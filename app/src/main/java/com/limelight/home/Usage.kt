package com.limelight.home

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import com.limelight.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Anonymous usage check-in, opt-in, so the project can count daily and monthly active users (TelemetryDeck).
 *
 * At most once a day, when MoonCast's home opens, and only if the user said yes: the app version, TV or handheld,
 * the Android version and device model, and whether PC, PS5 or Xbox was played since the last check-in. Never names,
 * addresses, games, accounts or sign-ins. The install is a random ID made on this device, sent hashed.
 *
 * Only official builds have an app ID (`-PmcTelemetryAppId` or MOONCAST_TELEMETRY_APP_ID at build time): forks and
 * self-built copies never ask and never send. Debug builds send as test mode, which TelemetryDeck keeps out of the
 * real numbers. A failed send is simply tried again on a later day; it never affects the app.
 */
object Usage {
    const val PREF = "mc_usage_checkin"        // the user's answer (true = send); absent = not asked yet
    private const val ID = "mc_usage_id"
    private const val LAST = "mc_usage_last"   // yyyy-MM-dd of the last check-in sent
    private const val USED = "mc_usage_used_"  // + pc / ps5 / xbox: played since the last check-in
    private const val URL_INGEST = "https://nom.telemetrydeck.com/v2/"

    /** This build can send at all (official builds only). */
    @JvmStatic val available get() = BuildConfig.TELEMETRY_APP_ID.isNotEmpty()

    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    /** Show the question: an official build that hasn't asked yet. */
    @JvmStatic fun shouldAsk(c: Context) = available && !prefs(c).contains(PREF)

    @JvmStatic fun answer(c: Context, yes: Boolean) {
        prefs(c).edit().putBoolean(PREF, yes).apply()
        if (yes) checkIn(c)
    }

    @JvmStatic fun enabled(c: Context) = available && prefs(c).getBoolean(PREF, false)

    /** A stream of this kind started ("pc", "ps5" or "xbox"); goes with the next check-in. */
    @JvmStatic fun used(c: Context, kind: String) {
        if (enabled(c)) prefs(c).edit().putBoolean(USED + kind, true).apply()
    }

    /** Sends today's check-in if the user said yes and none was sent today. Off the main thread; never throws. */
    @JvmStatic fun checkIn(c: Context) {
        if (!enabled(c)) return
        val p = prefs(c)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (p.getString(LAST, null) == today) return
        val app = c.applicationContext
        Thread({
            try {
                val id = p.getString(ID, null) ?: UUID.randomUUID().toString().also { p.edit().putString(ID, it).apply() }
                val payload = JSONObject()
                    .put("TelemetryDeck.AppInfo.version", BuildConfig.VERSION_NAME)
                    .put("TelemetryDeck.Device.platform", "Android")
                    .put("TelemetryDeck.Device.operatingSystem", "Android")
                    .put("TelemetryDeck.Device.systemVersion", Build.VERSION.RELEASE ?: "")
                    .put("TelemetryDeck.Device.modelName", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("MoonCast.app", if (BuildConfig.TV_APP) "tv" else "handheld")
                for (k in listOf("pc", "ps5", "xbox")) payload.put("MoonCast.used.$k", p.getBoolean(USED + k, false).toString())
                val signal = JSONObject()
                    .put("appID", BuildConfig.TELEMETRY_APP_ID)
                    .put("clientUser", sha256(id))
                    .put("sessionID", UUID.randomUUID().toString())
                    .put("type", "MoonCast.dailyCheckIn")
                    .put("isTestMode", BuildConfig.DEBUG)
                    .put("payload", payload)
                val conn = URL(URL_INGEST).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(JSONArray().put(signal).toString().toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    val e = p.edit().putString(LAST, today)
                    for (k in listOf("pc", "ps5", "xbox")) e.remove(USED + k)
                    e.apply()
                    Log.i("MoonCast", "usage check-in sent")
                } else Log.i("MoonCast", "usage check-in: HTTP $code")
            } catch (e: Exception) {
                Log.i("MoonCast", "usage check-in failed: $e")
            }
        }, "mc-usage").start()
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** The question, the same words on the handheld and the TV. */
    const val QUESTION = "Help improve MoonCast?"
    const val EXPLAIN = "Send an anonymous check-in once a day: the app version, the device model and Android version, " +
            "and whether you played PC, PS5 or Xbox. Never names, games, accounts or addresses. " +
            "You can change this in Settings, About."
}
