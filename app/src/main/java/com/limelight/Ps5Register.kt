package com.limelight

import android.util.Log
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEvent
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistHost
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Registers a PlayStation with chiaki (the "Link Device" PIN flow): given the console address, the user's PSN
 * account id (8 bytes) and the 8-digit PIN, it runs chiaki's registStart and returns the resulting remote-play
 * keys (rpRegistKey + rpKey) that the phone then stores as a PlayStation host. Runs on the TV app because that's
 * where libchiaki lives. Blocking: the HTTP handler waits here for the registration to finish.
 */
object Ps5Register {
    private const val TAG = "MoonCast-PS5Reg"

    class Result(val registKeyHex: String, val morningHex: String, val nickname: String, val macHex: String)

    fun register(host: String, accountId: ByteArray, pin: Int, ps5: Boolean, timeoutMs: Long): Result? {
        val latch = CountDownLatch(1)
        var host5: RegistHost? = null
        val info = RegistInfo(
            if (ps5) Target.PS5_1 else Target.PS4_10,
            host, false, null, accountId, pin
        )
        val log = ChiakiLog(0x7f) { _, text -> Log.i(TAG, text) }
        val regist = try {
            Regist(info, log) { ev: RegistEvent ->
                if (ev is RegistEventSuccess) host5 = ev.host
                latch.countDown()
            }
        } catch (e: Exception) { Log.e(TAG, "registStart failed", e); return null }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) {}
        try { regist.dispose() } catch (e: Exception) {}
        val h = host5 ?: return null
        return Result(hex(h.rpRegistKey), hex(h.rpKey), h.serverNickname, hex(h.serverMac))
    }

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x.toInt() and 0xff))
        return sb.toString()
    }
}
