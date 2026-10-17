package com.limelight.handheld

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.limelight.home.MoveToTv
import com.limelight.home.Ps5Store
import com.limelight.home.Psn
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Sends a game to a MoonCast TV: POST http://<tv>:8099/play. The TV starts it with its own player and pulls this
 * device's controller from our [PadServer] (padKey authorizes it).
 *   PC:   appId, appName (the TV plays it on the PC it is paired with)
 *   PS5:  ps5host, registKey, morning, ps5 (+ psnToken, duid, accountId when signed in to PSN, for a PS5 away from home)
 *   Xbox: xboxId, xboxName (the TV plays it with its own Xbox sign-in)
 * Always: deviceName (the TV shows "Sent from"), phoneIp (where our pad server is), padKey.
 */
object TvSender : MoveToTv.Handler {
    fun newPadKey(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun deviceName(ctx: Context): String =
        (if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) else null)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Blocking. Returns null when the TV accepted the game, else what went wrong (the TV's own words when it said). */
    fun send(ctx: Context, tv: TvFinder.Tv, t: MoveToTv.Target, padKey: String, direct: Boolean = false): String? {
        if (tv.ip.isEmpty()) return "${tv.name} isn't on this network. Open MoonCast on it, on the same Wi-Fi as this device."
        if (t.kind == "xb" && !tv.canXbox) return "Update MoonCast on ${tv.name} to send it an Xbox game."
        val j = JSONObject().put("deviceName", deviceName(ctx)).put("padKey", padKey)
        TvFinder.myIp()?.let { j.put("phoneIp", it) }
        when (t.kind) {
            // "pad":"direct": this device opens its own controller-only session on the PC (DirectPad), so the TV must not
            // relay the pad too. padKey still goes along: it authorizes /stop and /pad (the relay, if direct fails).
            "pc" -> { j.put("appId", t.appId.toString()).put("appName", t.title); if (direct) j.put("pad", "direct") }
            "ps" -> {
                val c = Ps5Store.all(ctx).firstOrNull { it.host == t.psHost }
                val regist = t.registKey ?: c?.registKey; val morning = t.morning ?: c?.morning
                if (t.psHost.isNullOrEmpty() || regist.isNullOrEmpty() || morning.isNullOrEmpty()) return "This PlayStation isn't registered here."
                j.put("ps5host", t.psHost).put("registKey", regist).put("morning", morning).put("ps5", if (t.isPs5) "true" else "false")
                // signed in to PSN: let the TV reach the console over the internet too (it still tries the LAN first)
                if (Psn.signedIn(ctx)) try {
                    Psn.remoteFor(ctx, c?.name ?: t.title)?.let { (tok, duid, acc) -> j.put("psnToken", tok).put("duid", duid).put("accountId", acc) }
                } catch (e: Exception) { }
            }
            "xb" -> j.put("xboxId", t.xboxId ?: "").put("xboxName", t.title)
        }
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://${tv.ip}:${TvFinder.PORT}/play").openConnection() as HttpURLConnection
            c.connectTimeout = 2000; c.readTimeout = 20000   // an Xbox may make the TV ask Xbox network first
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(j.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = c.responseCode
            if (code == 200) null
            else {
                val body = try { (c.errorStream ?: c.inputStream).bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
                val err = try { JSONObject(body).optString("error") } catch (e: Exception) { "" }
                when {
                    err.isBlank() -> "${tv.name} didn't take the game (error $code)."
                    err.startsWith("no app id") -> "${tv.name} has no paired PC. Pair it with your PC on the TV first."
                    err == "ps5 launch failed" -> "${tv.name} couldn't start the PlayStation."
                    else -> err.replaceFirstChar { it.uppercase() } + (if (err.endsWith(".")) "" else ".")
                }
            }
        } catch (e: Exception) {
            "${tv.name} didn't answer. Check MoonCast is installed on it and it's on the same Wi-Fi as this device."
        } finally { c?.disconnect() }
    }

    /**
     * Blocking. Ask [tv] to end the stream we started there and go back to its home (POST /stop, MoonCast TV version
     * 3 and later). [quit] also quits the game on the PC; otherwise it keeps running. False when the TV can't
     * (older MoonCast, or it didn't answer).
     */
    fun stop(tv: TvFinder.Tv, padKey: String, quit: Boolean = false): Boolean {
        if (tv.ip.isEmpty() || tv.version < 3) return false
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://${tv.ip}:${TvFinder.PORT}/stop").openConnection() as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 3000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("padKey", padKey).put("quit", quit).toString()
            c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            c.responseCode == 200
        } catch (e: Exception) { false } finally { c?.disconnect() }
    }

    /** Blocking. The direct controller session failed: ask [tv] to relay the pad after all (POST /pad, version 4+). */
    fun useRelay(tv: TvFinder.Tv, padKey: String): Boolean {
        if (tv.ip.isEmpty() || tv.version < 4) return false
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://${tv.ip}:${TvFinder.PORT}/pad").openConnection() as HttpURLConnection
            c.connectTimeout = 1500; c.readTimeout = 3000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("padKey", padKey)
            TvFinder.myIp()?.let { body.put("phoneIp", it) }
            c.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            c.responseCode == 200
        } catch (e: Exception) { false } finally { c?.disconnect() }
    }

    /** Open controller mode for [t] on [tv]; it sends the game after [delayMs] (time for a local stream to end). */
    fun open(act: Activity, tv: TvFinder.Tv, t: MoveToTv.Target, delayMs: Long = 0) {
        act.startActivity(t.put(Intent(act, TvControllerActivity::class.java))
                .putExtra(TvControllerActivity.EXTRA_TV, tv.id).putExtra(TvControllerActivity.EXTRA_DELAY, delayMs))
    }

    // ---- MoveToTv.Handler: the Quick menu's "Move to <TV>" ----

    override fun tvName(ctx: Context): String? = TvFinder.lastTv(ctx)?.name

    override fun move(act: Activity, t: MoveToTv.Target) {
        val tv = TvFinder.lastTv(act) ?: return
        Recents.touch(act, t.key)
        // the local stream ends as the player finishes; give it (and the PS5 or Xbox) a moment to let go first
        open(act, tv, t, when (t.kind) { "pc" -> 1500L; "ps" -> 3000L; else -> 2500L })
    }
}
