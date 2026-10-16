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
        fun hex(s: String): ByteArray =
            s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private var session: Session? = null
    private var started = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sv = SurfaceView(this)
        setContentView(sv)
        sv.holder.addCallback(this)
    }

    private fun connectInfo(): ConnectInfo {
        val host = intent.getStringExtra(EXTRA_HOST) ?: "192.168.100.126"
        val registKey = hex(intent.getStringExtra(EXTRA_REGIST_KEY) ?: "")
        val morning = hex(intent.getStringExtra(EXTRA_MORNING) ?: "")
        val ps5 = intent.getBooleanExtra(EXTRA_PS5, true)
        // 1080p60 HEVC; chiaki falls back if the console/link can't hold it
        val profile = ConnectVideoProfile.preset(
            VideoResolutionPreset.RES_1080P, VideoFPSPreset.FPS_60,
            if (ps5) Codec.CODEC_H265 else Codec.CODEC_H264
        )
        return ConnectInfo(ps5, host, registKey, morning, profile)
    }

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
        try {
            val cred = wakeCredential(hex(intent.getStringExtra(EXTRA_REGIST_KEY) ?: ""))
            if (cred != null) {
                Log.i(TAG, "waking $host")
                DiscoveryService.wakeup(null, host, cred, ps5)
            }
        } catch (e: Exception) { Log.w(TAG, "wake: $e") }
        var attempt = 0
        while (started && attempt < 8) {
            try {
                val s = Session(connectInfo(), null, false)
                s.eventCallback = { ev -> onEvent(ev) }
                s.setSurface(surface)
                val err = s.start()
                if (err.isSuccess) {
                    session = s
                    Ps5Pad.start(s)   // phone drives the controller
                    return
                }
                Log.w(TAG, "start attempt ${attempt + 1} failed: $err")
                try { s.dispose() } catch (e: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "connect attempt ${attempt + 1}: $e")
            }
            attempt++
            try { Thread.sleep(3000) } catch (e: InterruptedException) { break }  // console still waking
        }
        if (session == null) { Log.e(TAG, "PS5 did not connect"); runOnUiThread { finish() } }
    }

    // chiaki's wake credential = the registration key's ASCII (up to the null pad) parsed as a hex ULong.
    private fun wakeCredential(registKey: ByteArray): ULong? {
        val end = registKey.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else registKey.size }
        val str = String(registKey, 0, end, Charsets.UTF_8)
        return try { str.toULong(16) } catch (e: NumberFormatException) { null }
    }

    private fun onEvent(ev: Event) {
        when (ev) {
            is ConnectedEvent -> Log.i(TAG, "PS5 connected")
            is QuitEvent -> { Log.i(TAG, "PS5 quit: ${ev.reasonString}"); runOnUiThread { finish() } }
            else -> {}
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { session?.setSurface(null) }

    override fun onDestroy() {
        Ps5Pad.stop()
        session?.let { try { it.stop(); it.dispose() } catch (e: Exception) {} }
        session = null
        super.onDestroy()
    }
}
