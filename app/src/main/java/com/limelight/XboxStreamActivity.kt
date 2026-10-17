package com.limelight

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.limelight.home.QuickMenu
import com.limelight.home.Xbox
import com.limelight.xbox.XboxRtc
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Xbox Remote Play on this TV over Google's WebRTC ([XboxRtc]): hardware decoding, Google's jitter buffer and
 * congestion control, the same stack XStreaming's player uses. This activity signs the session in
 * ([Xbox.streamAuth]), wakes the console, draws the picture and turns this device's controllers into Xbox pads.
 *   EXTRA_CONSOLE_ID: the console's id from Xbox.consoles(); EXTRA_NAME: its name, for the screen.
 */
class XboxStreamActivity : Activity() {
    companion object {
        private const val TAG = "MoonCast-Xbox"
        const val EXTRA_CONSOLE_ID = "xbox_console_id"
        const val EXTRA_NAME = "xbox_name"
        private val MENU_COMBO = setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)
    }

    private val egl by lazy { EglBase.create() }
    private var renderer: SurfaceViewRenderer? = null
    private var rtc: XboxRtc? = null
    private var status: TextView? = null
    @Volatile private var closed = false
    private var started = false               // the stream is up: pad input goes to the Xbox
    @Volatile private var firstFrame = false
    private val startedAt = System.currentTimeMillis()
    private val consoleId by lazy { intent.getStringExtra(EXTRA_CONSOLE_ID) ?: "" }
    private val consoleName by lazy { intent.getStringExtra(EXTRA_NAME) ?: "Xbox" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The decoder rules the Xbox decoder borrows (MediaCodecHelper) need this first, as in Moonlight's Game
        com.limelight.binding.video.MediaCodecHelper.initialize(this,
                com.limelight.preferences.GlPreferences.readPreferences(this).glRenderer)
        val r = SurfaceViewRenderer(this).also { renderer = it }
        r.init(egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() { firstFrame = true; showStatus(null) }
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                Log.i(TAG, "picture ${w}x$h"); runOnUiThread { applyShape(w, h) }
            }
        })
        r.setEnableHardwareScaler(true)
        applyShape(0, 0)
        status = TextView(this).apply {
            setTextColor(0xFFE8ECFF.toInt()); textSize = 22f; gravity = Gravity.CENTER
            text = "Connecting to $consoleName…"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(r, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        })
        Thread { connect() }.start()
    }

    private fun showStatus(t: String?) = runOnUiThread {
        status?.let { if (t == null) it.visibility = View.GONE else { it.text = t; it.visibility = View.VISIBLE } }
    }

    /** Sign in, wake the console, and start the WebRTC session. Runs off the UI thread. */
    private fun connect() {
        try {
            showStatus("Waking $consoleName…")
            // Harmless if it's already on; needs the console's "Sleep" power mode and remote features.
            try { Xbox.wake(this, consoleId) } catch (e: Exception) { Log.w(TAG, "wake: $e") }
            showStatus("Signing in…")
            val auth = Xbox.streamAuth(this)
            if (closed) return
            // Settings, Xbox, Picture. No bitrate is sent; the Xbox picks quality from the device type this implies:
            // 720 = Android, 1080 = Windows, 1081 = Tizen. Auto: 1080, or 720 on a weak link, like the PS5.
            val pick = prefs.getString("xbox_resolution", "auto")
            // Auto also follows the screen: a 1440p-or-larger panel asks for the high-quality profile, like Native
            val res = when (pick) { "720" -> 720; "1080" -> 1080; "1081" -> 1081
                else -> if (linkMbps() in 1..59) 720 else if (screenHeight() >= 1440) 1081 else 1080 }
            Log.i(TAG, "stream $consoleName at ${res}p via ${auth.baseUri}")
            val session = XboxRtc(this, egl, consoleId, auth.baseUri, auth.gsToken, auth.connectToken, res,
                    prefs.getString("xbox_language", "en-US") ?: "en-US", coop, prefs.getInt("xbox_bitrate", 0),
                    prefs.getBoolean("checkbox_ultra_low_latency", false), renderer!!, object : XboxRtc.Listener {
                override fun onStatus(text: String) { if (!firstFrame) showStatus(text) }
                override fun onConnected() { runOnUiThread { if (!started) { started = true; send(); pollStats() } } }
                override fun onFailed(reason: String) { showStatus("The Xbox stream stopped.\n$reason") }
                override fun onRumble(gamepad: Int, weak: Double, strong: Double, durationMs: Int) {
                    runOnUiThread { rumble(gamepad, durationMs, weak, strong) }
                }
            })
            rtc = session
            if (closed) { session.stop(); return }
            session.start()
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            showStatus(if (!Xbox.signedIn(this)) "Sign in to Xbox first: Settings, Xbox."
                    else "Couldn't start Xbox Remote Play. Check the Xbox is on or asleep, with remote features on.\n\n${e.message ?: ""}".trim())
        }
    }

    private fun screenHeight(): Int {
        val d = windowManager.defaultDisplay
        return if (android.os.Build.VERSION.SDK_INT >= 23) minOf(d.mode.physicalWidth, d.mode.physicalHeight)
               else android.graphics.Point().also { d.getRealSize(it) }.let { minOf(it.x, it.y) }
    }

    /**
     * Settings, Video, Picture shape and Zoom (shared with the PC player): Fit keeps the shape with bars, Fill crops to
     * cover the screen, Stretch fills it ignoring the shape; Zoom enlarges for TVs that cut off the edges.
     */
    private fun applyShape(videoW: Int, videoH: Int) {
        val r = renderer ?: return
        val shape = prefs.getString("list_video_scale_mode", if (prefs.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit")
        val zoom = prefs.getInt("seekbar_video_zoom", 100) / 100f
        r.setScalingType(if (shape == "fill") RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        r.post {
            var sx = zoom; var sy = zoom
            val parent = r.parent as? View
            if (shape == "stretch" && parent != null && r.width > 0 && r.height > 0 && videoW > 0 && videoH > 0) {
                sx *= parent.width.toFloat() / r.width; sy *= parent.height.toFloat() / r.height
            }
            r.scaleX = sx; r.scaleY = sy
        }
    }

    private fun linkMbps(): Int = try {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        @Suppress("DEPRECATION") wm.connectionInfo?.linkSpeed ?: 0
    } catch (e: Exception) { 0 }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() }

    override fun onDestroy() {
        closed = true
        try { rtc?.stop() } catch (e: Throwable) { }
        try { renderer?.release() } catch (e: Throwable) { }
        try { egl.release() } catch (e: Throwable) { }
        super.onDestroy()
    }

    // ---- this device's controllers -> Xbox pads ----
    // Settings, Xbox: deadzone, edge compensation, Swap A/B and X/Y, short triggers, co-op (second pad = player 2).
    private val prefs by lazy { android.preference.PreferenceManager.getDefaultSharedPreferences(this) }
    private val deadzone by lazy { prefs.getInt("xbox_deadzone", 8) / 100f }
    private val edge by lazy { prefs.getBoolean("xbox_edge", false) }
    private val swapAB by lazy { prefs.getBoolean("xbox_swap_ab", false) }
    private val swapXY by lazy { prefs.getBoolean("xbox_swap_xy", false) }
    private val shortTriggers by lazy { prefs.getBoolean("xbox_short_triggers", false) }
    private val coop by lazy { prefs.getBoolean("xbox_coop", false) }

    /** One pad's live state. index = the Xbox player slot (0, or 1 for the second pad in co-op). */
    private class Pad(val index: Int) {
        val held = HashSet<Int>()
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f
        var lt = 0f; var rt = 0f; var hatX = 0f; var hatY = 0f
    }
    private val pads = LinkedHashMap<Int, Pad>()
    private var padDeviceId = -1
    private var comboPending = false
    private val mutedPads = HashSet<Int>()

    /** The pad for this device: player 1, or player 2 for a second pad when co-op is on (more pads share player 1). */
    private fun pad(deviceId: Int): Pad {
        val d = InputDevice.getDevice(deviceId)
        val isPad = d != null && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
        // The TV remote's arrows drive player 1 too, but never take the co-op slot.
        if (!isPad) return pads.getOrPut(-2) { Pad(0) }
        return pads.getOrPut(deviceId) {
            Pad(if (coop && pads.any { it.key != -2 } && pads.values.none { it.index == 1 }) 1 else 0)
        }
    }

    private fun isPadKey(code: Int) = KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    // A DualSense / DualShock 4 on an old kernel (the home Mi stick, Android 9) uses Android's generic layout: every
    // button shifted and L2/R2 on RX/RY. Same detection and remap as the PS5 player.
    private val oddSony = HashMap<Int, Boolean>()
    private fun isOddSony(deviceId: Int): Boolean = oddSony.getOrPut(deviceId) {
        val d = InputDevice.getDevice(deviceId)
        d != null && d.vendorId == 0x054c && d.hasKeys(KeyEvent.KEYCODE_BUTTON_C)[0]
    }
    private fun oddSonyKey(scan: Int): Int? = when (scan) {
        304 -> KeyEvent.KEYCODE_BUTTON_X        // Square -> X
        305 -> KeyEvent.KEYCODE_BUTTON_A        // Cross -> A
        306 -> KeyEvent.KEYCODE_BUTTON_B        // Circle -> B
        307 -> KeyEvent.KEYCODE_BUTTON_Y        // Triangle -> Y
        308 -> KeyEvent.KEYCODE_BUTTON_L1
        309 -> KeyEvent.KEYCODE_BUTTON_R1
        310 -> KeyEvent.KEYCODE_BUTTON_L2
        311 -> KeyEvent.KEYCODE_BUTTON_R2
        312 -> KeyEvent.KEYCODE_BUTTON_SELECT   // Create -> View
        313 -> KeyEvent.KEYCODE_BUTTON_START    // Options -> Menu
        314 -> KeyEvent.KEYCODE_BUTTON_THUMBL
        315 -> KeyEvent.KEYCODE_BUTTON_THUMBR
        316 -> KeyEvent.KEYCODE_BUTTON_MODE     // PS -> Xbox button
        else -> null
    }

    override fun dispatchKeyEvent(raw: KeyEvent): Boolean {
        val e = if (isOddSony(raw.deviceId)) oddSonyKey(raw.scanCode)?.let {
            KeyEvent(raw.downTime, raw.eventTime, raw.action, it, raw.repeatCount, raw.metaState, raw.deviceId, raw.scanCode, raw.flags, raw.source)
        } ?: raw else raw
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
                val p = pad(e.deviceId)
                if (e.action == KeyEvent.ACTION_DOWN) p.held.add(code) else if (e.action == KeyEvent.ACTION_UP) p.held.remove(code)
                if (p.held.containsAll(MENU_COMBO)) { comboPending = true; sendNeutral() }
                if (comboPending) { if (p.held.isEmpty()) { comboPending = false; openMenu() }; return true }
                send(p)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** Deadzone, then optional edge compensation: rescale so the stick still reaches 100% at its edge. */
    private fun stick(v: Float): Float {
        val a = Math.abs(v)
        if (a < deadzone) return 0f
        val out = if (edge) ((a - deadzone) / (1f - deadzone)).coerceAtMost(1f) else a
        return if (v < 0) -out else out
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (menu.isShowing) return menu.onMotion(e)
        if (e.deviceId in mutedPads) return true
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            padDeviceId = e.deviceId
            val p = pad(e.deviceId)
            p.lx = stick(e.getAxisValue(MotionEvent.AXIS_X)); p.ly = stick(e.getAxisValue(MotionEvent.AXIS_Y))
            p.rx = stick(e.getAxisValue(MotionEvent.AXIS_Z)); p.ry = stick(e.getAxisValue(MotionEvent.AXIS_RZ))
            if (isOddSony(e.deviceId)) {   // generic layout: L2/R2 on RX/RY, -1 (released) .. 1 (pressed)
                p.lt = (e.getAxisValue(MotionEvent.AXIS_RX) + 1f) / 2f
                p.rt = (e.getAxisValue(MotionEvent.AXIS_RY) + 1f) / 2f
            } else {
                p.lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
                p.rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
            }
            p.hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); p.hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            send(p)
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    /** Hand every pad's latest state to the session; it sends them 62.5 times a second. */
    private fun send() {
        val r = rtc ?: return
        r.pads = if (pads.isEmpty()) listOf(XboxRtc.PadFrame(0)) else pads.values.groupBy { it.index }.map { (idx, ps) -> frame(idx, ps) }
    }

    private fun send(@Suppress("UNUSED_PARAMETER") p: Pad) = send()

    /** One Xbox pad slot from the pads that drive it (the TV remote shares player 1). */
    private fun frame(index: Int, ps: List<Pad>): XboxRtc.PadFrame {
        fun h(k: Int) = ps.any { it.held.contains(k) }
        fun trig(v: Float) = if (shortTriggers) (if (v > 0.1f) 1f else 0f) else v
        val main = ps.firstOrNull { it.lx != 0f || it.ly != 0f || it.rx != 0f || it.ry != 0f || it.lt > 0f || it.rt > 0f } ?: ps.first()
        val a = h(KeyEvent.KEYCODE_BUTTON_A); val b = h(KeyEvent.KEYCODE_BUTTON_B)
        val x = h(KeyEvent.KEYCODE_BUTTON_X); val y = h(KeyEvent.KEYCODE_BUTTON_Y)
        return XboxRtc.PadFrame(index).apply {
            nexus = h(KeyEvent.KEYCODE_BUTTON_MODE); menu = h(KeyEvent.KEYCODE_BUTTON_START); view = h(KeyEvent.KEYCODE_BUTTON_SELECT)
            this.a = if (swapAB) b else a; this.b = if (swapAB) a else b
            this.x = if (swapXY) y else x; this.y = if (swapXY) x else y
            up = h(KeyEvent.KEYCODE_DPAD_UP) || ps.any { it.hatY < -.5f }; down = h(KeyEvent.KEYCODE_DPAD_DOWN) || ps.any { it.hatY > .5f }
            left = h(KeyEvent.KEYCODE_DPAD_LEFT) || ps.any { it.hatX < -.5f }; right = h(KeyEvent.KEYCODE_DPAD_RIGHT) || ps.any { it.hatX > .5f }
            lb = h(KeyEvent.KEYCODE_BUTTON_L1); rb = h(KeyEvent.KEYCODE_BUTTON_R1)
            ls = h(KeyEvent.KEYCODE_BUTTON_THUMBL); rs = h(KeyEvent.KEYCODE_BUTTON_THUMBR)
            lx = main.lx; ly = main.ly; rx = main.rx; ry = main.ry
            lt = trig(maxOf(main.lt, if (h(KeyEvent.KEYCODE_BUTTON_L2)) 1f else 0f))
            rt = trig(maxOf(main.rt, if (h(KeyEvent.KEYCODE_BUTTON_R2)) 1f else 0f))
        }
    }

    private fun sendNeutral() {
        val r = rtc ?: return
        val slots = if (pads.isEmpty()) listOf(0) else pads.values.map { it.index }.distinct()
        r.pads = slots.map { XboxRtc.PadFrame(it) }
    }

    /** Rumble on the pad playing that slot (player 1: the pad that played last), like the PS5 player. */
    private fun rumble(slot: Int, durationMs: Int, weak: Double, strong: Double) {
        val dev = if (slot == 0) padDeviceId else pads.entries.firstOrNull { it.value.index == slot }?.key ?: return
        val d = InputDevice.getDevice(dev) ?: return
        // Settings, Xbox, Rumble: Off / Weak / Medium / Strong, like XStreaming's vibration intensity
        val scale = when (prefs.getString("xbox_rumble", "strong")) { "off" -> 0.0; "weak" -> 0.35; "medium" -> 0.65; else -> 1.0 }
        if (scale == 0.0) return
        val amp = (maxOf(weak, strong).coerceIn(0.0, 1.0) * scale * 255).toInt()
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

    // Live numbers from WebRTC's own stats, refreshed every second (and logged every 5 s for diagnosis)
    @Volatile private var last: XboxRtc.Stats? = null
    private var statTicks = 0
    private fun pollStats() {
        val h = android.os.Handler(mainLooper)
        h.post(object : Runnable {
            override fun run() {
                if (closed) return
                rtc?.stats { st ->
                    last = st
                    if (statTicks++ % 5 == 0) Log.i(TAG, "stats ${st.width}x${st.height} fps=${st.fps} mbps=${"%.2f".format(java.util.Locale.US, st.mbps)} loss=${"%.2f".format(java.util.Locale.US, st.lossPct)}% rtt=${st.rttMs}")
                }
                h.postDelayed(this, 1000)
            }
        })
    }

    private fun liveStats(): List<Pair<String, String>> {
        fun f(v: Double, fmt: String) = if (v < 0) "…" else fmt.format(java.util.Locale.US, v)
        val st = last
        if (st == null || !firstFrame) {
            val min = (System.currentTimeMillis() - startedAt) / 60000
            return listOf((if (started) "Connected" else "Starting") to "Stage", "$min min" to "Waiting for")
        }
        return listOf(
            f(minOf(st.fps, 60.0), "%.0f") to "Frames per second",
            f(st.rttMs, "%.0f ms") to "Network delay",
            f(st.mbps, "%.1f Mbps") to "Picture quality",
            f(st.lossPct, "%.1f%%") to "Lost packets")
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
        val p = pads.values.firstOrNull { it.index == 0 } ?: pad(padDeviceId)
        h.post { p.held.add(KeyEvent.KEYCODE_BUTTON_MODE); send(p) }
        h.postDelayed({ p.held.remove(KeyEvent.KEYCODE_BUTTON_MODE); send(p) }, 120)
    }

    private fun turnOff() {
        menu.hide()
        Thread { try { Xbox.turnOff(this, consoleId) } catch (e: Exception) { Log.w(TAG, "turn off: $e") }; runOnUiThread { finish() } }.start()
    }
}
