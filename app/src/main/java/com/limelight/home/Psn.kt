package com.limelight.home

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * PlayStation Network sign-in for this TV, the same way the phone app does it (chiaki's "Remote Play" OAuth client):
 * Sony's sign-in page -> code -> token -> account id. The account id registers a PS5 here; the token (renewed with
 * the refresh token) lets this TV reach the PS5 over the internet with no VPN or port forwarding.
 *
 * Kept in this app's private storage, like the phone app keeps its own.
 */
object Psn {
    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
    private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
    const val REDIRECT = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
    // Remote Play over the internet needs this broader scope, minted against a stable client duid.
    private const val SCOPE = "psn:clientapp%20referenceDataService:countryConfig.read" +
            "%20pushNotification:webSocket.desktop.connect%20sessionManager:remotePlaySession.system.update"
    private const val DUID_PREFIX = "0000000700410080"   // chiaki DUID_PREFIX
    private const val PREFS = "mc_psn"

    data class Device(val name: String, val duid: String, val remotePlay: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun signedIn(ctx: Context) = prefs(ctx).getString("account_id", null) != null
    fun accountId(ctx: Context): String? = prefs(ctx).getString("account_id", null)
    fun onlineId(ctx: Context): String? = prefs(ctx).getString("online_id", null)

    fun signOut(ctx: Context) = prefs(ctx).edit().clear().apply()

    /** This install's client device id, stable so a refreshed token stays valid. */
    private fun duid(ctx: Context): String {
        prefs(ctx).getString("duid", null)?.let { return it }
        val b = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val d = DUID_PREFIX + b.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("duid", d).apply()
        return d
    }

    fun authorizeUrl(ctx: Context) =
        "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize?service_entity=urn:service-entity:psn" +
        "&response_type=code&client_id=$CLIENT_ID&redirect_uri=$REDIRECT&scope=$SCOPE" +
        "&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay" +
        "&duid=${duid(ctx)}&prompt=always&PlatformPrivacyWs1=minimal&"

    /** The code from Sony's redirect, or null if this URL isn't it. Empty string = cancelled. */
    fun codeFrom(url: String?): String? {
        if (url == null || !url.startsWith(REDIRECT)) return null
        return Uri.parse(url).getQueryParameter("code") ?: ""
    }

    private val basic get() = "Basic " + Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)

    private fun post(form: String): JSONObject {
        val c = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Authorization", basic)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
    }

    private fun saveToken(ctx: Context, j: JSONObject) {
        prefs(ctx).edit().putString("token", j.getString("access_token"))
                .putString("refresh", j.optString("refresh_token", prefs(ctx).getString("refresh", "")))
                .putLong("token_at", System.currentTimeMillis()).apply()
    }

    /** Finish sign-in: code -> token -> account id (and the consoles reachable over PSN). Blocking; call off the UI thread. */
    fun finishSignIn(ctx: Context, code: String) {
        val tok = post("grant_type=authorization_code&code=${URLEncoder.encode(code, "UTF-8")}" +
                "&redirect_uri=${URLEncoder.encode(REDIRECT, "UTF-8")}")
        saveToken(ctx, tok)
        val token = tok.getString("access_token")
        val c = URL("$TOKEN_URL/${URLEncoder.encode(token, "UTF-8")}").openConnection() as HttpURLConnection
        val info = try {
            c.setRequestProperty("Authorization", basic)
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally { c.disconnect() }
        // user_id -> 8 little-endian bytes -> base64: chiaki's account id
        val uid = info.getString("user_id").toLong()
        val le = ByteArray(8) { i -> (uid shr (8 * i)).toByte() }
        prefs(ctx).edit().putString("account_id", Base64.encodeToString(le, Base64.NO_WRAP))
                .putString("online_id", info.optString("online_id", "")).apply()
        try { refreshDevices(ctx) } catch (e: Exception) { }
    }

    /** The token, renewed when it's near its ~1 h expiry. */
    fun freshToken(ctx: Context): String? {
        val p = prefs(ctx)
        val tok = p.getString("token", null) ?: return null
        val refresh = p.getString("refresh", "") ?: ""
        if (System.currentTimeMillis() - p.getLong("token_at", 0) < 50 * 60 * 1000L || refresh.isEmpty()) return tok
        return try {
            saveToken(ctx, post("grant_type=refresh_token&refresh_token=${URLEncoder.encode(refresh, "UTF-8")}" +
                    "&scope=$SCOPE&redirect_uri=${URLEncoder.encode(REDIRECT, "UTF-8")}"))
            p.getString("token", tok)
        } catch (e: Exception) { tok }
    }

    /** Ask PSN which consoles on this account can be reached over the internet. Blocking. */
    fun refreshDevices(ctx: Context): List<Device> {
        val tok = freshToken(ctx) ?: return emptyList()
        val c = URL("https://web.np.playstation.com/api/cloudAssistedNavigation/v2/users/me/clients" +
                "?platform=PS5&includeFields=device&limit=10&offset=0").openConnection() as HttpURLConnection
        val out = JSONArray()
        try {
            c.setRequestProperty("Authorization", "Bearer $tok")
            c.setRequestProperty("Accept-Language", "jp")
            c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode == 200) {
                val clients = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("clients") ?: JSONArray()
                for (i in 0 until clients.length()) {
                    val cl = clients.getJSONObject(i); val dev = cl.optJSONObject("device")
                    val f = dev?.optJSONArray("enabledFeatures")
                    val remote = f != null && (0 until f.length()).any { f.optString(it) == "remotePlay" }
                    out.put(JSONObject().put("name", dev?.optString("name") ?: "").put("duid", cl.optString("duid")).put("remotePlay", remote))
                }
            }
        } finally { c.disconnect() }
        prefs(ctx).edit().putString("devices", out.toString()).apply()
        return devices(ctx)
    }

    fun devices(ctx: Context): List<Device> = try {
        val a = JSONArray(prefs(ctx).getString("devices", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Device(it.optString("name"), it.optString("duid"), it.optBoolean("remotePlay")) }
    } catch (e: Exception) { emptyList() }

    /** (token, duid, accountId) to reach this console over PSN, or null. Blocking (may renew the token). */
    fun remoteFor(ctx: Context, consoleName: String): Triple<String, String, String>? {
        val acc = accountId(ctx) ?: return null
        var devs = devices(ctx)
        if (devs.isEmpty()) devs = try { refreshDevices(ctx) } catch (e: Exception) { emptyList() }
        val remote = devs.filter { it.remotePlay && it.duid.isNotEmpty() }
        val d = remote.firstOrNull { it.name.equals(consoleName, ignoreCase = true) } ?: remote.singleOrNull() ?: return null
        val tok = freshToken(ctx) ?: return null
        return Triple(tok, d.duid, acc)
    }
}
