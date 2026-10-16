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
            // Settings, Xbox, Picture. The engine sends no bitrate; the Xbox picks quality from the device type this
            // implies: 720 = Android, 1080 = Windows, 1081 = Tizen (XStreaming's "1080P (HQ)"). Auto: 1080, or 720 on
            // a weak link, the same rule as the PS5.
            val pick = android.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("xbox_resolution", "auto")
            val res = when (pick) { "720" -> 720; "1080" -> 1080; "1081" -> 1081; else -> if (linkMbps() in 1..59) 720 else 1080 }
            Log.i(TAG, "stream $consoleName at ${res}p via ${auth.baseUri}")
            bridge.updateStreamInfo(consoleId, "home", "nano", auth.baseUri, auth.gsToken, auth.connectToken, res,
                    prefs.getString("xbox_language", "en-US"), null, null, null, true, coop, true, true, true)
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
        pollPad()
    }

    // The Xbox only takes a pad that reports all the time: XStreaming re-sends the whole state 60 times a second
    // (its polling_rate), and a pad that only reports changes is ignored.
    private fun pollPad() {
        val h = android.os.Handler(mainLooper)
        h.post(object : Runnable {
            override fun run() {
                if (closed) return
                if (!menu.isShowing && !comboPending) send()
                h.postDelayed(this, 16)
            }
        })
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

    /** Every pad's state; at least player 1 (neutral) so the Xbox always sees a controller. */
    private fun send() {
        if (pads.isEmpty()) send(Pad(0)) else pads.values.forEach { send(it) }
    }

    private fun send(p: Pad) {
        if (!started) return
        fun h(k: Int) = if (p.held.contains(k)) 1 else 0
        fun trig(v: Float) = if (shortTriggers) (if (v > 0.1f) 1f else 0f) else v
        val a = h(KeyEvent.KEYCODE_BUTTON_A); val b = h(KeyEvent.KEYCODE_BUTTON_B)
        val x = h(KeyEvent.KEYCODE_BUTTON_X); val y = h(KeyEvent.KEYCODE_BUTTON_Y)
        // Raw Android axes, as XStreaming's SdlGamepadManager sends them; the engine flips Y for the Xbox.
        bridge.sendGamepadState(p.index, h(KeyEvent.KEYCODE_BUTTON_MODE), h(KeyEvent.KEYCODE_BUTTON_START), h(KeyEvent.KEYCODE_BUTTON_SELECT),
                if (swapAB) b else a, if (swapAB) a else b, if (swapXY) y else x, if (swapXY) x else y,
                maxOf(h(KeyEvent.KEYCODE_DPAD_UP), if (p.hatY < -.5f) 1 else 0), maxOf(h(KeyEvent.KEYCODE_DPAD_DOWN), if (p.hatY > .5f) 1 else 0),
                maxOf(h(KeyEvent.KEYCODE_DPAD_LEFT), if (p.hatX < -.5f) 1 else 0), maxOf(h(KeyEvent.KEYCODE_DPAD_RIGHT), if (p.hatX > .5f) 1 else 0),
                h(KeyEvent.KEYCODE_BUTTON_L1), h(KeyEvent.KEYCODE_BUTTON_R1), h(KeyEvent.KEYCODE_BUTTON_THUMBL), h(KeyEvent.KEYCODE_BUTTON_THUMBR),
                p.lx, p.ly, p.rx, p.ry,
                trig(maxOf(p.lt, h(KeyEvent.KEYCODE_BUTTON_L2).toFloat())), trig(maxOf(p.rt, h(KeyEvent.KEYCODE_BUTTON_R2).toFloat())))
    }

    private fun sendNeutral() {
        if (!started) return
        val slots = if (pads.isEmpty()) listOf(0) else pads.values.map { it.index }.distinct()
        slots.forEach { bridge.sendGamepadState(it, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f) }
    }

    /** Rumble on the pad that played last, like the PS5 player. */
    private fun rumble(durationMs: Int, weak: Double, strong: Double) {
        val d = InputDevice.getDevice(padDeviceId) ?: return
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
        val p = pads.values.firstOrNull { it.index == 0 } ?: pad(padDeviceId)
        h.post { p.held.add(KeyEvent.KEYCODE_BUTTON_MODE); send(p) }
        h.postDelayed({ p.held.remove(KeyEvent.KEYCODE_BUTTON_MODE); send(p) }, 120)
    }

    private fun turnOff() {
        menu.hide()
        Thread { try { Xbox.turnOff(this, consoleId) } catch (e: Exception) { Log.w(TAG, "turn off: $e") }; runOnUiThread { finish() } }.start()
    }
}
