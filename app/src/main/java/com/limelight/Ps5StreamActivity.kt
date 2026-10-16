package com.limelight

import android.app.Activity
import android.os.Bundle
import android.util.Log
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
        // 1080p60 HEVC; chiaki falls back if the console/link can't hold it
        val profile = ConnectVideoProfile.preset(
            VideoResolutionPreset.RES_1080P, VideoFPSPreset.FPS_60,
            if (ps5) Codec.CODEC_H265 else Codec.CODEC_H264
        )
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

    private fun canGoRemote() = !intent.getStringExtra(EXTRA_PSN_TOKEN).isNullOrEmpty() && !intent.getStringExtra(EXTRA_DUID).isNullOrEmpty()

    // Is a PlayStation answering chiaki discovery at this address on our LAN? (Also true in standby: "620 Server Standby".)
    // A different device that happens to own the same IP on another network (e.g. JAL vs home) won't answer SRCH.
    private fun lanConsoleAnswers(host: String, ps5: Boolean): Boolean = try {
        java.net.DatagramSocket().use { s ->
            s.soTimeout = 1500
            val msg = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:${if (ps5) "00030010" else "00020020"}\n".toByteArray()
            s.send(java.net.DatagramPacket(msg, msg.size, java.net.InetAddress.getByName(host), if (ps5) 9302 else 987))
            val buf = ByteArray(1024); val p = java.net.DatagramPacket(buf, buf.size)
            s.receive(p)
            String(buf, 0, p.length).startsWith("HTTP/1.1")
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
        val maxAttempts = if (remote) 3 else 8   // a hole-punch attempt already takes several seconds
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
                    return
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
            is ConnectedEvent -> { Log.i(TAG, "PS5 connected"); stopProgressWatch(); showStatus(null) }
            is QuitEvent -> {
                // Don't dispose here: this runs on chiaki's own callback thread, and dispose()/join would deadlock.
                // Just close the screen; onDestroy tears the session down on a separate thread.
                Log.i(TAG, "PS5 quit: ${ev.reasonString}")
                closed = true
                runOnUiThread { finish() }
            }
            else -> {}
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (!closed) session?.setSurface(null) }

    override fun onDestroy() {
        closed = true
        stopProgressWatch()
        val s = session
        session = null
        // Tear down off the UI thread: Session.dispose() joins native threads and can block, which would freeze the
        // app's main looper and stop the receiver from launching the next stream.
        Thread({ Ps5Pad.stop(); if (s != null) try { s.stop(); s.dispose() } catch (e: Exception) {} }, "ps5-teardown").start()
        super.onDestroy()
    }
}
