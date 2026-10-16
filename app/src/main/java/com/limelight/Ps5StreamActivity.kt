package com.limelight

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.ConnectedEvent
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.Event
import com.metallic.chiaki.lib.QuitEvent
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset

/**
 * Streams a PS5 (or PS4) natively with chiaki (libchiaki), rendered straight to a SurfaceView via MediaCodec -
 * the native MoonCast path for a "PlayStation" host. Connect info comes from the phone (Add PC -> PlayStation):
 *   EXTRA_HOST, EXTRA_REGIST_KEY (hex), EXTRA_MORNING (hex), EXTRA_PS5.
 * The phone stays the controller: [Ps5Pad] feeds its pad into session.setControllerState.
 */
class Ps5StreamActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        private const val TAG = "MoonCast-PS5"
        const val EXTRA_HOST = "ps5_host"
        const val EXTRA_REGIST_KEY = "ps5_regist_key"   // hex, 16 bytes
        const val EXTRA_MORNING = "ps5_morning"          // hex, 16 bytes
        const val EXTRA_PS5 = "ps5_is_ps5"
        private const val QUIT_RP_IN_USE = 4
        private val MENU_COMBO = setOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)   // CHIAKI_QUIT_REASON_SESSION_REQUEST_RP_IN_USE ("Remote is already in use")
        // Remote Play over the internet (PSN hole-punch): sent by the phone, used when the console isn't on this LAN
        const val EXTRA_PSN_TOKEN = "ps5_psn_token"
        const val EXTRA_DUID = "ps5_duid"                  // the console's PSN device id (hex)
        const val EXTRA_ACCOUNT_ID = "ps5_account_id"      // base64, 8 bytes
        fun hex(s: String): ByteArray =
            s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private var session: Session? = null
    private var started = false
    @Volatile private var closed = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sv = SurfaceView(this)
        // Status over the (black) video surface until the first frame: waking a console over the internet takes up to a minute
        status = android.widget.TextView(this).apply {
            setTextColor(0xFFE8ECFF.toInt()); textSize = 22f
            gravity = android.view.Gravity.CENTER
            text = "Connecting to your PlayStation…"
        }
        setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(status, android.widget.FrameLayout.LayoutParams(-1, -1))
        })
        sv.holder.addCallback(this)
    }

    private var status: android.widget.TextView? = null
    private fun showStatus(t: String?) = runOnUiThread {
        status?.let { if (t == null) it.visibility = android.view.View.GONE else { it.text = t; it.visibility = android.view.View.VISIBLE } }
    }

    private fun connectInfo(remote: Boolean): ConnectInfo {
        val host = intent.getStringExtra(EXTRA_HOST) ?: "192.168.100.126"
        val registKey = hex(intent.getStringExtra(EXTRA_REGIST_KEY) ?: "")
        val morning = hex(intent.getStringExtra(EXTRA_MORNING) ?: "")
        val ps5 = intent.getBooleanExtra(EXTRA_PS5, true)
        // Settings, PlayStation (TV home): 1080p or 720p at 60, H.265 or H.264. chiaki falls back if the console
        // or link can't hold it.
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        val res720 = prefs.getString("ps5_resolution", "1080") == "720"
        val h264 = !ps5 || prefs.getString("ps5_codec", "h265") == "h264"
        val preset = ConnectVideoProfile.preset(
            if (res720) VideoResolutionPreset.RES_720P else VideoResolutionPreset.RES_1080P, VideoFPSPreset.FPS_60,
            if (h264) Codec.CODEC_H264 else Codec.CODEC_H265
        )
        // Home network: Auto = 30 or 20 Mbps from this device's own link (chiaki's 1080p preset is 15), or the
        // number picked in Settings. Over the internet keep the preset - the PS5 backs off by itself.
        val homeKbps = prefs.getString("ps5_bitrate_home", "auto")?.toIntOrNull()?.times(1000) ?: lanBitrateKbps()
        val profile = if (remote) preset else preset.copy(bitrate = homeKbps)
        streamInfo = Triple(if (res720) "720p" else "1080p", if (h264) "H.264" else "H.265", profile.bitrate / 1000)
        streamRemote = remote
        if (!remote) return ConnectInfo(ps5, host, registKey, morning, profile)
        val account = intent.getStringExtra(EXTRA_ACCOUNT_ID)?.let {
            try { android.util.Base64.decode(it, android.util.Base64.DEFAULT) } catch (e: Exception) { null }
        }
        return ConnectInfo(ps5, host, registKey, morning, profile,
            psnToken = intent.getStringExtra(EXTRA_PSN_TOKEN), duid = intent.getStringExtra(EXTRA_DUID), psnAccountId = account)
    }

    // chiaki's libcurl has no CA store on Android, so PSN's HTTPS calls failed ("SSL peer certificate ... was not OK").
    // Export this device's trusted roots (AndroidCAStore) as a PEM bundle; the patched lib reads MC_CA_BUNDLE.
    private fun ensureCaBundle() {
        try {
            val f = java.io.File(filesDir, "cacert.pem")
            if (!f.exists() || f.length() == 0L || System.currentTimeMillis() - f.lastModified() > 7L * 24 * 3600 * 1000) {
                val ks = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
                val pem = StringBuilder()
                for (alias in ks.aliases()) {
                    val cert = ks.getCertificate(alias) ?: continue
                    pem.append("-----BEGIN CERTIFICATE-----\n")
                        .append(android.util.Base64.encodeToString(cert.encoded, android.util.Base64.DEFAULT))
                        .append("-----END CERTIFICATE-----\n")
                }
                f.writeText(pem.toString())
            }
            android.system.Os.setenv("MC_CA_BUNDLE", f.absolutePath, true)
            Log.i(TAG, "CA bundle for PSN: ${f.length()} bytes")
        } catch (e: Exception) { Log.w(TAG, "CA bundle: $e") }
    }

    // Home-network bitrate from this device's own link: 30 Mbps on Ethernet or fast 5/6 GHz Wi-Fi, 20 on 2.4 GHz /
    // slow / unknown links. Measured 2026-10-16: the home Mi stick (2.4 GHz, 87 Mbps link) got ~25 Mbps at a 30 Mbps
    // ask but lost 6-9 % of packets (a glitch every ~7 s), the PS5 not backing off enough.
    private fun lanBitrateKbps(): Int {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                Log.i(TAG, "LAN bitrate 30 Mbps (Ethernet)"); return 30000
            }
            @Suppress("DEPRECATION")
            val wi = (applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
            val fast = wi != null && wi.frequency >= 4900 && wi.linkSpeed >= 200
            Log.i(TAG, "LAN bitrate ${if (fast) 30 else 20} Mbps (Wi-Fi ${wi?.frequency} MHz, link ${wi?.linkSpeed} Mbps)")
            return if (fast) 30000 else 20000
        } catch (e: Exception) { Log.w(TAG, "link check: $e"); return 20000 }
    }

    private fun canGoRemote() = !intent.getStringExtra(EXTRA_PSN_TOKEN).isNullOrEmpty() && !intent.getStringExtra(EXTRA_DUID).isNullOrEmpty()

    // Is a PlayStation answering chiaki discovery at this address on our LAN? (Also true in standby: "620 Server Standby".)
    // A different device that happens to own the same IP on another network (e.g. JAL vs home) won't answer SRCH.
    private fun lanConsoleAnswers(host: String, ps5: Boolean): Boolean = try {
        // Ask up to 3 times: on a lossy 2.4 GHz link ONE lost SRCH/reply sent a home session over PSN (~40-80 s instead
        // of ~9 s) - seen on the home Mi stick 2026-10-16.
        java.net.DatagramSocket().use { s ->
            s.soTimeout = 600
            val msg = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:${if (ps5) "00030010" else "00020020"}\n".toByteArray()
            val dst = java.net.InetAddress.getByName(host)
            val buf = ByteArray(1024)
            var ok = false
            for (i in 0 until 3) {
                s.send(java.net.DatagramPacket(msg, msg.size, dst, if (ps5) 9302 else 987))
                try {
                    val p = java.net.DatagramPacket(buf, buf.size); s.receive(p)
                    if (String(buf, 0, p.length).startsWith("HTTP/1.1")) { ok = true; break }
                } catch (e: java.net.SocketTimeoutException) { }
            }
            ok
        }
    } catch (e: Exception) { false }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) { session?.setSurface(holder.surface); return }
        started = true
        val surface = holder.surface
        Thread({ connect(surface) }, "ps5-connect").start()
    }

    // Wake the console from standby (harmless if already on), then connect; retry a few times while it powers up.
    private fun connect(surface: android.view.Surface) {
        val host = intent.getStringExtra(EXTRA_HOST) ?: "192.168.100.126"
        val ps5 = intent.getBooleanExtra(EXTRA_PS5, true)
        // LAN if the console answers here; otherwise over the internet via PSN (no VPN / no port-forward) when the
        // phone sent a PSN token. PSN wakes the console itself when the session starts.
        val remote = !lanConsoleAnswers(host, ps5) && canGoRemote()
        Log.i(TAG, if (remote) "console not on this LAN -> Remote Play via PSN" else "console on LAN ($host)")
        if (remote) { ensureCaBundle(); startProgressWatch() }
        showStatus(if (remote) "Reaching PlayStation Network…" else "Connecting to your PlayStation…")
        if (!remote) try {
            val cred = wakeCredential(hex(intent.getStringExtra(EXTRA_REGIST_KEY) ?: ""))
            if (cred != null) {
                Log.i(TAG, "waking $host")
                DiscoveryService.wakeup(null, host, cred, ps5)
            }
        } catch (e: Exception) { Log.w(TAG, "wake: $e") }
        var attempt = 0
        val maxAttempts = if (remote) 4 else 8   // remote: a missed PSN start request is only fixed by a fresh attempt
        while (!closed && attempt < maxAttempts) {
            try {
                val s = Session(connectInfo(remote), null, false)
                s.eventCallback = { ev -> onEvent(ev) }
                s.setSurface(surface)
                val err = s.start()
                if (closed) { try { s.stop(); s.dispose() } catch (e: Exception) {}; return }  // torn down mid-connect
                if (err.isSuccess) {
                    session = s
                    Ps5Pad.start(s)   // phone drives the controller
                    if (!remote) return
                    // Remote: the data-channel hole-punch happens after start(), and the PS5 may give up on it.
                    // Only the first picture counts as success; a quit before that is a failed attempt -> retry.
                    val latch = java.util.concurrent.CountDownLatch(1)
                    attemptLatch = latch
                    attemptQuit = false
                    latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
                    attemptLatch = null
                    if (connected || closed) return
                    Log.w(TAG, "attempt ${attempt + 1}: PS5 dropped the session before the picture - retrying")
                    Ps5Pad.stop()
                    session = null
                    try { s.stop(); s.dispose() } catch (e: Exception) {}
                    attempt++
                    if (attempt < maxAttempts) {
                        progressStage = 0
                        showStatus("Your PS5 dropped the connection.\nRetrying (attempt ${attempt + 1} of $maxAttempts)…")
                        try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                    }
                    continue
                }
                Log.w(TAG, "start attempt ${attempt + 1} failed: $err")
                try { s.dispose() } catch (e: Exception) {}   // always release the native session so none linger on the PS5
            } catch (e: Exception) {
                Log.w(TAG, "connect attempt ${attempt + 1}: $e")
            }
            attempt++
            if (remote && attempt < maxAttempts && !closed) progressStage = 0
            if (remote && attempt < maxAttempts && !closed)
                showStatus("PlayStation Network didn't answer in time.\nRetrying (attempt ${attempt + 1} of $maxAttempts)…")
            try { Thread.sleep(3000) } catch (e: InterruptedException) { break }  // console still waking
        }
        if (session == null && !closed) {
            Log.e(TAG, "PS5 did not connect")
            stopProgressWatch()
            showStatus("Couldn't reach your PlayStation.\nCheck it's in rest mode (not off) and try again.")
            try { Thread.sleep(4000) } catch (e: InterruptedException) { }
            runOnUiThread { finish() }
        }
    }

    // Live status for a remote connect: the hole-punch runs in native code with no callback to us, but it logs every
    // milestone. An app may read its own logcat (no permission), so follow chiaki's lines and show where we are.
    @Volatile private var connected = false                                  // first picture arrived
    @Volatile private var attemptLatch: java.util.concurrent.CountDownLatch? = null  // remote attempt in flight
    @Volatile private var attemptQuit = false
    @Volatile private var progressProc: Process? = null
    @Volatile private var progressStage = 0

    private fun startProgressWatch() {
        stopProgressWatch()
        Thread({
            try {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-v", "brief", "-s", "Chiaki:V"))
                progressProc = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (closed || progressProc !== p) return@forEachLine
                    // stages only move forward within an attempt (create_offer is logged again for the data channel)
                    val (stage, st) = when {
                        "hole-punch failed" in line -> { progressStage = 0; return@forEachLine }   // retry restarts
                        "websocket_thread_func: connecting" in line -> 1 to "Reaching PlayStation Network…"
                        "create_offer" in line -> 2 to
                            "Asking your PS5 to start Remote Play…\n(it wakes from rest mode if needed — up to a minute)"
                        "customdata1" in line || ("Sent response to" in line && ":9303" in line) -> 3 to "Your PS5 answered — connecting…"
                        "control hole punched" in line -> 4 to "Connected through PlayStation Network — signing in…"
                        "Login message: success" in line -> 5 to "Signed in to your PS5 — opening the video channel…"
                        "Punched hole for data" in line -> 6 to "Starting video…"
                        else -> return@forEachLine
                    }
                    if (stage > progressStage) { progressStage = stage; showStatus(st) }
                }
            } catch (e: Exception) { Log.w(TAG, "progress watch: $e") }
        }, "ps5-progress").start()
    }

    private fun stopProgressWatch() {
        val p = progressProc; progressProc = null
        try { p?.destroy() } catch (e: Exception) { }
    }

    // chiaki's wake credential = the registration key's ASCII (up to the null pad) parsed as a hex ULong.
    private fun wakeCredential(registKey: ByteArray): ULong? {
        val end = registKey.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else registKey.size }
        val str = String(registKey, 0, end, Charsets.UTF_8)
        return try { str.toULong(16) } catch (e: NumberFormatException) { null }
    }

    private fun onEvent(ev: Event) {
        when (ev) {
            is ConnectedEvent -> {
                Log.i(TAG, "PS5 connected"); connected = true
                stopProgressWatch(); showStatus(null); attemptLatch?.countDown()
            }
            is QuitEvent -> {
                // The PS5 allows ONE Remote Play session: if another device (e.g. the Odin's own player) still has it,
                // say so instead of closing silently - retrying can't help until that one ends.
                if (!connected && ev.reason.value == QUIT_RP_IN_USE) {
                    Log.i(TAG, "PS5 is already streaming to another device")
                    closed = true
                    attemptLatch?.countDown()
                    stopProgressWatch()
                    showStatus("Your PS5 is already streaming to another device.\nClose it there, then try again.")
                    Thread({ try { Thread.sleep(6000) } catch (e: InterruptedException) { }; runOnUiThread { finish() } }, "ps5-inuse").start()
                    return
                }
                val latch = attemptLatch
                if (!connected && latch != null) {
                    // a remote attempt failed before the picture: let connect() retry it instead of closing the screen
                    Log.i(TAG, "PS5 quit before the picture: ${ev.reasonString}")
                    attemptQuit = true
                    latch.countDown()
                    return
                }
                // Don't dispose here: this runs on chiaki's own callback thread, and dispose()/join would deadlock.
                // Just close the screen; onDestroy tears the session down on a separate thread.
                Log.i(TAG, "PS5 quit: ${ev.reasonString}")
                closed = true
                runOnUiThread { finish() }
            }
            else -> {}
        }
    }

    // ---- the device's own controller (Odin handheld, or a pad plugged into the TV) ----
    // Until now the player only took input relayed from a phone. Map this device's gamepad straight to a DualSense
    // state; same combos as the relay (an Xbox-style pad has no PS / touchpad button): L2 + Start = PS,
    // Select + Start = touchpad. Sent through Ps5Pad's lock so it can never reach a session being disposed.
    private val held = HashSet<Int>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f; private var hatX = 0f; private var hatY = 0f

    private fun isPadKey(code: Int) = KeyEvent.isGamepadButton(code) || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val fromPad = (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (menu.isShowing) return menu.onKey(e)
        // the system Back (Odin's back key / a TV remote) opens the Quick menu; the pad's B button is Circle
        if (code == KeyEvent.KEYCODE_BACK && !fromPad) {
            if (e.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        if (isPadKey(code)) {
            if (e.repeatCount == 0) {
                if (e.action == KeyEvent.ACTION_DOWN) held.add(code) else if (e.action == KeyEvent.ACTION_UP) held.remove(code)
                // Select + Start + LB + RB opens the Quick menu once all four are let go; the game never sees it
                if (held.containsAll(MENU_COMBO)) { comboPending = true; Ps5Pad.pushLocal(ControllerState()) }
                if (comboPending) { if (held.isEmpty()) { comboPending = false; openMenu() }; return true }
                sendLocal()
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (menu.isShowing) return menu.onMotion(e)
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            fun dz(v: Float) = if (Math.abs(v) < 0.08f) 0f else v
            lx = dz(e.getAxisValue(MotionEvent.AXIS_X)); ly = dz(e.getAxisValue(MotionEvent.AXIS_Y))
            rx = dz(e.getAxisValue(MotionEvent.AXIS_Z)); ry = dz(e.getAxisValue(MotionEvent.AXIS_RZ))
            lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
            rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
            hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            sendLocal()
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    private fun sendLocal() {
        fun h(k: Int) = held.contains(k)
        val l2 = maxOf(lt, if (h(KeyEvent.KEYCODE_BUTTON_L2)) 1f else 0f)
        val r2 = maxOf(rt, if (h(KeyEvent.KEYCODE_BUTTON_R2)) 1f else 0f)
        val select = h(KeyEvent.KEYCODE_BUTTON_SELECT); val start = h(KeyEvent.KEYCODE_BUTTON_START)
        val psCombo = l2 > .5f && start
        val tpCombo = select && start && !psCombo
        var f = 0u
        if (h(KeyEvent.KEYCODE_BUTTON_A)) f = f or (if (swapCrossCircle) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS)
        if (h(KeyEvent.KEYCODE_BUTTON_B)) f = f or (if (swapCrossCircle) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON)
        if (h(KeyEvent.KEYCODE_BUTTON_X)) f = f or ControllerState.BUTTON_BOX
        if (h(KeyEvent.KEYCODE_BUTTON_Y)) f = f or ControllerState.BUTTON_PYRAMID
        if (h(KeyEvent.KEYCODE_BUTTON_L1)) f = f or ControllerState.BUTTON_L1
        if (h(KeyEvent.KEYCODE_BUTTON_R1)) f = f or ControllerState.BUTTON_R1
        if (h(KeyEvent.KEYCODE_BUTTON_THUMBL)) f = f or ControllerState.BUTTON_L3
        if (h(KeyEvent.KEYCODE_BUTTON_THUMBR)) f = f or ControllerState.BUTTON_R3
        if (select && !tpCombo) f = f or ControllerState.BUTTON_SHARE
        if (start && !psCombo && !tpCombo) f = f or ControllerState.BUTTON_OPTIONS
        if (h(KeyEvent.KEYCODE_BUTTON_MODE) || psCombo) f = f or ControllerState.BUTTON_PS
        if (tpCombo) f = f or ControllerState.BUTTON_TOUCHPAD
        if (h(KeyEvent.KEYCODE_DPAD_UP) || hatY < -.5f) f = f or ControllerState.BUTTON_DPAD_UP
        if (h(KeyEvent.KEYCODE_DPAD_DOWN) || hatY > .5f) f = f or ControllerState.BUTTON_DPAD_DOWN
        if (h(KeyEvent.KEYCODE_DPAD_LEFT) || hatX < -.5f) f = f or ControllerState.BUTTON_DPAD_LEFT
        if (h(KeyEvent.KEYCODE_DPAD_RIGHT) || hatX > .5f) f = f or ControllerState.BUTTON_DPAD_RIGHT
        fun ax(v: Float): Short = (Math.round(v * 32767f).coerceIn(-32767, 32767)).toShort()
        fun trig(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt().toUByte()
        Ps5Pad.pushLocal(ControllerState(
            buttons = f,
            l2State = trig(if (psCombo) 0f else l2), r2State = trig(r2),
            leftX = ax(lx), leftY = ax(ly), rightX = ax(rx), rightY = ax(ry)))
    }

    // ---- Quick menu ----
    private val menu by lazy { com.limelight.home.QuickMenu(this) }
    private var comboPending = false
    @Volatile private var streamInfo = Triple("1080p", "H.265", 0)
    @Volatile private var streamRemote = false
    private val startedAt = System.currentTimeMillis()
    private val swapCrossCircle by lazy {
        android.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean("ps5_swap_cross_circle", false)
    }

    private fun openMenu() {
        if (isFinishing) return
        menu.show("PlayStation 5",
            (if (streamRemote) "Over PlayStation Network. " else "On your home network. ") + com.limelight.home.QuickMenu.COMBO_LABEL + " opens this menu",
            {
                val min = (System.currentTimeMillis() - startedAt) / 60000
                listOf(streamInfo.first to "Picture", streamInfo.second to "Video",
                    (if (streamInfo.third > 0) "${streamInfo.third} Mbps" else "Auto") to "Quality", "$min min" to "Playing for")
            },
            listOf(
                com.limelight.home.QuickMenu.Item("◐", "PS button", "L2 + Start", false) { tap(ControllerState.BUTTON_PS) },
                com.limelight.home.QuickMenu.Item("▭", "Touchpad", "Select + Start", false) { tap(ControllerState.BUTTON_TOUCHPAD) },
                com.limelight.home.QuickMenu.Item("⏻", "Disconnect", "Your PS5 stays on", false) { finish() }))
    }

    /** Press and release one button, for the buttons a pad doesn't have. */
    private fun tap(button: UInt) {
        Ps5Pad.pushLocal(ControllerState(buttons = button))
        android.os.Handler(mainLooper).postDelayed({ Ps5Pad.pushLocal(ControllerState()) }, 150)
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // Leaving the player ends the PS5 session. Otherwise it keeps streaming in the background (Odin: switching back to
    // MoonCast) and the PS5 - one Remote Play session at a time - refuses the next device with "Remote is already in use".
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()   // full screen on a handheld (Odin): no status / nav bars over the game
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (!closed) session?.setSurface(null) }

    override fun onDestroy() {
        closed = true
        attemptLatch?.countDown()   // release a remote attempt that's waiting for its first picture
        stopProgressWatch()
        val s = session
        session = null
        // Tear down off the UI thread: Session.dispose() joins native threads and can block, which would freeze the
        // app's main looper and stop the receiver from launching the next stream.
        Thread({ Ps5Pad.stop(); if (s != null) try { s.stop(); s.dispose() } catch (e: Exception) {} }, "ps5-teardown").start()
        super.onDestroy()
    }
}
