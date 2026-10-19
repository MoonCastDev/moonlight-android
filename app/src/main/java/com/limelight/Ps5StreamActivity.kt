package com.limelight

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import com.limelight.home.Profile
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
import com.metallic.chiaki.lib.RumbleEvent
import com.metallic.chiaki.lib.LoginPinRequestEvent
import com.metallic.chiaki.lib.ControllerTouch
import com.metallic.chiaki.lib.Session
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset

/**
 * Streams a PS5 (or PS4) natively with chiaki (libchiaki), rendered straight to a SurfaceView via MediaCodec -
 * the native MoonCast path for a "PlayStation" host. Connect info comes from the phone (Add PC -> PlayStation):
 *   EXTRA_HOST, EXTRA_REGIST_KEY (hex), EXTRA_MORNING (hex), EXTRA_PS5.
 * The phone stays the controller: [Ps5Pad] feeds its pad into session.setControllerState.
 */
class Ps5StreamActivity : Activity(), SurfaceHolder.Callback, com.limelight.home.MoveToTv.LocalSession {
    companion object {
        private const val TAG = "MoonCast-PS5"
        const val EXTRA_HOST = "ps5_host"
        const val EXTRA_REGIST_KEY = "ps5_regist_key"   // hex, 16 bytes
        const val EXTRA_MORNING = "ps5_morning"          // hex, 16 bytes
        const val EXTRA_PS5 = "ps5_is_ps5"
        private const val QUIT_RP_IN_USE = 4
        private const val SURFACE_RELEASE_WAIT_MS = 500L   // longest the screen waits for the decoder to let go
        private const val IN_USE_WAIT_MS = 30_000L   // how long to wait for another device to let go of Remote Play
        private const val KEY_TOUCHPAD = KeyEvent.KEYCODE_BUTTON_16   // internal: an odd Sony pad's touchpad click
        private val STEP_NAMES = listOf("Found your PS5", "Waking it up", "Signing in", "Starting video")
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
        val dm = resources.displayMetrics
        fun dp(v: Int) = Math.round(v * dm.widthPixels / 1920f)
        steps = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            STEP_NAMES.forEach { name ->
                addView(android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(android.view.View(context).apply { setBackgroundColor(0xFF263150.toInt()) }, android.widget.LinearLayout.LayoutParams(-1, dp(5)))
                    addView(android.widget.TextView(context).apply {
                        text = name; setTextColor(0xFF97A1B8.toInt()); textSize = 15f; setPadding(0, dp(12), 0, 0)
                    })
                }, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
            }
        }
        setContentView(android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(status, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(steps, android.widget.FrameLayout.LayoutParams(dp(1100), -2, android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM).apply { bottomMargin = dp(220) })
        })
        sv.holder.addCallback(this)
    }

    private var status: android.widget.TextView? = null
    private fun showStatus(t: String?) = runOnUiThread {
        status?.let { if (t == null) it.visibility = android.view.View.GONE else { it.text = t; it.visibility = android.view.View.VISIBLE } }
        if (t == null) steps?.visibility = android.view.View.GONE
    }

    // The four steps of a connect, as in the mockup: done = cyan, now = amber, to do = grey.
    private var steps: android.widget.LinearLayout? = null
    private fun step(n: Int) = runOnUiThread {
        val row = steps ?: return@runOnUiThread
        row.visibility = android.view.View.VISIBLE
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i) as android.widget.LinearLayout
            val color = when { i < n - 1 -> 0xFF4FD6E3.toInt(); i == n - 1 -> 0xFFF2B55C.toInt(); else -> 0xFF263150.toInt() }
            c.getChildAt(0).setBackgroundColor(color)
            (c.getChildAt(1) as android.widget.TextView).setTextColor(if (i <= n - 1) 0xFFEEF1F7.toInt() else 0xFF97A1B8.toInt())
        }
    }

    private fun connectInfo(remote: Boolean): ConnectInfo {
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""   // every caller passes the console's host
        val registKey = hex(intent.getStringExtra(EXTRA_REGIST_KEY) ?: "")
        val morning = hex(intent.getStringExtra(EXTRA_MORNING) ?: "")
        val ps5 = intent.getBooleanExtra(EXTRA_PS5, true)
        // Settings, PlayStation (TV home): 1080p or 720p at 60, H.265 or H.264. chiaki falls back if the console
        // or link can't hold it.
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
        // A weak link can't carry 1080p: the picture queues on the Wi-Fi and every press shows up late (JAL TV: 2.4 GHz,
        // 26-39 Mbps link, 15 Mbps asked over PSN -> dropped packets and a laggy feel). Keep 60 fps and step down pixels.
        val link = linkMbps()
        val weak = link in 1..59
        // Home and Away profiles (Profile.kt). Home: Settings resolution (720p on a weak link). Away: "Quality away
        // from home", Auto = by the link, with its own bitrate and codec.
        val away = Profile.isAway(this)
        val awayQuality = prefs.getString("ps5_away_quality", "auto")
        val resPreset = when {
            away && awayQuality == "540" -> VideoResolutionPreset.RES_540P
            away && awayQuality == "720" -> VideoResolutionPreset.RES_720P
            away && awayQuality == "1080" -> VideoResolutionPreset.RES_1080P
            weak || (!away && prefs.getString("ps5_resolution", "1080") == "720") -> VideoResolutionPreset.RES_720P
            else -> VideoResolutionPreset.RES_1080P
        }
        val h264 = !ps5 || (if (away) prefs.getString("away_codec", "h265") else prefs.getString("ps5_codec", "h265")) == "h264"
        // Frame rate: the PS5 streams at 30 or 60 fps only; any other rate broke the picture (#39). A Custom value
        // saved by an older build uses the nearer of the two.
        val fpsSel = (if (away) prefs.getString("away_fps", "60") else prefs.getString("ps5_fps_home", "60")) ?: "60"
        val fps30 = if (fpsSel == "custom")
            (prefs.getString(if (away) "away_fps_custom" else "ps5_fps_home_custom", "60")?.toIntOrNull() ?: 60) < 45
        else fpsSel == "30"
        val preset = ConnectVideoProfile.preset(
            resPreset, if (fps30) VideoFPSPreset.FPS_30 else VideoFPSPreset.FPS_60,
            if (h264) Codec.CODEC_H264 else Codec.CODEC_H265
        )
        // Home network: Auto = 30 or 20 Mbps from this device's own link (chiaki's 1080p preset is 15), or the
        // number picked in Settings, or a Custom value (2-50 Mbps). Over the internet keep the preset - the PS5 backs off by itself.
        // ~30 % of the link is what a busy 2.4 GHz channel really delivers without queueing
        val linkCap = if (link > 0) (link * 300).coerceIn(5000, 30000) else 30000
        fun customKbps(key: String, def: Int) = (prefs.getString("${key}_custom", def.toString())?.toIntOrNull() ?: def).coerceIn(2, 50) * 1000
        val homeSel = prefs.getString("ps5_bitrate_home", "auto") ?: "auto"
        val homeKbps = when (homeSel) {
            "custom" -> customKbps("ps5_bitrate_home", 20)
            "auto" -> minOf(lanBitrateKbps(), linkCap)
            else -> homeSel.toIntOrNull()?.times(1000) ?: minOf(lanBitrateKbps(), linkCap)
        }
        val awaySel = prefs.getString("away_bitrate", "auto") ?: "auto"
        val awayKbps = when (awaySel) {
            "custom" -> customKbps("away_bitrate", 10)
            "auto" -> null
            else -> awaySel.toIntOrNull()?.times(1000)
        }
        val profile = if (away) preset.copy(bitrate = awayKbps ?: minOf(preset.bitrate, linkCap)) else preset.copy(bitrate = homeKbps)
        streamInfo = Triple(when (resPreset) { VideoResolutionPreset.RES_540P -> "540p"; VideoResolutionPreset.RES_720P -> "720p"; else -> "1080p" }, if (h264) "H.264" else "H.265", profile.bitrate / 1000)
        streamRemote = remote
        Log.i(TAG, "stream ${streamInfo.first}${profile.maxFPS} at ${profile.bitrate / 1000} Mbps (${if (remote) "PSN" else "home"}, ${if (away) "away" else "home"} profile, link ${if (link > 0) "$link Mbps" else "cable/unknown"})")
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
    /** Wi-Fi link speed in Mbps, or 0 for Ethernet / unknown (no cap). */
    private fun linkMbps(): Int = try {
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) 0
        else @Suppress("DEPRECATION") (applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .connectionInfo?.linkSpeed?.coerceAtLeast(0) ?: 0
    } catch (e: Exception) { 0 }

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
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""   // every caller passes the console's host
        val ps5 = intent.getBooleanExtra(EXTRA_PS5, true)
        // LAN if the console answers here; otherwise over the internet via PSN (no VPN / no port-forward) when the
        // phone sent a PSN token. PSN wakes the console itself when the session starts.
        val remote = !lanConsoleAnswers(host, ps5) && canGoRemote()
        Log.i(TAG, if (remote) "console not on this LAN -> Remote Play via PSN" else "console on LAN ($host)")
        if (remote) { ensureCaBundle(); startProgressWatch() }
        showStatus(if (remote) "Reaching PlayStation Network…" else "Connecting to your PlayStation…")
        step(if (remote) 1 else 2)
        if (!remote) wakeLan(host, ps5)
        var attempt = 0
        // remote: a missed PSN start request is only fixed by a fresh attempt. Home network: a console still waking
        // from rest mode refuses the session at once, so keep trying for about a minute.
        val maxAttempts = if (remote) 4 else 20
        while (!closed && attempt < maxAttempts) {
            try {
                val s = Session(connectInfo(remote), null, false)
                s.eventCallback = { ev -> onEvent(ev) }
                s.setSurface(surface)
                // Only the first picture counts as success; a quit before that is a failed attempt -> retry.
                // Remote: the data-channel hole-punch may fail. Home: the console may still be waking up.
                // Armed before start() (#38): a console still waking refuses within milliseconds, often before
                // start() returns, and a quit with no attempt armed closed the screen instead of retrying.
                val latch = java.util.concurrent.CountDownLatch(1)
                attemptQuit = false
                attemptLatch = latch
                val err = s.start()
                if (closed) { attemptLatch = null; try { s.stop(); s.dispose() } catch (e: Exception) {}; return }  // torn down mid-connect
                if (err.isSuccess) {
                    session = s
                    if (!remote) step(3)
                    Ps5Pad.start(s)   // phone drives the controller
                    latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
                    attemptLatch = null
                    if (connected || closed) return
                    Log.w(TAG, "attempt ${attempt + 1}: PS5 ended the session before the picture - retrying")
                    Ps5Pad.stop()
                    session = null
                    synchronized(disposed) { disposed.add(s) }
                    try { s.stop() } catch (e: Exception) {}
                    synchronized(sessionLock) { try { s.dispose() } catch (e: Exception) {} }
                    if (inUse) {
                        // another device (e.g. a handheld that just moved the game here) is still letting go
                        inUse = false
                        if (inUseSince == 0L) inUseSince = System.currentTimeMillis()
                        if (System.currentTimeMillis() - inUseSince < IN_USE_WAIT_MS) {
                            showStatus("Waiting for the PS5 to finish with the other device…")
                            try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                            continue   // doesn't count as an attempt
                        }
                        Log.i(TAG, "PS5 still in use by another device after ${IN_USE_WAIT_MS / 1000} s")
                        closed = true
                        showStatus("Your PS5 is already streaming to another device.\nClose it there, then try again.")
                        com.limelight.home.StreamReport.failed("Your PS5 is busy with another device. Close it there, then try again.")
                        try { Thread.sleep(6000) } catch (e: InterruptedException) { }
                        runOnUiThread { finish() }
                        return
                    }
                    attempt++
                    if (attempt < maxAttempts) {
                        progressStage = 0
                        if (remote) showStatus("Your PS5 dropped the connection.\nRetrying (attempt ${attempt + 1} of $maxAttempts)…")
                        else {
                            showStatus("Waking your PlayStation…\nThis takes up to a minute from rest mode.")
                            if (attempt % 4 == 0) wakeLan(host, ps5)   // in case the first wake was missed
                        }
                        try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
                    }
                    continue
                }
                attemptLatch = null
                Log.w(TAG, "start attempt ${attempt + 1} failed: $err")
                try { s.dispose() } catch (e: Exception) {}   // always release the native session so none linger on the PS5
            } catch (e: Exception) {
                attemptLatch = null
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
            com.limelight.home.StreamReport.failed("Couldn't reach the PlayStation. Check it's in rest mode (not off).")
            try { Thread.sleep(4000) } catch (e: InterruptedException) { }
            runOnUiThread { finish() }
        }
    }

    // Live status for a remote connect: the hole-punch runs in native code with no callback to us, but it logs every
    // milestone. An app may read its own logcat (no permission), so follow chiaki's lines and show where we are.
    @Volatile private var connected = false                                  // first picture arrived
    @Volatile private var attemptLatch: java.util.concurrent.CountDownLatch? = null  // remote attempt in flight
    @Volatile private var attemptQuit = false
    @Volatile private var inUse = false          // the last attempt ended with "Remote is already in use"
    private var inUseSince = 0L
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
                    if (stage > progressStage) {
                        progressStage = stage; showStatus(st)
                        step(when (stage) { 1 -> 1; 2 -> 2; 3, 4, 5 -> 3; else -> 4 })
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "progress watch: $e") }
        }, "ps5-progress").start()
    }

    private fun stopProgressWatch() {
        val p = progressProc; progressProc = null
        try { p?.destroy() } catch (e: Exception) { }
    }

    private fun wakeLan(host: String, ps5: Boolean) {
        try {
            val cred = wakeCredential(hex(intent.getStringExtra(EXTRA_REGIST_KEY) ?: "")) ?: return
            Log.i(TAG, "waking $host")
            DiscoveryService.wakeup(null, host, cred, ps5)
        } catch (e: Exception) { Log.w(TAG, "wake: $e") }
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
                com.limelight.home.StreamReport.playing()
                stopProgressWatch(); showStatus(null); attemptLatch?.countDown()
            }
            is QuitEvent -> {
                // The PS5 allows ONE Remote Play session: if another device (e.g. the Odin's own player) still has it,
                // say so instead of closing silently - retrying can't help until that one ends.
                val inUseQuit = ev.reason.value == QUIT_RP_IN_USE || ev.reasonString?.contains("in use", ignoreCase = true) == true
                if (!connected && inUseQuit && attemptLatch != null) {
                    // Remote Play is still busy with another device: retry every 3 s for up to 30 s (connect() loop)
                    Log.i(TAG, "PS5 is busy with another device: waiting for it")
                    inUse = true
                    attemptQuit = true
                    attemptLatch?.countDown()
                    return
                }
                if (!connected && inUseQuit) {
                    Log.i(TAG, "PS5 is already streaming to another device")
                    closed = true
                    attemptLatch?.countDown()
                    stopProgressWatch()
                    showStatus("Your PS5 is already streaming to another device.\nClose it there, then try again.")
                    com.limelight.home.StreamReport.failed("Your PS5 is busy with another device. Close it there, then try again.")
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
                if (!connected) {
                    // never got a picture: say why instead of dropping back to the home screen without a word
                    stopProgressWatch()
                    showStatus("Couldn't reach your PlayStation.\nCheck it's on or in rest mode (not fully off), then try again.")
                    com.limelight.home.StreamReport.failed("Couldn't reach the PlayStation. Check it's on or in rest mode (not fully off).")
                    Thread({ try { Thread.sleep(6000) } catch (e: InterruptedException) { }; runOnUiThread { if (!menu.isShowing) finish() } }, "ps5-noconnect").start()
                    return
                }
                runOnUiThread { finish() }
            }
            is RumbleEvent -> rumble(ev.left.toInt(), ev.right.toInt())
            is LoginPinRequestEvent -> {
                // Settings, PlayStation, Console PIN: answer the PS5's login PIN for you (once; a wrong one isn't retried)
                val pin = getSharedPreferences("mc_tv", MODE_PRIVATE).getString("ps5_login_pin", "") ?: ""
                if (pin.length == 4 && !ev.pinIncorrect) { Log.i(TAG, "answering the console login PIN"); session?.setLoginPin(pin) }
                else showStatus(if (ev.pinIncorrect) "The console PIN in Settings is wrong.\nFix it in Settings, PlayStation, Console PIN."
                                else "Your PS5 asks for its login PIN.\nSet it once in Settings, PlayStation, Console PIN.")
            }
            else -> {}
        }
    }

    // The PS5's rumble goes to the pad you're holding (the last one that sent input). Each event renews a short
    // buzz at the stronger motor's level; 0 stops it, and it stops by itself if the stop never arrives.
    @Volatile private var padDeviceId = -1
    private fun rumble(left: Int, right: Int) {
        try {
            val dev = InputDevice.getDevice(padDeviceId) ?: return
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31) dev.vibratorManager.defaultVibrator
                      else @Suppress("DEPRECATION") dev.vibrator
            if (!vib.hasVibrator()) return
            val strength = maxOf(left, right)
            if (strength == 0) { vib.cancel(); return }
            if (android.os.Build.VERSION.SDK_INT >= 26)
                vib.vibrate(android.os.VibrationEffect.createOneShot(1000, strength.coerceIn(1, 255)))
            else @Suppress("DEPRECATION") vib.vibrate(1000)
        } catch (e: Exception) { Log.w(TAG, "rumble: $e") }
    }

    // ---- the device's own controller (Odin handheld, or a pad plugged into the TV) ----
    // Until now the player only took input relayed from a phone. Map this device's gamepad straight to a DualSense
    // state; same combos as the relay (an Xbox-style pad has no PS / touchpad button): L2 + Start = PS,
    // Select + Start = touchpad. Sent through Ps5Pad's lock so it can never reach a session being disposed.
    private val held = HashSet<Int>()
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f; private var hatX = 0f; private var hatY = 0f

    private fun isPadKey(code: Int) = KeyEvent.isGamepadButton(code) || code == KEY_TOUCHPAD || code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    // A DualSense / DualShock 4 on an old kernel (no hid-playstation driver, e.g. the JAL AQUOS on 4.9) comes through
    // Android's generic layout: every button is shifted (Square = BUTTON_A, Cross = BUTTON_B, Circle = BUTTON_C, ...)
    // and the analog L2/R2 are on RX/RY. Same detection as Moonlight's ControllerHandler: Sony + a BUTTON_C key.
    private val oddSony = HashMap<Int, Boolean>()
    private fun isOddSony(deviceId: Int): Boolean = oddSony.getOrPut(deviceId) {
        val d = InputDevice.getDevice(deviceId)
        d != null && d.vendorId == 0x054c && d.hasKeys(KeyEvent.KEYCODE_BUTTON_C)[0]
    }
    private fun oddSonyKey(scan: Int): Int? = when (scan) {
        304 -> KeyEvent.KEYCODE_BUTTON_X        // Square
        305 -> KeyEvent.KEYCODE_BUTTON_A        // Cross
        306 -> KeyEvent.KEYCODE_BUTTON_B        // Circle
        307 -> KeyEvent.KEYCODE_BUTTON_Y        // Triangle
        308 -> KeyEvent.KEYCODE_BUTTON_L1
        309 -> KeyEvent.KEYCODE_BUTTON_R1
        310 -> KeyEvent.KEYCODE_BUTTON_L2       // the click; the analog value comes from RX
        311 -> KeyEvent.KEYCODE_BUTTON_R2
        312 -> KeyEvent.KEYCODE_BUTTON_SELECT   // Create
        313 -> KeyEvent.KEYCODE_BUTTON_START    // Options
        314 -> KeyEvent.KEYCODE_BUTTON_THUMBL
        315 -> KeyEvent.KEYCODE_BUTTON_THUMBR
        316 -> KeyEvent.KEYCODE_BUTTON_MODE     // PS
        317 -> KEY_TOUCHPAD                     // the real touchpad click
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
        // the system Back (Odin's back key / a TV remote) opens the Quick menu; the pad's B button is Circle
        if (code == KeyEvent.KEYCODE_BACK && !fromPad) {
            if (e.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        if (fromPad && e.deviceId in mutedPads) return true
        if (dpadTouch && isPadKey(code) && held.contains(KeyEvent.KEYCODE_BUTTON_SELECT) && e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { swipe(-600, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { swipe(600, 0); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { swipe(0, -350); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { swipe(0, 350); return true }
            }
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

    // A DualSense / DualShock 4 on a newer kernel (hid-playstation: Pixel, Retroid, Odin) shows its touchpad as a
    // separate input device, a small mouse/touchpad: its click is a mouse button, not a gamepad key, so it never
    // reached the PS5. Only a Sony touchpad counts, so a real mouse or the screen can't press it.
    private val sonyTouchpad = HashMap<Int, Boolean>()
    private fun isSonyTouchpad(deviceId: Int): Boolean = sonyTouchpad.getOrPut(deviceId) {
        val d = InputDevice.getDevice(deviceId)
        d != null && d.vendorId == 0x054c &&
                (d.supportsSource(InputDevice.SOURCE_TOUCHPAD) || d.supportsSource(InputDevice.SOURCE_MOUSE))
    }
    /** The touchpad's click, from either way Android reports it. True when it was the Sony touchpad. */
    private fun touchpadClick(e: MotionEvent): Boolean {
        if (!isSonyTouchpad(e.deviceId)) return false
        val down = when (e.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> true
            MotionEvent.ACTION_BUTTON_RELEASE -> false
            MotionEvent.ACTION_DOWN -> e.buttonState != 0 || (e.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
            else -> return true   // finger moves on the pad: nothing yet, and don't let them move a pointer
        }
        if (down == held.contains(KEY_TOUCHPAD)) return true
        if (down) held.add(KEY_TOUCHPAD) else held.remove(KEY_TOUCHPAD)
        sendLocal()
        return true
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean = touchpadClick(e) || super.dispatchTouchEvent(e)

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (menu.isShowing) return menu.onMotion(e)
        if (e.deviceId in mutedPads) return true
        if (touchpadClick(e)) return true
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.action == MotionEvent.ACTION_MOVE) {
            padDeviceId = e.deviceId
            fun dz(v: Float) = if (Math.abs(v) < 0.08f) 0f else v
            lx = dz(e.getAxisValue(MotionEvent.AXIS_X)); ly = dz(e.getAxisValue(MotionEvent.AXIS_Y))
            rx = dz(e.getAxisValue(MotionEvent.AXIS_Z)); ry = dz(e.getAxisValue(MotionEvent.AXIS_RZ))
            if (isOddSony(e.deviceId)) {
                // generic layout: L2/R2 on RX/RY, scaled -1 (released) .. 1 (pressed)
                lt = (e.getAxisValue(MotionEvent.AXIS_RX) + 1f) / 2f
                rt = (e.getAxisValue(MotionEvent.AXIS_RY) + 1f) / 2f
            } else {
                lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
                rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
            }
            hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X); hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            sendLocal()
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // Settings, PlayStation, "D-pad as touchpad": hold Select and press a direction to swipe on the touchpad.
    private val dpadTouch by lazy {
        android.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean("ps5_dpad_touchpad", false)
    }
    @Volatile private var touch: ControllerTouch? = null
    private fun swipe(dx: Int, dy: Int) {
        val h = android.os.Handler(mainLooper)
        val cx = 960; val cy = 471          // the touchpad is 1920 x 942
        for (i in 0..5) h.postDelayed({
            touch = ControllerTouch((cx + dx * i / 5).toUShort(), (cy + dy * i / 5).toUShort(), 0); sendLocal()
        }, i * 25L)
        h.postDelayed({ touch = null; sendLocal() }, 170)
    }

    // Quick menu, Controllers: pads you mute are ignored (the PS5 takes one controller; everyone shares player 1).
    private val mutedPads = HashSet<Int>()

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
        if (tpCombo || h(KEY_TOUCHPAD)) f = f or ControllerState.BUTTON_TOUCHPAD
        if (h(KeyEvent.KEYCODE_DPAD_UP) || hatY < -.5f) f = f or ControllerState.BUTTON_DPAD_UP
        if (h(KeyEvent.KEYCODE_DPAD_DOWN) || hatY > .5f) f = f or ControllerState.BUTTON_DPAD_DOWN
        if (h(KeyEvent.KEYCODE_DPAD_LEFT) || hatX < -.5f) f = f or ControllerState.BUTTON_DPAD_LEFT
        if (h(KeyEvent.KEYCODE_DPAD_RIGHT) || hatX > .5f) f = f or ControllerState.BUTTON_DPAD_RIGHT
        fun ax(v: Float): Short = (Math.round(v * 32767f).coerceIn(-32767, 32767)).toShort()
        fun trig(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt().toUByte()
        val st = touch
        Ps5Pad.pushLocal(ControllerState(
            touches = if (st != null) arrayOf(st, ControllerTouch()) else arrayOf(ControllerTouch(), ControllerTouch()),
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

    // Frame counters at the previous Quick menu poll, to turn chiaki's running totals into a rate.
    private var lastShown = -1.0
    private var lastLost = 0.0
    private var lastAt = 0L
    private var lastLossPct = 0.0

    /** The Quick menu's four numbers, polled every second while it's open: frame rate, delay, bitrate, lost packets. */
    private fun liveStats(): List<Pair<String, String>> {
        val v = session?.stats()
        if (v == null || v.size < 6) {
            val min = (System.currentTimeMillis() - startedAt) / 60000
            return listOf(streamInfo.first to "Picture", streamInfo.second to "Video",
                (if (streamInfo.third > 0) "${streamInfo.third} Mbps" else "Auto") to "Quality", "$min min" to "Playing for")
        }
        val now = System.currentTimeMillis()
        val fps = if (lastShown >= 0 && now > lastAt) (v[4] - lastShown) * 1000.0 / (now - lastAt) else -1.0
        if (lastShown >= 0 && v[4] + v[5] > lastShown + lastLost)
            lastLossPct = 100.0 * (v[5] - lastLost) / (v[4] + v[5] - lastShown - lastLost)
        lastShown = v[4]; lastLost = v[5]; lastAt = now
        Log.d(TAG, "stats mbps=${v[0]} loss=${v[1]} rtt=${v[2]} rtt0=${v[3]} shown=${v[4]} lost=${v[5]}")
        val loss = maxOf(v[1] * 100.0, lastLossPct)
        val rtt = if (v[3] > 1.0) v[3] else 0.0   // chiaki uses 1 ms when it couldn't measure
        return listOf(
            (if (fps < 0) "…" else "%.0f".format(java.util.Locale.US, minOf(fps, 60.0))) to "Frames per second",
            (if (rtt > 0) "%.0f ms".format(java.util.Locale.US, rtt) else "…") to "Network delay",
            (if (v[0] > 0) "%.1f Mbps".format(java.util.Locale.US, v[0]) else "…") to "Picture quality",
            "%.1f%%".format(java.util.Locale.US, loss) to "Lost packets")
    }

    private fun openMenu() {
        lastShown = -1.0; lastLossPct = 0.0
        if (isFinishing) return
        // handheld only: hand the PS5 over to the last MoonCast TV
        val move = com.limelight.home.MoveToTv.item(this, com.limelight.home.MoveToTv.Target("ps", "PlayStation 5",
            psHost = intent.getStringExtra(EXTRA_HOST), registKey = intent.getStringExtra(EXTRA_REGIST_KEY),
            morning = intent.getStringExtra(EXTRA_MORNING), isPs5 = intent.getBooleanExtra(EXTRA_PS5, true)))
        menu.show("PlayStation 5",
            (if (streamRemote) "Over PlayStation Network. " else "On your home network. ") + com.limelight.home.QuickMenu.COMBO_LABEL + " opens this menu",
            { liveStats() },
            listOfNotNull(
                com.limelight.home.QuickMenu.Item("PS", "PS button", "L2 + Start", false) { tap(ControllerState.BUTTON_PS) },
                com.limelight.home.QuickMenu.Item("▢", "Touchpad", "Select + Start", false) { tap(ControllerState.BUTTON_TOUCHPAD) },
                com.limelight.home.QuickMenu.Item("✚", "Controllers", "${com.limelight.home.QuickMenu.pads().size} connected", false) { controllers() },
                move,
                com.limelight.home.QuickMenu.Item("←", "Disconnect", disconnectHint(), false) { leave() },
                com.limelight.home.QuickMenu.Item("Zz", "Put PS5 to rest", "Ends the game", true) { rest() }))
    }

    // What leaving does: Settings, PlayStation, "When I disconnect" (ask / rest / keep).
    private fun onDisconnect() = android.preference.PreferenceManager.getDefaultSharedPreferences(this).getString("ps5_on_disconnect", "ask")
    private fun disconnectHint() = when (onDisconnect()) { "rest" -> "Puts it in rest mode"; "keep" -> "Your PS5 stays on"; else -> "Asks what to do" }

    private fun leave() {
        when (onDisconnect()) {
            "rest" -> rest()
            "keep" -> finish()
            else -> menu.show("Leave your PS5?", "Rest mode saves power. Keeping it on gets you back in faster.", { emptyList() }, listOf(
                com.limelight.home.QuickMenu.Item("Zz", "Put it in rest mode", "Uses less power", false) { rest() },
                com.limelight.home.QuickMenu.Item("←", "Keep it on", "Back in faster", false) { finish() },
                com.limelight.home.QuickMenu.Item("↩", "Keep playing", null, false) { }))
        }
    }

    /** Rest mode (chiaki's goto-bed), then leave. Give the message a moment to go out before the session stops. */
    private fun rest() {
        val s = session
        if (s == null) { finish(); return }
        showStatus("Putting your PS5 in rest mode…")
        Thread({
            val r = try { s.gotoBed() } catch (e: Throwable) { Log.w(TAG, "goto bed: $e"); null }
            Log.i(TAG, "rest mode requested: $r")
            try { Thread.sleep(800) } catch (e: InterruptedException) { }
            runOnUiThread { finish() }
        }, "ps5-rest").start()
    }

    private fun controllers() {
        val pads = com.limelight.home.QuickMenu.pads()
        menu.show("Controllers", "The PS5 takes one controller, so every pad is player 1. Mute one to stop it interfering.",
            { emptyList() },
            pads.map { d ->
                val muted = d.id in mutedPads
                com.limelight.home.QuickMenu.Item(if (muted) "–" else "1", d.name,
                    (if (muted) "Muted" else "On") + (com.limelight.home.QuickMenu.battery(d)?.let { ", $it" } ?: ""), false) {
                    if (muted) mutedPads.remove(d.id) else { mutedPads.add(d.id); held.clear(); sendLocal() }
                    controllers()
                }.asToggle()
            } + listOf(com.limelight.home.QuickMenu.Item("+", "Pair a controller", "Bluetooth settings", false) {
                com.limelight.home.QuickMenu.pairController(this) }))
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
    // The surface is gone once this returns, so the decoder has to let go of it first (#37): a decoder still drawing
    // into a dead surface fails, and chiaki then crashed copying a frame into a missing buffer. The release runs on a
    // worker (it takes chiaki's decoder lock) and the wait is bounded, so a stuck session can't freeze the app.
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (closed) {
            if (session != null && teardownThread == null) teardown(null)   // the PS5 quit: tear down now, not in onDestroy
            try { teardownThread?.join(SURFACE_RELEASE_WAIT_MS) } catch (e: InterruptedException) { }
            return
        }
        val s = session ?: return
        val t = Thread({ synchronized(sessionLock) { if (!closed && synchronized(disposed) { s !in disposed }) try { s.setSurface(null) } catch (e: Exception) { } } },
            "ps5-surface")
        t.start()
        try { t.join(SURFACE_RELEASE_WAIT_MS) } catch (e: InterruptedException) { }
    }

    @Volatile private var teardownThread: Thread? = null

    private val sessionLock = Any()
    private val disposed = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Session, Boolean>())

    /**
     * End the session off the UI thread (Session.dispose() joins native threads and can block). stop() first, without
     * the lock, so a surface call stuck on the decoder lock gets released; dispose() under the lock, so no surface
     * call can run on a freed session. [done] runs on the main thread afterwards.
     */
    private fun teardown(done: Runnable?) {
        closed = true
        attemptLatch?.countDown()   // release a remote attempt that's waiting for its first picture
        stopProgressWatch()
        val s = session
        session = null
        val t = Thread({
            Ps5Pad.stop()
            if (s != null) {
                // release the decoder first, while the surface still exists (#37)
                synchronized(sessionLock) { try { s.setSurface(null) } catch (e: Exception) { } }
                synchronized(disposed) { disposed.add(s) }
                try { s.stop() } catch (e: Exception) { }
                synchronized(sessionLock) { try { s.dispose() } catch (e: Exception) { } }
            }
            if (done != null) android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }, "ps5-teardown")
        teardownThread = t
        t.start()
    }

    /** "Move to TV": the PS5 takes one Remote Play client, so this one must be gone before the TV connects. */
    override fun endLocalSession(done: Runnable) {
        showStatus("Handing your PS5 over to the TV…")
        if (closed && session == null) { done.run(); return }
        teardown(done)
    }

    override fun onDestroy() {
        if (!closed || session != null) teardown(null)
        super.onDestroy()
    }
}
