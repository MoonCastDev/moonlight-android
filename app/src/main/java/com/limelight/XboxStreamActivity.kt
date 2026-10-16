package com.limelight

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.limelight.home.QuickMenu
import com.limelight.home.Xbox
import com.xstreaming.nano.NanoRustBridge

/**
 * Xbox Remote Play on this TV, natively: XStreaming's nano-rs engine (Rust WebRTC + the Xbox streaming protocol)
 * negotiates the session and hands frames and audio to MediaCodec through [NanoRustBridge]. This activity signs
 * the session in ([Xbox.streamAuth]), wakes the console, and turns this device's controller into an Xbox pad.
 *   EXTRA_CONSOLE_ID: the console's id from Xbox.consoles(); EXTRA_NAME: its name, for the screen.
 */
class XboxStreamActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val TAG = "MoonCast-Xbox"
        const val EXTRA_CONSOLE_ID = "xbox_console_id"
        const val EXTRA_NAME = "xbox_name"
        private val MENU_COMBO = setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)
    }

    private val bridge = NanoRustBridge()
    private var status: TextView? = null
    @Volatile private var closed = false
    @Volatile private var ready = false        // stream info handed to the engine
    private var surfaceUp = false
    private var started = false
    private val startedAt = System.currentTimeMillis()
    private val consoleId by lazy { intent.getStringExtra(EXTRA_CONSOLE_ID) ?: "" }
    private val consoleName by lazy { intent.getStringExtra(EXTRA_NAME) ?: "Xbox" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sv = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(0xFFE8ECFF.toInt()); textSize = 22f; gravity = Gravity.CENTER
            text = "Connecting to $consoleName…"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(sv, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        })
        sv.holder.addCallback(this)
        if (!bridge.isNativeAvailable) { showStatus("This build of MoonCast doesn't include the Xbox player."); return }
        bridge.setRumbleListener { _, duration, _, _, weak, strong, _, _ -> rumble(duration, weak, strong) }
        Thread { connect() }.start()
        watchStatus()
    }

    private fun showStatus(t: String?) = runOnUiThread {
        status?.let { if (t == null) it.visibility = View.GONE else { it.text = t; it.visibility = View.VISIBLE } }
    }

    /** Sign in, wake the console, and hand the session to the engine. Runs off the UI thread. */
    private fun connect() {
        try {
            showStatus("Waking $consoleName…")
            // Harmless if it's already on; needs the console's "Sleep" power mode and remote features.
            try { Xbox.wake(this, consoleId) } catch (e: Exception) { Log.w(TAG, "wake: $e") }
            showStatus("Signing in…")
            val auth = Xbox.streamAuth(this)
            if (closed) return
            val res = if (linkMbps() in 1..59) 720 else 1080   // same rule as the PS5: keep 60 fps, step down pixels
            Log.i(TAG, "stream $consoleName at ${res}p via ${auth.baseUri}")
            bridge.updateStreamInfo(consoleId, "home", "nano", auth.baseUri, auth.gsToken, auth.connectToken, res,
                    "en-US", null, null, null, true, false, true, true, true)
            ready = true
            runOnUiThread { maybeStart() }
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            showStatus(if (!Xbox.signedIn(this)) "Sign in to Xbox first: Settings, Xbox."
                    else "Couldn't start Xbox Remote Play. Check the Xbox is on or asleep, with remote features on.\n\n${e.message ?: ""}".trim())
        }
    }

    private fun maybeStart() {
        if (started || !ready || !surfaceUp || closed) return
        started = true
        bridge.start()
    }

    /** The engine reports its stage; show it until the first frame, and explain a failure. */
    private fun watchStatus() {
        val h = android.os.Handler(mainLooper)
        h.postDelayed(object : Runnable {
            override fun run() {
                if (closed) return
                if (bridge.renderedVideoFrameCount > 0) showStatus(null)
                else if (started && bridge.sessionStatusText.isNotBlank()) showStatus(bridge.sessionStatusText)
                if (bridge.hasTerminalSessionError()) {
                    showStatus("The Xbox ended the stream.\n${bridge.sessionStatusText}"); return
                }
                h.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun linkMbps(): Int = try {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION") wm.connectionInfo?.linkSpeed ?: 0
    } catch (e: Exception) { 0 }

    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
        bridge.bindSurface(h.surface, w, hgt)
        surfaceUp = true
        maybeStart()
    }
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceUp = false; bridge.releaseSurface() }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() }

    override fun onDestroy() {
        closed = true
        try { bridge.stop() } catch (e: Throwable) { }
        try { bridge.release() } catch (e: Throwable) { }
        super.onDestroy()
    }

    // ---- this device's controller -> Xbox pad ----
    private val held = HashSet<Int>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f; private var hatX = 0f; private var hatY = 0f
    private var padDeviceId = -1
    private var comboPending = false
    private val mutedPads = HashSet<Int>()

    private fun isPadKey(code: Int) = KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val fromPad = (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (menu.isShowing) return menu.onKey(e)
        if (fromPad) padDeviceId = e.deviceId
        if (code == KeyEvent.KEYCODE_BACK && !fromPad) {   // TV remote / Odin back: Quick menu (the pad's B is B)
            if (e.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        if (fromPad && e.deviceId in mutedPads) return true
        if (isPadKey(code)) {
            if (e.repeatCount == 0) {
                if (e.action == KeyEvent.ACTION_DOWN) held.add(code) else if (e.action == KeyEvent.ACTION_UP) held.remove(code)
                if (held.containsAll(MENU_COMBO)) { comboPending = true; sendNeutral() }
                if (comboPending) { if (held.isEmpty()) { comboPending = false; openMenu() }; return true }
                send()
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (menu.isShowing) return menu.onMotion(e)
        if (e.deviceId in mutedPads) return true
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            padDeviceId = e.deviceId
            fun dz(v: Float) = if (Math.abs(v) < 0.08f) 0f else v
            lx = dz(e.getAxisValue(MotionEvent.AXIS_X)); ly = dz(e.getAxisValue(MotionEvent.AXIS_Y))
            rx = dz(e.getAxisValue(MotionEvent.AXIS_Z)); ry = dz(e.getAxisValue(MotionEvent.AXIS_RZ))
            lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
            rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
            hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            send()
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    private fun send() {
        if (!started) return
        fun h(k: Int) = if (held.contains(k)) 1 else 0
        // Android's sticks are down-positive like the web Gamepad API; XStreaming's driver flips Y before the engine.
        bridge.sendGamepadState(0, h(KeyEvent.KEYCODE_BUTTON_MODE), h(KeyEvent.KEYCODE_BUTTON_START), h(KeyEvent.KEYCODE_BUTTON_SELECT),
                h(KeyEvent.KEYCODE_BUTTON_A), h(KeyEvent.KEYCODE_BUTTON_B), h(KeyEvent.KEYCODE_BUTTON_X), h(KeyEvent.KEYCODE_BUTTON_Y),
                maxOf(h(KeyEvent.KEYCODE_DPAD_UP), if (hatY < -.5f) 1 else 0), maxOf(h(KeyEvent.KEYCODE_DPAD_DOWN), if (hatY > .5f) 1 else 0),
                maxOf(h(KeyEvent.KEYCODE_DPAD_LEFT), if (hatX < -.5f) 1 else 0), maxOf(h(KeyEvent.KEYCODE_DPAD_RIGHT), if (hatX > .5f) 1 else 0),
                h(KeyEvent.KEYCODE_BUTTON_L1), h(KeyEvent.KEYCODE_BUTTON_R1), h(KeyEvent.KEYCODE_BUTTON_THUMBL), h(KeyEvent.KEYCODE_BUTTON_THUMBR),
                lx, -ly, rx, -ry,
                maxOf(lt, h(KeyEvent.KEYCODE_BUTTON_L2).toFloat()), maxOf(rt, h(KeyEvent.KEYCODE_BUTTON_R2).toFloat()))
    }

    private fun sendNeutral() {
        if (!started) return
        bridge.sendGamepadState(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    /** Rumble on the pad that played last, like the PS5 player. */
    private fun rumble(durationMs: Int, weak: Double, strong: Double) {
        val d = InputDevice.getDevice(padDeviceId) ?: return
        val amp = (maxOf(weak, strong).coerceIn(0.0, 1.0) * 255).toInt()
        try {
            @Suppress("DEPRECATION") val v = d.vibrator
            if (!v.hasVibrator()) return
            if (amp <= 0) { v.cancel(); return }
            if (android.os.Build.VERSION.SDK_INT >= 26)
                v.vibrate(android.os.VibrationEffect.createOneShot(maxOf(durationMs, 20).toLong(), amp.coerceIn(1, 255)))
            else @Suppress("DEPRECATION") v.vibrate(maxOf(durationMs, 20).toLong())
        } catch (e: Exception) { }
    }

    // ---- Quick menu ----
    private val menu by lazy { QuickMenu(this) }

    private fun liveStats(): List<Pair<String, String>> {
        fun f(v: Double, fmt: String) = if (v < 0) "…" else fmt.format(java.util.Locale.US, v)
        if (bridge.renderedVideoFrameCount <= 0) {
            val min = (System.currentTimeMillis() - startedAt) / 60000
            return listOf(bridge.sessionStage.ifBlank { "Starting" } to "Stage", "$min min" to "Waiting for")
        }
        return listOf(
            f(minOf(bridge.webRtcFps, 60.0), "%.0f") to "Frames per second",
            f(bridge.webRtcRttMs, "%.0f ms") to "Network delay",
            f(bridge.webRtcBitrateMbps, "%.1f Mbps") to "Picture quality",
            f(bridge.webRtcPacketLossPercent, "%.1f%%") to "Lost packets")
    }

    private fun openMenu() {
        if (isFinishing) return
        sendNeutral()
        menu.show(consoleName, "Xbox Remote Play. " + QuickMenu.COMBO_LABEL + " opens this menu", { liveStats() }, listOf(
            QuickMenu.Item("⊗", "Xbox button", "Opens the guide", false) { tapGuide() },
            QuickMenu.Item("←", "Disconnect", "The game keeps running", false) { finish() },
            QuickMenu.Item("⏻", "Turn off the Xbox", "Ends the game", true) { turnOff() }))
    }

    private fun tapGuide() {
        menu.hide()
        val h = android.os.Handler(mainLooper)
        h.post { held.add(KeyEvent.KEYCODE_BUTTON_MODE); send() }
        h.postDelayed({ held.remove(KeyEvent.KEYCODE_BUTTON_MODE); send() }, 120)
    }

    private fun turnOff() {
        menu.hide()
        Thread { try { Xbox.turnOff(this, consoleId) } catch (e: Exception) { Log.w(TAG, "turn off: $e") }; runOnUiThread { finish() } }.start()
    }
}
