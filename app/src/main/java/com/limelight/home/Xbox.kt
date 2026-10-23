package com.limelight.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Xbox sign-in and console control for this TV, the way XStreaming (github.com/Geocld/XStreaming, AGPL-3.0) does it:
 * Microsoft's device-code sign-in (the TV shows a code, you enter it at microsoft.com/link on a phone) -> Xbox Live user
 * token -> XSTS tokens for the console list (xccs) and for Remote Play (gssv). The stream itself runs in nano-rs.
 *
 * Kept in this app's private storage ("mc_xbox"), like the PSN sign-in.
 */
object Xbox {
    private const val CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e"
    private const val SCOPE = "xboxlive.signin openid profile offline_access"
    private const val MS_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
    private const val PREFS = "mc_xbox"

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)
    data class Console(val id: String, val name: String, val type: String, val powerState: String)
    /** What a stream needs: the console, the region's Remote Play server, its token, and the token for /connect. */
    data class StreamAuth(val baseUri: String, val gsToken: String, val connectToken: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun signedIn(ctx: Context) = prefs(ctx).getString("refresh", null) != null
    fun gamertag(ctx: Context): String? = prefs(ctx).getString("gamertag", null)
    fun signOut(ctx: Context) = prefs(ctx).edit().clear().apply()

    // ---------------------------------------------------------------- HTTP

    private class HttpError(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}")

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10000; c.readTimeout = 15000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally { c.disconnect() }
    }

    private fun form(vararg kv: Pair<String, String>) = kv.joinToString("&") { "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
    private val formHeaders = mapOf("Content-Type" to "application/x-www-form-urlencoded")
    private val xblHeaders = mapOf("x-xbl-contract-version" to "1", "Content-Type" to "application/json",
            "Cache-Control" to "no-cache", "Origin" to "https://www.xbox.com", "Referer" to "https://www.xbox.com/")

    // ---------------------------------------------------------------- sign-in

    /** Step 1: ask Microsoft for a code to show on the TV. Blocking. */
    fun startSignIn(): DeviceCode {
        val j = JSONObject(request("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", "POST",
                form("client_id" to CLIENT_ID, "scope" to SCOPE), formHeaders))
        return DeviceCode(j.getString("device_code"), j.getString("user_code"),
                j.optString("verification_uri", "https://www.microsoft.com/link"), j.optInt("expires_in", 900), j.optInt("interval", 5))
    }

    /** Step 2: one poll. true = signed in, false = still waiting for the user. Throws if the code expired or was declined. */
    fun pollSignIn(ctx: Context, dc: DeviceCode): Boolean {
        return try {
            val j = JSONObject(request(MS_TOKEN, "POST", form("grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to CLIENT_ID, "device_code" to dc.deviceCode), formHeaders))
            saveUserToken(ctx, j)
            try { webAuth(ctx) } catch (e: Exception) { }   // fetches the gamertag
            true
        } catch (e: HttpError) {
            val err = try { JSONObject(e.body).optString("error") } catch (x: Exception) { "" }
            if (err == "authorization_pending" || err == "slow_down") false else throw e
        }
    }

    private fun saveUserToken(ctx: Context, j: JSONObject) {
        prefs(ctx).edit().putString("access", j.getString("access_token"))
                .putString("refresh", j.optString("refresh_token", prefs(ctx).getString("refresh", "")))
                .putLong("access_until", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000).apply()
    }

    /** The Microsoft account token, renewed a minute before it expires. */
    private fun userToken(ctx: Context): String {
        val p = prefs(ctx)
        if (p.getLong("access_until", 0) - System.currentTimeMillis() > 60_000) p.getString("access", null)?.let { return it }
        val refresh = p.getString("refresh", null) ?: throw IllegalStateException("Not signed in to Xbox")
        saveUserToken(ctx, JSONObject(request(MS_TOKEN, "POST", form("client_id" to CLIENT_ID, "grant_type" to "refresh_token",
                "refresh_token" to refresh, "scope" to SCOPE), formHeaders)))
        return p.getString("access", null)!!
    }

    /** Xbox Live user token (valid ~16 h); everything else is authorised from it. */
    private fun xblUser(ctx: Context): String {
        val p = prefs(ctx)
        if (p.getLong("xbl_until", 0) - System.currentTimeMillis() > 60_000) p.getString("xbl", null)?.let { return it }
        val j = JSONObject(request("https://user.auth.xboxlive.com/user/authenticate", "POST", JSONObject()
                .put("Properties", JSONObject().put("AuthMethod", "RPS").put("RpsTicket", "d=" + userToken(ctx))
                        .put("SiteName", "user.auth.xboxlive.com"))
                .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT").toString(), xblHeaders))
        p.edit().putString("xbl", j.getString("Token")).putLong("xbl_until", expiry(j)).apply()
        return j.getString("Token")
    }

    /** XSTS "NotAfter" (e.g. 2026-10-17T15:20:00.1234567Z) in ms; seconds precision is plenty. */
    private fun expiry(j: JSONObject): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(j.getString("NotAfter").take(19))!!.time
    } catch (e: Exception) { System.currentTimeMillis() + 3600_000L }

    private fun xsts(ctx: Context, relyingParty: String): JSONObject = JSONObject(request("https://xsts.auth.xboxlive.com/xsts/authorize", "POST",
            JSONObject().put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(xblUser(ctx))))
                    .put("RelyingParty", relyingParty).put("TokenType", "JWT").toString(), xblHeaders))

    /** "XBL3.0 x=<uhs>;<token>" for xccs (console list, power, text). Also remembers the gamertag. */
    private fun webAuth(ctx: Context): String {
        val p = prefs(ctx)
        if (p.getLong("web_until", 0) - System.currentTimeMillis() > 60_000) p.getString("web", null)?.let { return it }
        val j = xsts(ctx, "http://xboxlive.com")
        val xui = j.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0)
        val auth = "XBL3.0 x=${xui.getString("uhs")};${j.getString("Token")}"
        p.edit().putString("web", auth).putLong("web_until", expiry(j)).apply {
            xui.optString("gtg").takeIf { it.isNotBlank() }?.let { putString("gamertag", it) }
        }.apply()
        return auth
    }

    /** Remote Play ("xhome") server and token for this account's region, plus the console-transfer token for /connect. Blocking. */
    fun streamAuth(ctx: Context): StreamAuth {
        val gssv = xsts(ctx, "http://gssv.xboxlive.com/").getString("Token")
        val j = JSONObject(request("https://xhome.gssv-play-prod.xboxlive.com/v2/login/user", "POST",
                JSONObject().put("token", gssv).put("offeringId", "xhome").toString(),
                mapOf("Content-Type" to "application/json", "Cache-Control" to "no-store, must-revalidate, no-cache",
                        "x-gssv-client" to "XboxComBrowser")))
        val regions = j.getJSONObject("offeringSettings").getJSONArray("regions")
        val all = (0 until regions.length()).map { regions.getJSONObject(it) }
        val region = all.firstOrNull { it.optBoolean("isDefault") } ?: all.first()
        // /connect wants a Passport "console transfer" token, minted from the same refresh token at login.live.com
        val lpt = JSONObject(request("https://login.live.com/oauth20_token.srf", "POST", form("client_id" to CLIENT_ID,
                "scope" to "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN",
                "grant_type" to "refresh_token", "refresh_token" to (prefs(ctx).getString("refresh", null) ?: "")), formHeaders))
        return StreamAuth(region.getString("baseUri"), j.getString("gsToken"), lpt.getString("access_token"))
    }

    // ---------------------------------------------------------------- consoles

    private fun xccsHeaders(ctx: Context, contract: String) = mapOf("Authorization" to webAuth(ctx), "Accept-Language" to "en-US",
            "x-xbl-contract-version" to contract, "x-xbl-client-name" to "XboxApp", "x-xbl-client-type" to "UWA",
            "x-xbl-client-version" to "39.39.22001.0", "skillplatform" to "RemoteManagement", "Content-Type" to "application/json")

    /** Consoles on this account, cached in prefs for the shelf. Blocking. */
    fun refreshConsoles(ctx: Context): List<Console> {
        val j = JSONObject(request("https://xccs.xboxlive.com/lists/devices?queryCurrentDevice=false&includeStorageDevices=true",
                "GET", null, xccsHeaders(ctx, "2")))
        val r = j.optJSONArray("result") ?: JSONArray()
        prefs(ctx).edit().putString("consoles", r.toString()).apply()
        return consoles(ctx)
    }

    fun consoles(ctx: Context): List<Console> = try {
        val a = JSONArray(prefs(ctx).getString("consoles", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Console(it.getString("id"), it.optString("name", "Xbox"), it.optString("consoleType", "Xbox"), it.optString("powerState", ""))
        }
    } catch (e: Exception) { emptyList() }

    private fun command(ctx: Context, consoleId: String, type: String, command: String, params: JSONArray = JSONArray()) {
        request("https://xccs.xboxlive.com/commands", "POST", JSONObject().put("destination", "Xbox").put("type", type)
                .put("command", command).put("sessionId", UUID.randomUUID().toString()).put("sourceId", "com.microsoft.smartglass")
                .put("parameters", params).put("linkedXboxId", consoleId).toString(), xccsHeaders(ctx, "4"))
    }

    /** Wake from sleep (the console needs "Sleep" power mode and remote features on). Blocking. */
    fun wake(ctx: Context, consoleId: String) = command(ctx, consoleId, "Power", "WakeUp")
    fun turnOff(ctx: Context, consoleId: String) = command(ctx, consoleId, "Power", "TurnOff")
    /** Type into whatever text box is open on the console. Blocking. */
    fun sendText(ctx: Context, consoleId: String, text: String) =
            command(ctx, consoleId, "Shell", "InjectString", JSONArray().put(JSONObject().put("replacementString", text)))
}
