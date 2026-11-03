package com.limelight.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import org.json.JSONObject
import java.net.Inet4Address

/**
 * Automatic bitrate (Settings: on by default, per PC profile), for any host, network and device. Design:
 * docs/auto-bitrate-design.md (MoonCastDev/moonlight-android#139). The same behaviour as the iOS app.
 *
 * The client measures and decides, from what every GameStream host sends (RTP sequence numbers, FEC, frames):
 * once a second the network is *good*, *strained* (round-trip time rising, or some loss under 2%) or *congested* (over
 * 2% missing by RTP sequence, or frames FEC couldn't recover). Congested for 3 s (5 s within 2 min of the last change)
 * steps down to 80% of what arrived, net of FEC and headers, never below a floor for the resolution and frame rate;
 * 60 s of good below the bitrate set steps up 20%, never above it (the user's or the play style's bitrate is the
 * ceiling). A step-up that turns congested within 60 s goes back and doubles the wait (60 s, 2, 4 ... 15 min). A
 * decoder slower than the frame interval with no loss is the device, not the network: the bitrate stays, and a hint
 * is shown once.
 *
 * A change is applied by the best tier the host offers: A live (a probe in [PROBES] found a live bitrate call), C a
 * brief reconnect that resumes the game (any GameStream host; at most 2 a minute, step-ups 1 a minute, none while a
 * menu or the keyboard is open), D a suggestion with one button when the reconnect budget is used up. (B, a
 * host-managed adaptive bitrate bounded by the client, isn't offered by any host yet: Polaris's is a host-wide
 * persistent setting that ignores client loss.)
 *
 * What was learnt is kept per host + network (hashed Wi-Fi name and access point where Android shows them, else hashed
 * subnet and gateway; "mobile" and "ethernet" otherwise; away is its own) for 24 h, only on this device, and only as
 * the link limit: the bitrate setting itself is never changed.
 */
object AutoBitrate {
    const val PREF = "mc_auto_bitrate"
    const val SUBTITLE = "Steps down when the network drops packets, back up when it's clean."
    private const val STORE = "mc_auto_bitrate_links"
    private const val MODES = "mc_auto_bitrate_modes"
    private const val HINTS = "mc_auto_bitrate_hints"
    private const val LAST_HOST = "mc_auto_bitrate_last_host"
    private const val TAG = "MoonCast: auto bitrate: "

    const val SHARE = 0.8
    const val WIRE_OVERHEAD = 1.1
    const val LOSS = 0.02
    const val CONGESTED_SECONDS = 3
    const val CAUTIOUS_SECONDS = 5
    const val CAUTIOUS_MS = 120_000L
    const val GOOD_SECONDS = 60
    const val STEP_UP = 1.2
    const val UP_HOLD_MS = 60_000L
    const val UP_HOLD_MAX_MS = 15 * 60_000L
    const val UP_PROVE_MS = 60_000L
    const val MAX_AGE_MS = 24 * 3600_000L
    const val SETTLE_MS = 3000L
    const val DEVICE_SECONDS = 5
    /** Packets a second below this say too little to judge (a still picture). */
    const val MIN_PACKETS = 100
    /** Tier C: reconnects in any minute, and for step-ups. */
    const val RECONNECTS_PER_MIN = 2
    const val UP_RECONNECTS_PER_MIN = 1

    @JvmStatic
    fun enabled(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF, true)

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)

    // ------------------------------------------------------------------ this network

    private fun hash(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * This network's id: "wifi:<hash of SSID+BSSID>" where Android shows them (location permission), otherwise
     * "wifi:<hash of subnet+gateway>" ("vpn:" over a VPN); "ethernet" or "mobile"; null off any network. Never the
     * names themselves.
     */
    @JvmStatic
    fun network(ctx: Context): String? {
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(n)
            val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            if (caps != null && !vpn) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "mobile"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet"
            }
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                @Suppress("DEPRECATION")
                val info = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
                val ssid = info?.ssid?.trim('"')
                val bssid = info?.bssid
                if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>" && ssid != "0x" && bssid != null && bssid != "02:00:00:00:00:00")
                    return "wifi:" + hash("$ssid|$bssid")
            }
            val lp = cm.getLinkProperties(n) ?: return null
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
            val b = la.address.address
            val bits = la.prefixLength
            val v = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
            val net = if (bits == 0) 0 else v and (-1 shl (32 - bits))
            // the gateway's MAC isn't readable on Android 10+: its address instead
            val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: ""
            return (if (vpn) "vpn:" else "wifi:") + hash("$net/$bits|$gw")
        } catch (e: Exception) {
            return null
        }
    }

    private fun key(net: String, uuid: String?, away: Boolean) = "${uuid ?: "?"}|$net${if (away) "|away" else ""}"

    // ------------------------------------------------------------------ what's remembered (the link limit only)

    /** For one host on one network: [rate] the bitrate to stream at (kbps; 0: none, the ceiling), [wire] the link. */
    class Link(@JvmField val rate: Int, @JvmField val wire: Int, @JvmField val at: Long, @JvmField val upHoldMs: Long, @JvmField val upFails: Int)

    private fun all(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString(STORE, null) ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun recall(ctx: Context, k: String): Link? {
        val e = all(ctx).optJSONObject(k) ?: return null
        val at = e.optLong("at")
        if (System.currentTimeMillis() - at > MAX_AGE_MS) return null
        return Link(e.optInt("rate"), e.optInt("wire"), at, e.optLong("hold", UP_HOLD_MS), e.optInt("fails"))
    }

    private fun remember(ctx: Context, k: String, rate: Int, wire: Int, upHoldMs: Long, upFails: Int) {
        val a = all(ctx)
        if (a.length() > 64) {
            var oldest: String? = null; var t = Long.MAX_VALUE
            a.keys().forEach { n -> val at = a.optJSONObject(n)?.optLong("at") ?: 0; if (at < t) { t = at; oldest = n } }
            oldest?.let { a.remove(it) }
        }
        a.put(k, JSONObject().put("rate", rate).put("wire", wire).put("at", System.currentTimeMillis()).put("hold", upHoldMs).put("fails", upFails))
        prefs(ctx).edit().putString(STORE, a.toString()).apply()
    }

    /** The PC streamed last (Settings rows without a PC of their own show it). */
    @JvmStatic
    fun lastHost(ctx: Context): String? = prefs(ctx).getString(LAST_HOST, null)

    /** The limit learnt on this network for [uuid] (null: the PC streamed last); null when none. */
    @JvmStatic
    @JvmOverloads
    fun limitHere(ctx: Context, uuid: String? = null): Link? {
        val net = network(ctx) ?: return null
        val u = uuid ?: lastHost(ctx) ?: return null
        return (recall(ctx, key(net, u, false)) ?: recall(ctx, key(net, u, true)))?.takeIf { it.rate > 0 }
    }

    /** Settings, Forget: what was learnt on this network, for every PC. */
    @JvmStatic
    fun forgetHere(ctx: Context) {
        val net = network(ctx) ?: return
        val a = all(ctx)
        val gone = ArrayList<String>()
        a.keys().forEach { k -> if (k.substringAfter('|').removeSuffix("|away") == net) gone.add(k) }
        gone.forEach { a.remove(it) }
        prefs(ctx).edit().putString(STORE, a.toString()).apply()
        LimeLog.info(TAG + "forgot ${gone.size} limit(s) on this network")
    }

    // ------------------------------------------------------------------ how changes apply, per host

    enum class Mode(@JvmField val label: String) { LIVE("Live"), RECONNECT("Brief reconnects"), ADVISE("Suggestions only") }

    /** What [uuid]'s PC (null: the PC streamed last) was found to support when it last streamed; null before. */
    @JvmStatic
    fun mode(ctx: Context, uuid: String?): Mode? = try {
        val m = JSONObject(prefs(ctx).getString(MODES, null) ?: "{}").optString(uuid ?: lastHost(ctx) ?: "", "")
        Mode.values().firstOrNull { it.name == m }
    } catch (e: Exception) { null }

    private fun setMode(ctx: Context, uuid: String?, m: Mode) {
        if (uuid == null) return
        val j = try { JSONObject(prefs(ctx).getString(MODES, null) ?: "{}") } catch (e: Exception) { JSONObject() }
        prefs(ctx).edit().putString(MODES, j.put(uuid, m.name).toString()).apply()
    }

    /** The Settings row's line: the PC's mode and the limit learnt on this network. */
    @JvmStatic
    fun settingsLine(ctx: Context, uuid: String?): String {
        if (!enabled(ctx)) return "Off: the bitrate set is used as it is."
        val l = limitHere(ctx, uuid)
        return (mode(ctx, uuid)?.label ?: "Live or brief reconnects, found when you play") +
                (if (l != null) " · this network: limit ${l.rate / 1000} Mbps, learnt ${ago(l.at)}" else " · no limit on this network")
    }

    private fun ago(at: Long): String {
        val m = (System.currentTimeMillis() - at) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; else -> "${m / 60} h ago" }
    }

    /** A host's JSON API on its paired HTTPS port (the stream's client certificate). Throws on errors. */
    fun interface HostApi {
        /** [json] null: GET, else POST it. Returns the body. */
        @Throws(Exception::class)
        fun call(path: String, json: String?): String
    }

    /** A live bitrate call a probe found: [set] asks for a stream bitrate in kbps, true when the host took it. */
    class LiveControl(@JvmField val via: String, val set: (Int) -> Boolean)

    /** Probe -> capability -> how to call. A host is recognised by what answers, not by its name. */
    class Probe(@JvmField val name: String, val run: (HostApi) -> LiveControl?)

    /**
     * The probe table. Polaris (papi-ux/polaris, src/nvhttp.cpp at de8d152): GET /polaris/v1/session/status gives the
     * stream's scope (app_session_id, session_generation) and whether its encoder takes runtime changes
     * (adaptive_runtime_update_supported: NVENC re-IDRs, PyroWave applies next frame); POST
     * /polaris/v1/session/bitrate {bitrate_kbps (encoder kbps), app_session_id, session_generation} changes it,
     * for the session's sole owner only (409 active_owner_required otherwise). Another host's call is a new row.
     */
    @JvmField
    val PROBES: List<Probe> = listOf(Probe("polaris /polaris/v1/session/bitrate") { api ->
        val s = polarisSession(api.call("polaris/v1/session/status", null))
        if (s == null) null else LiveControl("Polaris session bitrate") { kbps ->
            val reply = api.call("polaris/v1/session/bitrate", polarisBitrateBody(s, kbps))
            LimeLog.info(TAG + "Polaris: ${reply.take(160)}")
            JSONObject(reply).optBoolean("status", false)
        }
    })

    /** Runs the probes in order (worker thread); the first that answers wins. */
    @JvmStatic
    fun probe(api: HostApi): LiveControl? {
        for (p in PROBES) {
            try {
                val c = p.run(api)
                if (c != null) { LimeLog.info(TAG + "host offers live bitrate (${p.name})"); return c }
                LimeLog.info(TAG + "probe ${p.name}: not for this stream")
            } catch (e: Exception) {
                LimeLog.info(TAG + "probe ${p.name}: no ($e)")
            }
        }
        return null
    }

    class PolarisSession(@JvmField val appSessionId: String, @JvmField val generation: Long, @JvmField val encoderShare: Double,
                         /** The host capped this stream's request: never ask its encoder for more than it gave (kbps), else 0. */
                         @JvmField val hostCapEncoderKbps: Int)

    @JvmStatic
    fun polarisSession(statusJson: String): PolarisSession? {
        val j = JSONObject(statusJson)
        val id = j.optString("app_session_id", "")
        val gen = j.optLong("session_generation", 0)
        val owned = j.optBoolean("owned_by_client", true)
        val runtime = j.optBoolean("adaptive_runtime_update_supported", false)
        val units = j.optJSONObject("bitrate_units")
        // split_kbps: what the handshake split (the request after the host's caps); else the request itself
        val split = units?.optDouble("split_kbps", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val asked = units?.optDouble("requested_kbps", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val req = if (split > 0) split else asked
        val enc = units?.optDouble("encoder_kbps", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        // the share of a request the encoder gets (FEC, audio and overhead taken off), as the handshake split it
        val share = if (req > 0 && enc > 0) (enc / req).coerceIn(0.3, 1.0) else 0.8
        LimeLog.info(TAG + "Polaris session: generation $gen, owned $owned, runtime bitrate $runtime, encoder ${enc.toInt()} of ${req.toInt()} kbps")
        if (id.isEmpty() || gen <= 0 || !owned || !runtime) return null
        return PolarisSession(id, gen, share, if (split > 0 && asked > split) enc.toInt() else 0)
    }

    /** The body of POST /polaris/v1/session/bitrate for a stream bitrate of [kbps] (encoder kbps, 1000-500000). */
    @JvmStatic
    fun polarisBitrateBody(s: PolarisSession, kbps: Int): String =
        JSONObject().put("bitrate_kbps", (kbps * s.encoderShare).toInt()
                .let { if (s.hostCapEncoderKbps > 0) minOf(it, s.hostCapEncoderKbps) else it }.coerceIn(1000, 500_000))
                .put("app_session_id", s.appSessionId).put("session_generation", s.generation).toString()

    // ------------------------------------------------------------------ rates

    /** The bitrate for a link that carries [wireKbps]: 80% of it, net of FEC and headers, whole Mbps. */
    @JvmStatic
    fun capKbps(wireKbps: Int): Int = (wireKbps * SHARE / WIRE_OVERHEAD).toInt() / 1000 * 1000

    private fun load(w: Int, h: Int, fps: Int) = w.toDouble() * h * fps / (1920.0 * 1080 * 60)

    /** Never below this (about 5 Mbps at 720p60, 10 at 1080p60, 20 at 1440p and 4K): under it, a lower resolution. */
    @JvmStatic
    fun floorKbps(w: Int, h: Int, fps: Int): Int = ((10_000 * load(w, h, fps)).toInt() / 1000 * 1000).coerceIn(3000, 20_000)

    /** PyroWave under this (60 Mbps at 1080p60, scaled) looks better as HEVC/AV1. */
    @JvmStatic
    fun pyroHevcKbps(w: Int, h: Int, fps: Int): Int = (60_000 * load(w, h, fps)).toInt()

    /** Runs [show] once per network for [what]. */
    private fun oncePerNetwork(ctx: Context, what: String, show: () -> Unit) {
        val net = network(ctx) ?: "?"
        val set = prefs(ctx).getStringSet(HINTS, emptySet()) ?: emptySet()
        if ("$what|$net" in set) return
        prefs(ctx).edit().putStringSet(HINTS, HashSet(set).apply { add("$what|$net") }).apply()
        show()
    }

    // ------------------------------------------------------------------ a stream starting

    class Plan(
        @JvmField val on: Boolean,
        @JvmField val uuid: String?,
        /** Where it's remembered (host, network, away), or null (no network: nothing is learnt). */
        @JvmField val key: String?,
        /** The bitrate set (the user's or play style's), kbps: the ceiling, never streamed above. */
        @JvmField val ceilingKbps: Int,
        @JvmField val startKbps: Int,
        @JvmField val floorKbps: Int,
        /** PyroWave may use 4:4:4 (false: capped below 2/3 of its recommended rate). */
        @JvmField val allow444: Boolean,
        @JvmField val note: String?,
        @JvmField val link: Link?,
    )

    /**
     * A PC stream to [uuid] is starting with [cfg] (after AwayStream.prepare): it starts at min(ceiling, the limit
     * learnt here). Only [cfg] (this stream) changes; the saved bitrate stays the ceiling. [pyro]: PyroWave asked for.
     */
    @JvmStatic
    fun prepare(ctx: Context, cfg: PreferenceConfiguration, uuid: String?, away: Boolean, pyro: Boolean): Plan {
        val ceiling = cfg.bitrate
        val floor = minOf(ceiling, floorKbps(cfg.width, cfg.height, cfg.fps))
        if (!enabled(ctx)) {
            LimeLog.info(TAG + "off: ${ceiling / 1000} Mbps as set")
            return Plan(false, uuid, null, ceiling, ceiling, floor, true, null, null)
        }
        if (uuid != null) prefs(ctx).edit().putString(LAST_HOST, uuid).apply()
        val net = network(ctx)
        val k = net?.let { key(it, uuid, away) }
        val link = k?.let { recall(ctx, it) }
        if (link == null || link.rate <= 0 || link.rate >= ceiling) {
            LimeLog.info(TAG + "on, ceiling ${ceiling / 1000} Mbps, floor ${floor / 1000}, network ${net ?: "none"}" +
                    (if (link != null && link.rate > 0) ", limit ${link.rate / 1000} Mbps (at or above the ceiling)" else ", no limit learnt"))
            return Plan(true, uuid, k, ceiling, ceiling, floor, true, null, link)
        }
        val start = maxOf(floor, link.rate)
        cfg.bitrate = start
        val rec = Pyrowave.recommendedKbps(cfg.width, cfg.height, cfg.fps)
        val allow444 = !pyro || start * 3 >= rec * 2
        LimeLog.info(TAG + "network $net carries ~${link.wire / 1000} Mbps: starting at ${start / 1000} Mbps (ceiling ${ceiling / 1000})" +
                (if (!allow444) ", PyroWave 4:2:0 (under 2/3 of ${rec / 1000} Mbps)" else ""))
        // a start that is Automatic bitrate's own reconnect already said so
        val own = lastChangeAt != 0L && SystemClock.elapsedRealtime() - lastChangeAt < 15_000
        return Plan(true, uuid, k, ceiling, start, floor, allow444, if (own) null else "Network limit here: ${start / 1000} Mbps", link)
    }

    // ------------------------------------------------------------------ a stream running

    /** What the controller needs from the stream (Game). Main thread unless said. */
    interface Stream {
        /** Runs [PROBES] against this stream's host (worker thread). */
        fun probeLive(): LiveControl?
        /** A menu or the keyboard is open: no reconnect now. */
        fun busy(): Boolean
        /** Starts the stream again at what was learnt (the game keeps running). */
        fun reconnect(note: String)
        fun toast(text: String)
        /** Tier D: [text] with one button [button] that does [action]. */
        fun suggest(text: String, button: String, action: Runnable)
        fun pyro(): Boolean
        fun pyro444(): Boolean
        /** Average decode time a frame lately, ms (0 unknown). */
        fun decodeMs(): Float
    }

    /** Process-wide (a reconnect makes a new Game): reconnect times, the last change, a step-up not yet proven. */
    private val reconnects = ArrayList<Long>()
    private val upReconnects = ArrayList<Long>()
    private var lastChangeAt = 0L
    private var lastUpAt = 0L
    private var lastUpFrom = 0

    private enum class Health { GOOD, STRAINED, CONGESTED }

    /** One stream's watcher. Game creates it when the stream starts and [stop]s it when it ends. Main thread only. */
    class Controller(private val ctx: Context, private val plan: Plan, private val stream: Stream, private val width: Int,
                     private val height: Int, private val fps: Int) {
        private val ui = Handler(Looper.getMainLooper())
        private var running = false
        /** What the stream runs at now, kbps. */
        @Volatile var currentKbps = plan.startKbps
            private set
        /** The limit learnt here (kbps), 0 when none: the stats show it. */
        @Volatile var limitKbps = plan.link?.rate?.takeIf { it in 1 until plan.ceilingKbps } ?: 0
            private set
        private var live: LiveControl? = null
        private var probed = false
        private var settleUntil = 0L
        private var upHold = plan.link?.upHoldMs ?: UP_HOLD_MS
        private var upFails = plan.link?.upFails ?: 0
        private val tot = LongArray(4)
        private var lp = -1L; private var lb = 0L; private var lm = 0L; private var lu = 0L; private var lt = 0L
        private var congested = 0
        private val samples = ArrayList<Int>()
        private var good = 0
        private var slowDecode = 0
        private var deviceHinted = false
        private var rttBase = Int.MAX_VALUE
        private var working = false
        private var lastLogAt = 0L
        private var lastSuggestAt = 0L
        private var startedAt = 0L

        fun start() {
            if (!plan.on || running) return
            running = true
            startedAt = SystemClock.elapsedRealtime()
            LimeLog.info(TAG + "watching at ${currentKbps / 1000} Mbps (ceiling ${plan.ceilingKbps / 1000}, floor ${plan.floorKbps / 1000})")
            Thread({
                val c = try { stream.probeLive() } catch (e: Exception) { LimeLog.warning(TAG + "probe: $e"); null }
                ui.post {
                    live = c; probed = true
                    setMode(ctx, plan.uuid, if (c != null) Mode.LIVE else Mode.RECONNECT)
                    LimeLog.info(TAG + if (c != null) "tier A: live (${c.via})" else "tier C: brief reconnects (no live bitrate call)")
                }
            }, "mc-autobitrate").start()
            ui.postDelayed(tick, 1000)
        }

        fun stop() {
            running = false
            ui.removeCallbacks(tick)
        }

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                try { step() } catch (e: Exception) { LimeLog.warning(TAG + e) }
                if (running) ui.postDelayed(this, 1000)
            }
        }

        private fun step() {
            MoonBridge.getVideoReceiveTotals(tot)
            val now = SystemClock.elapsedRealtime()
            val p = tot[0]; val b = tot[1]; val m = tot[2]; val u = tot[3]
            if (p == 0L) return
            if (settleUntil == 0L) settleUntil = now + SETTLE_MS   // the first 3 s after the first packet
            val first = lp < 0 || p < lp
            val dp = (p - lp).toDouble(); val dm = (if (m >= lm) m - lm else 0L).toDouble(); val du = if (u >= lu) u - lu else 0L
            val dt = (now - lt) / 1000.0
            val db = b - lb
            lp = p; lb = b; lm = m; lu = u; lt = now
            if (first || now < settleUntil || working || !probed || dt < 0.2) return
            if (dp + dm < MIN_PACKETS * dt) return   // a still picture: nothing to judge
            val wireKbps = (db * 8 / 1000 / dt).toInt()
            val loss = dm / (dp + dm)
            val rttInfo = MoonBridge.getEstimatedRttInfo()
            val rtt = if (rttInfo < 0) 0 else (rttInfo ushr 32).toInt()
            if (rtt in 1 until rttBase) rttBase = rtt
            val rttRising = rtt > 0 && rttBase < Int.MAX_VALUE && rtt > rttBase + maxOf(15, rttBase)

            // the device, not the network: decoding slower than the frames come, and nothing missing on the way
            val dec = stream.decodeMs()
            val deviceBound = dm == 0.0 && fps > 0 && dec > 1000f / fps
            slowDecode = if (deviceBound) slowDecode + 1 else 0
            if (slowDecode >= DEVICE_SECONDS && !deviceHinted) {
                deviceHinted = true
                LimeLog.info(TAG + String.format(java.util.Locale.US, "device-bound: decode %.1f ms > %.1f ms a frame, no loss: bitrate stays", dec, 1000f / fps))
                stream.toast("This device can't keep up at this setting. A lower frame rate or the Battery style will be smoother.")
            }

            val health = when {
                loss > LOSS || (du > 0 && !deviceBound) -> Health.CONGESTED
                loss > 0 || rttRising -> Health.STRAINED
                else -> Health.GOOD
            }
            if (health == Health.CONGESTED) { congested++; samples.add(wireKbps) } else { congested = 0; samples.clear() }
            // good while the stream uses its bitrate (a quiet picture proves nothing about the link)
            if (health != Health.GOOD) good = 0 else if (wireKbps * 10 >= currentKbps * 4) good++
            if (health != Health.GOOD && now - lastLogAt > 5000) {
                lastLogAt = now
                LimeLog.info(TAG + String.format(java.util.Locale.US, "%s: %.1f%% missing, %d frames lost, rtt %d ms (base %d), %d Mbps arrived at %d Mbps",
                        health.name.lowercase(), loss * 100, du, rtt, if (rttBase == Int.MAX_VALUE) 0 else rttBase, wireKbps / 1000, currentKbps / 1000))
            }
            val cautious = lastChangeAt != 0L && now - lastChangeAt < CAUTIOUS_MS
            if (congested >= (if (cautious) CAUTIOUS_SECONDS else CONGESTED_SECONDS)) {
                stepDown(samples.sum() / samples.size, loss, du, now)
                return
            }
            // a step-up that held for a minute is proven
            if (lastUpAt != 0L && now - lastUpAt >= UP_PROVE_MS) lastUpAt = 0L
            if (good >= GOOD_SECONDS && currentKbps < plan.ceilingKbps && now - maxOf(lastChangeAt, startedAt) >= upHold) stepUp()
        }

        private fun stepDown(carried: Int, loss: Double, du: Long, now: Long) {
            congested = 0; samples.clear(); good = 0
            var to = minOf(plan.ceilingKbps, capKbps(carried))
            // a step-up that turned congested within a minute: back to where it was (lower if the link says so), and
            // twice the wait before the next one
            if (lastUpAt != 0L && now - lastUpAt < UP_PROVE_MS) {
                to = minOf(lastUpFrom, maxOf(to, plan.floorKbps))
                upHold = minOf(upHold * 2, UP_HOLD_MAX_MS); upFails++
                lastUpAt = 0L
                LimeLog.info(TAG + "the step-up from ${lastUpFrom / 1000} Mbps turned congested: back, next step-up after ${upHold / 1000} s")
            }
            if (to < plan.floorKbps) {
                LimeLog.info(TAG + "the link carries less than the floor (${plan.floorKbps / 1000} Mbps at ${width}x$height $fps fps)")
                oncePerNetwork(ctx, "floor") { stream.toast("This network carries only ~${carried / 1000} Mbps: a lower resolution will look better.") }
                to = plan.floorKbps
            }
            LimeLog.info(TAG + String.format(java.util.Locale.US, "congested (%.0f%% missing, %d frames lost) at %d Mbps: %d Mbps arrived; limit here %d Mbps",
                    loss * 100, du, currentKbps / 1000, carried / 1000, to / 1000))
            plan.key?.let { remember(ctx, it, if (to >= plan.ceilingKbps) 0 else to, carried, upHold, upFails) }
            limitKbps = if (to < plan.ceilingKbps) to else 0
            // already at (or under) it: something else is losing packets
            if (currentKbps <= to * 1.05) return
            if (stream.pyro() && to < pyroHevcKbps(width, height, fps))
                oncePerNetwork(ctx, "pyro") { stream.toast("PyroWave needs more than this network carries: HEVC or AV1 will look better here.") }
            apply(to, "Network busy: ${to / 1000} Mbps", down = true)
        }

        private fun stepUp() {
            good = 0
            val to = minOf(plan.ceilingKbps, maxOf(currentKbps + 1000, (currentKbps * STEP_UP).toInt() / 1000 * 1000))
            LimeLog.info(TAG + "good for $GOOD_SECONDS s at ${currentKbps / 1000} Mbps: up to ${to / 1000} Mbps (ceiling ${plan.ceilingKbps / 1000})")
            val wire = (to * WIRE_OVERHEAD / SHARE).toInt()
            // a clean step-up raises the stored limit (back at the ceiling: no limit any more)
            plan.key?.let { remember(ctx, it, if (to >= plan.ceilingKbps) 0 else to, wire, upHold, upFails) }
            limitKbps = if (to < plan.ceilingKbps) to else 0
            apply(to, "Network clear: ${to / 1000} Mbps", down = false)
        }

        /** Best tier first: live (A), a brief reconnect within budget (C), else a suggestion (D, step-downs only). */
        private fun apply(kbps: Int, note: String, down: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val from = currentKbps
            // PyroWave going below 2/3 of its recommended rate in 4:4:4: only a new start brings 4:2:0
            val needs420 = down && stream.pyro444() && kbps * 3 < Pyrowave.recommendedKbps(width, height, fps) * 2
            val c = live
            if (c != null && !needs420) {
                working = true
                Thread({
                    val ok = try { c.set(kbps) } catch (e: Exception) { LimeLog.warning(TAG + "live change: $e"); false }
                    ui.post {
                        working = false
                        if (!running) return@post
                        if (ok) {
                            changed(from, kbps, now)
                            LimeLog.info(TAG + "now ${kbps / 1000} Mbps (live)")
                            if (down) stream.toast(note)
                        } else {
                            LimeLog.info(TAG + "live change refused: brief reconnects for the rest of this stream")
                            live = null
                            reconnectOrSuggest(from, kbps, note, down)
                        }
                    }
                }, "mc-autobitrate-set").start()
                return
            }
            reconnectOrSuggest(from, kbps, note + if (needs420) ", 4:2:0" else "", down)
        }

        private fun changed(from: Int, to: Int, now: Long) {
            currentKbps = to
            lastChangeAt = now
            settleUntil = now + SETTLE_MS
            if (to > from) { lastUpAt = now; lastUpFrom = from }
        }

        private fun reconnectOrSuggest(from: Int, kbps: Int, note: String, down: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (stream.busy()) {
                LimeLog.info(TAG + "a menu or the keyboard is open: asking again in a moment")
                if (down) congested = CONGESTED_SECONDS - 1 else good = GOOD_SECONDS - 1
                return
            }
            reconnects.removeAll { now - it > 60_000 }
            upReconnects.removeAll { now - it > 60_000 }
            val budget = reconnects.size < RECONNECTS_PER_MIN && (down || upReconnects.size < UP_RECONNECTS_PER_MIN)
            if (!budget) {
                if (!down) { LimeLog.info(TAG + "reconnect budget used: the step-up waits"); return }
                if (lastSuggestAt != 0L && now - lastSuggestAt < CAUTIOUS_MS) return
                lastSuggestAt = now
                LimeLog.info(TAG + "tier D: suggesting ${kbps / 1000} Mbps (reconnect budget used)")
                stream.suggest("The network is struggling. Lower the bitrate to ${kbps / 1000} Mbps?", "Lower to ${kbps / 1000} Mbps", Runnable {
                    if (running) { changed(from, kbps, SystemClock.elapsedRealtime()); reconnect(note) }
                })
                return
            }
            reconnects.add(now)
            if (!down) upReconnects.add(now)
            changed(from, kbps, now)
            reconnect(note)
        }

        private fun reconnect(note: String) {
            LimeLog.info(TAG + "tier C: reconnecting at ${currentKbps / 1000} Mbps")
            running = false
            ui.removeCallbacks(tick)
            stream.reconnect(note)
        }
    }

    /** The stats' bitrate: "N Mbps (auto · limit M)" while Automatic bitrate is on. */
    @JvmStatic
    fun label(kbps: Int, on: Boolean, limitKbps: Int): String =
        "${kbps / 1000} Mbps" + when { !on -> ""; limitKbps > 0 -> " (auto · limit ${limitKbps / 1000})"; else -> " (auto)" }
}
