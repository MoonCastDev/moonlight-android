package com.limelight.handheld

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.limelight.binding.PlatformBinding
import com.limelight.computers.ComputerManagerService
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.av.video.VideoDecoderRenderer
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.utils.ServerHelper

/**
 * The controller straight to the PC: while a PC game plays on a TV, this handheld opens its own, controller-only
 * Sunshine session to the same game and sends its pad as controller 0. That skips the TV's Wi-Fi hop (a few ms
 * straight to the PC, often tens of ms through a TV on a busy network), like the old phone app's InputSession did
 * (640x360, a picture it throws away). A PC that refuses a second session makes this fail, and controller mode falls
 * back to the TV's relay by itself.
 *
 * The session: 640x360 (small, so a host script that matches the display to each client can tell it apart), 15 fps,
 * 500 kbps, H.264, resume only
 * (never launches or quits). The picture is never decoded (the decoder just drops every frame) and the sound is never
 * played (the audio renderer drops it) - only the network packets arrive.
 */
class DirectPad(ctx: Context, private val pcUuid: String, private val appId: Int, private val appName: String) {
    enum class State { WAITING, CONNECTING, CONNECTED, FAILED, STOPPED }

    companion object { private const val TAG = "MoonCast-DirectPad" }

    private val app = ctx.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    @Volatile var state = State.WAITING; private set
    @Volatile var pcName: String = "the PC"; private set
    /** What went wrong last (for the status line). */
    @Volatile var why: String? = null; private set
    /** Called on the main thread whenever [state] changes. */
    var onState: ((State) -> Unit)? = null

    @Volatile private var stopped = false
    @Volatile private var conn: NvConnection? = null          // the session, once it's up (input goes here)
    @Volatile private var live: NvConnection? = null          // the session being set up or running
    // NvConnection.stop() releases Moonlight's one-connection semaphore: call it exactly once per started session
    private val stoppedConns = java.util.Collections.newSetFromMap(java.util.WeakHashMap<NvConnection, Boolean>())
    private fun stopOnce(c: NvConnection) {
        synchronized(stoppedConns) { if (!stoppedConns.add(c)) return }
        Thread({ try { c.stop() } catch (e: Throwable) { Log.w(TAG, "stop: $e") } }, "direct-pad-stop").start()
    }
    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private var bound = false
    @Volatile private var everConnected = false

    private val sc = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val b = service as ComputerManagerService.ComputerManagerBinder
            binder = b
            Thread({ b.waitForReady(); run(b) }, "direct-pad").start()
        }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
    }

    fun start() {
        bound = app.bindService(Intent(app, ComputerManagerService::class.java), sc, Context.BIND_AUTO_CREATE)
        if (!bound) set(State.FAILED, "Moonlight's PC manager didn't start")
    }

    fun stop() {
        if (stopped) return
        stopped = true
        set(State.STOPPED, null)
        val up = conn; conn = null
        try { up?.sendControllerInput(0, 0x1, 0, 0, 0, 0, 0, 0, 0) } catch (e: Throwable) { }   // let go of everything
        live?.let { stopOnce(it) }; live = null
        if (bound) try { app.unbindService(sc) } catch (e: Exception) { }
        bound = false
    }

    private fun set(s: State, reason: String?) {
        if (stopped && s != State.STOPPED) return
        state = s; if (reason != null) why = reason
        ui.post { onState?.invoke(s) }
    }

    /** Wait for the TV's game to be running on the PC, then join it. Reconnects if the session drops. */
    private fun run(b: ComputerManagerService.ComputerManagerBinder) {
        var tries = 0
        while (!stopped) {
            val pc = b.getComputer(pcUuid)
            if (pc == null) { set(State.FAILED, "This device doesn't know that PC"); return }
            pcName = pc.name ?: pcName
            // 1. the game is running on the PC (the TV started it): never launch it ourselves
            val running = waitForGame(b, pc, if (everConnected) 20_000 else 45_000)
            if (stopped) return
            if (!running) { set(State.FAILED, "${appName} isn't running on $pcName"); return }
            // 2. join it, controller only
            set(State.CONNECTING, null)
            val ended = java.util.concurrent.CountDownLatch(1)
            val ok = connect(b, pc) { ended.countDown() }
            if (!ok) {
                if (++tries >= 3) { set(State.FAILED, why ?: "Couldn't reach $pcName"); return }
            } else tries = 0
            ended.await()   // until the session ends
            conn = null
            if (stopped) return
            Log.i(TAG, "direct session ended, reconnecting")
            try { Thread.sleep(1500) } catch (e: InterruptedException) { return }
        }
    }

    private fun waitForGame(b: ComputerManagerService.ComputerManagerBinder, pc: ComputerDetails, maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        var seenAt = 0L
        while (!stopped && System.currentTimeMillis() < end) {
            try {
                val http = NvHTTP(ServerHelper.getCurrentAddressFromComputer(pc), pc.httpsPort, b.uniqueId, pc.serverCert,
                        PlatformBinding.getCryptoProvider(app))
                val cur = http.getCurrentGame(http.getServerInfo(true))
                if (cur == appId) {
                    // give the TV's own session a moment to set up before we join next to it
                    if (seenAt == 0L) seenAt = System.currentTimeMillis()
                    if (everConnected || System.currentTimeMillis() - seenAt >= 2000) return true
                } else seenAt = 0L
            } catch (e: Exception) { Log.w(TAG, "poll: $e") }
            try { Thread.sleep(1000) } catch (e: InterruptedException) { return false }
        }
        return false
    }

    /** One session. True once it started; [onEnd] runs when it ends (or failed to start). */
    private fun connect(b: ComputerManagerService.ComputerManagerBinder, pc: ComputerDetails, onEnd: () -> Unit): Boolean {
        val addr = try { ServerHelper.getCurrentAddressFromComputer(pc) } catch (e: Exception) { why = "$pcName is offline"; onEnd(); return false }
        val cfg = StreamConfiguration.Builder()
                .setResolution(640, 360)
                .setLaunchRefreshRate(15).setRefreshRate(15)
                .setApp(NvApp(appName, appId, false))
                .setBitrate(500)
                .setEnableSops(false)
                .enableLocalAudioPlayback(false)
                .setMaxPacketSize(1392)
                .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
                .setSupportedVideoFormats(MoonBridge.VIDEO_FORMAT_H264)
                .setAttachedGamepadMask(0x1)   // /resume asks remoteControllersBitmap=1, gcmap=1: pad 0, player 1
                .setAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_STEREO)
                .setPersistGamepadsAfterDisconnect(false)
                .build()
        val c = NvConnection(app, addr, pc.httpsPort, b.uniqueId, cfg, PlatformBinding.getCryptoProvider(app), pc.serverCert)
        c.setResumeOnly(true)
        live = c
        val started = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun end() { if (done.compareAndSet(false, true)) { started.countDown(); onEnd() } }
        c.start(NoAudio, NoVideo, object : NvConnectionListener {
            override fun stageStarting(stage: String) {}
            override fun stageComplete(stage: String) {}
            override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
                // no stop() here: a failed start already let go of Moonlight's connection slot
                Log.w(TAG, "stage $stage failed: $errorCode"); if (why == null) why = "Couldn't join the game on $pcName"
                synchronized(stoppedConns) { stoppedConns.add(c) }
                if (live === c) live = null
                end()
            }
            override fun connectionStarted() {
                ok = true; everConnected = true
                conn = c
                if (stopped) { stopOnce(c); end(); return }
                Log.i(TAG, "controller session up on $pcName")
                // claim pad 0 now (remoteControllersBitmap=1 in /resume asked for it): Sunshine creates the pad at
                // once, before the first press, as an Xbox-style pad with analog triggers
                val all = ControllerPacket.A_FLAG or ControllerPacket.B_FLAG or ControllerPacket.X_FLAG or ControllerPacket.Y_FLAG or
                        ControllerPacket.LB_FLAG or ControllerPacket.RB_FLAG or ControllerPacket.BACK_FLAG or ControllerPacket.PLAY_FLAG or
                        ControllerPacket.LS_CLK_FLAG or ControllerPacket.RS_CLK_FLAG or ControllerPacket.UP_FLAG or ControllerPacket.DOWN_FLAG or
                        ControllerPacket.LEFT_FLAG or ControllerPacket.RIGHT_FLAG or ControllerPacket.SPECIAL_BUTTON_FLAG
                try { c.sendControllerArrivalEvent(0, 0x1, MoonBridge.LI_CTYPE_XBOX, all, MoonBridge.LI_CCAP_ANALOG_TRIGGERS) }
                catch (e: Throwable) { Log.w(TAG, "arrival: $e") }
                set(State.CONNECTED, null)
                started.countDown()
                push()
            }
            override fun connectionTerminated(errorCode: Int) {
                Log.i(TAG, "controller session ended: $errorCode")
                conn = null
                stopOnce(c)
                if (live === c) live = null
                if (!stopped) set(State.CONNECTING, null)
                end()
            }
            override fun connectionStatusUpdate(connectionStatus: Int) {}
            override fun displayMessage(message: String) { why = message }
            override fun displayTransientMessage(message: String) {}
            override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {}
            override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {}
            override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {}
            override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {}
            override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {}
        })
        started.await(20, java.util.concurrent.TimeUnit.SECONDS)
        if (!ok && !done.get()) { why = "$pcName didn't answer"; stopOnce(c); if (live === c) live = null; end() }
        return ok
    }

    // ---- the pad: W3C Standard Gamepad state -> Moonlight controller 0 ----
    private val bt = FloatArray(PadServer.BUTTONS)
    private val ax = FloatArray(PadServer.AXES)

    fun update(b: FloatArray, a: FloatArray) {
        System.arraycopy(b, 0, bt, 0, bt.size); System.arraycopy(a, 0, ax, 0, ax.size)
        push()
    }

    private fun push() {
        val c = conn ?: return
        var f = 0
        if (bt[0] > .5f) f = f or ControllerPacket.A_FLAG
        if (bt[1] > .5f) f = f or ControllerPacket.B_FLAG
        if (bt[2] > .5f) f = f or ControllerPacket.X_FLAG
        if (bt[3] > .5f) f = f or ControllerPacket.Y_FLAG
        if (bt[4] > .5f) f = f or ControllerPacket.LB_FLAG
        if (bt[5] > .5f) f = f or ControllerPacket.RB_FLAG
        if (bt[8] > .5f) f = f or ControllerPacket.BACK_FLAG
        if (bt[9] > .5f) f = f or ControllerPacket.PLAY_FLAG
        if (bt[10] > .5f) f = f or ControllerPacket.LS_CLK_FLAG
        if (bt[11] > .5f) f = f or ControllerPacket.RS_CLK_FLAG
        if (bt[12] > .5f) f = f or ControllerPacket.UP_FLAG
        if (bt[13] > .5f) f = f or ControllerPacket.DOWN_FLAG
        if (bt[14] > .5f) f = f or ControllerPacket.LEFT_FLAG
        if (bt[15] > .5f) f = f or ControllerPacket.RIGHT_FLAG
        if (bt[16] > .5f) f = f or ControllerPacket.SPECIAL_BUTTON_FLAG
        fun trig(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt().toByte()
        fun axis(v: Float) = Math.round(v * 32767).coerceIn(-32767, 32767).toShort()
        // W3C Y is down-positive, Moonlight's is up-positive (as in PhoneGamepad)
        c.sendControllerInput(0, 0x1, f, trig(bt[6]), trig(bt[7]), axis(ax[0]), axis(-ax[1]), axis(ax[2]), axis(-ax[3]))
    }

    /** The picture is thrown away: nothing is decoded or drawn. */
    private object NoVideo : VideoDecoderRenderer() {
        override fun setup(format: Int, width: Int, height: Int, redrawRate: Int) = 0
        override fun start() {}
        override fun stop() {}
        override fun submitDecodeUnit(decodeUnitData: ByteArray, decodeUnitLength: Int, decodeUnitType: Int, frameNumber: Int,
                                      frameType: Int, frameHostProcessingLatency: Char, receiveTimeUs: Long, enqueueTimeUs: Long) = MoonBridge.DR_OK
        override fun cleanup() {}
        override fun getCapabilities() = 0
        override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {}
    }

    /** The sound is never played. */
    private object NoAudio : AudioRenderer {
        override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int) = 0
        override fun start() {}
        override fun stop() {}
        override fun playDecodedAudio(audioData: ShortArray) {}
        override fun cleanup() {}
    }
}
