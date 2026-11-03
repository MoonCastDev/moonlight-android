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
 * Automatic bitrate (Settings: on by default, per PC profile). A PC stream on a Wi-Fi link that carries less than the
 * bitrate loses the end of every frame burst (PyroWave at 194-438 Mbps lost 43-71% of its packets on home Wi-Fi), and
 * stock Sunshine/Polaris have no throughput test, so the stream itself is the measure, as on iOS (MCPyroWaveLink):
 *
 *  - after the stream's first 3 s, packets missing by RTP sequence above 2% for 3 s in a row (5% for 5 s within 2 min
 *    of a step-down) mean the link is full: what arrived then is what it carries. The stream steps down to 80% of it,
 *    net of FEC and headers (/1.1).
 *  - a stream below the bitrate set (the user's, or the play style's) that runs with under 0.5% missing for 60 s, while
 *    actually using its bitrate, steps up by ~18% (never above the set one), at most once a minute. A step-up that
 *    loses again within a minute doubles the wait before the next one (up to 16 min): no oscillation.
 *  - what was learnt is remembered for this PC on this network (Wi-Fi name, else subnet) for 24 h: the next start
 *    begins there. PyroWave below 2/3 of its recommended rate starts in 4:2:0 instead of 4:4:4.
 *
 * The change is live on Polaris (POST /polaris/v1/session/bitrate, when the session says its encoder can take it:
 * NVENC re-IDRs, PyroWave applies next frame), otherwise the stream reconnects at the new rate (the game keeps
 * running; the PC resumes it). Off: the bitrate set is used as it is.
 */
object AutoBitrate {
    const val PREF = "mc_auto_bitrate"
    const val SUBTITLE = "Steps down when Wi-Fi drops packets, back up when it's clean."
    private const val STORE = "mc_auto_bitrate_links"
    private const val TAG = "MoonCast: auto bitrate: "

    const val SHARE = 0.8
    const val WIRE_OVERHEAD = 1.1
    const val LOSS = 0.02
    const val LOSS_SECONDS = 3
    const val REPEAT_LOSS = 0.05
    const val REPEAT_SECONDS = 5
    const val CAUTIOUS_MS = 120_000L
    const val CLEAN = 0.005
    const val CLEAN_SECONDS = 60
    const val STEP_UP = 1.18
    const val UP_HOLD_MS = 60_000L
    const val UP_HOLD_MAX_MS = 16 * 60_000L
    const val MAX_AGE_MS = 24 * 3600_000L
    const val SETTLE_MS = 3000L
    const val MIN_KBPS = 5000
    /** Packets a second below this say too little to judge (a still picture). */
    const val MIN_PACKETS = 100

    @JvmStatic
    fun enabled(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF, true)

    // ------------------------------------------------------------------ this network

    /** "wifi:<name>" when the Wi-Fi's name can be read, else "net:<subnet>"; null off any network. */
    @JvmStatic
    fun network(ctx: Context): String? {
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(n)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                @Suppress("DEPRECATION")
                val ssid = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
                        ?.trim('"')
                if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>" && ssid != "0x") return "wifi:$ssid"
            }
            val lp = cm.getLinkProperties(n) ?: return null
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
            val b = la.address.address
            val bits = la.prefixLength
            val v = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
            val net = if (bits == 0) 0 else v and (-1 shl (32 - bits))
            return "net:${(net ushr 24) and 255}.${(net ushr 16) and 255}.${(net ushr 8) and 255}.${net and 255}/$bits"
        } catch (e: Exception) {
            return null
        }
    }

    private fun key(ctx: Context, uuid: String?, away: Boolean): String? {
        val net = network(ctx) ?: return null
        return "${uuid ?: "?"}|$net${if (away) "|away" else ""}"
    }

    // ------------------------------------------------------------------ what's remembered

    /** What was learnt for one PC on one network. [rate]: the bitrate to stream at, kbps; [wire]: the link, kbps. */
    class Link(@JvmField val rate: Int, @JvmField val wire: Int, @JvmField val at: Long, @JvmField val upHoldMs: Long)

    private fun all(ctx: Context): JSONObject = try {
        JSONObject(PreferenceManager.getDefaultSharedPreferences(ctx).getString(STORE, null) ?: "{}")
    } catch (e: Exception) { JSONObject() }

    @JvmStatic
    fun recall(ctx: Context, k: String): Link? {
        val e = all(ctx).optJSONObject(k) ?: return null
        val at = e.optLong("at")
        if (System.currentTimeMillis() - at > MAX_AGE_MS) {
            LimeLog.info(TAG + "what was learnt for $k (${e.optInt("rate") / 1000} Mbps) is over a day old: unknown again")
            return null
        }
        return Link(e.optInt("rate"), e.optInt("wire"), at, e.optLong("hold", UP_HOLD_MS))
    }

    private fun remember(ctx: Context, k: String, rate: Int, wire: Int, upHoldMs: Long) {
        val a = all(ctx)
        // the oldest go once there are many
        if (a.length() > 64) {
            var oldest: String? = null; var t = Long.MAX_VALUE
            a.keys().forEach { n -> val at = a.optJSONObject(n)?.optLong("at") ?: 0; if (at < t) { t = at; oldest = n } }
            oldest?.let { a.remove(it) }
        }
        a.put(k, JSONObject().put("rate", rate).put("wire", wire).put("at", System.currentTimeMillis()).put("hold", upHoldMs))
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(STORE, a.toString()).apply()
    }

    /** The bitrate for a link that carries [wireKbps]: 80% of it, net of FEC and headers, whole Mbps. */
    @JvmStatic
    fun capKbps(wireKbps: Int): Int = maxOf(MIN_KBPS, (wireKbps * SHARE / WIRE_OVERHEAD).toInt() / 1000 * 1000)

    // ------------------------------------------------------------------ a stream starting

    class Plan(
        /** Automatic bitrate is on for this stream. */
        @JvmField val on: Boolean,
        /** Where it is remembered (PC, network, away), or null (no network: nothing is learnt). */
        @JvmField val key: String?,
        /** The bitrate set (user's or play style's), kbps: never streamed above. */
        @JvmField val ceilingKbps: Int,
        /** What the stream starts at, kbps. */
        @JvmField val startKbps: Int,
        /** PyroWave may use 4:4:4 (false: capped below 2/3 of its recommended rate). */
        @JvmField val allow444: Boolean,
        /** The toast at stream start, or null. */
        @JvmField val note: String?,
        @JvmField val upHoldMs: Long,
    )

    /**
     * A PC stream to [uuid] is starting with [cfg] (after AwayStream.prepare): its bitrate within what this network
     * carries, as learnt. [pyro]: PyroWave is asked for (4:2:0 when capped too far below its recommended rate).
     */
    @JvmStatic
    fun prepare(ctx: Context, cfg: PreferenceConfiguration, uuid: String?, away: Boolean, pyro: Boolean): Plan {
        val ceiling = cfg.bitrate
        if (!enabled(ctx)) {
            LimeLog.info(TAG + "off: ${ceiling / 1000} Mbps as set")
            return Plan(false, null, ceiling, ceiling, true, null, UP_HOLD_MS)
        }
        val k = key(ctx, uuid, away)
        val link = k?.let { recall(ctx, it) }
        if (link == null || link.rate <= 0 || link.rate >= ceiling) {
            LimeLog.info(TAG + "on, ${ceiling / 1000} Mbps" + (if (k == null) " (no network to remember)" else if (link == null) " (nothing learnt for $k)"
                    else " (learnt ${link.rate / 1000} Mbps for $k, at or above the bitrate set)"))
            return Plan(true, k, ceiling, ceiling, true, null, link?.upHoldMs ?: UP_HOLD_MS)
        }
        cfg.bitrate = link.rate
        val rec = Pyrowave.recommendedKbps(cfg.width, cfg.height, cfg.fps)
        val allow444 = !pyro || link.rate * 3 >= rec * 2
        LimeLog.info(TAG + "$k carries ~${link.wire / 1000} Mbps: ${link.rate / 1000} Mbps of ${ceiling / 1000} set" +
                (if (!allow444) ", PyroWave 4:2:0 (under 2/3 of ${rec / 1000} Mbps recommended)" else ""))
        return Plan(true, k, ceiling, link.rate, allow444,
                "Wi-Fi carries ~${link.wire / 1000} Mbps here; now ${link.rate / 1000} Mbps" + (if (!allow444 && Yuv444.wanted(ctx)) ", 4:2:0" else ""),
                link.upHoldMs)
    }

    // ------------------------------------------------------------------ a stream running

    /** What the controller needs from the stream (Game). */
    interface Stream {
        /** Changes this stream to [kbps] without reconnecting; false when it can't (then [reconnect] is used). */
        fun setLive(kbps: Int): Boolean
        /** Starts the stream again (the game keeps running): the next start uses what was learnt. False: not now. */
        fun reconnect(note: String): Boolean
        fun toast(text: String)
        /** The stream is PyroWave 4:4:4 now. */
        fun pyro444(): Boolean
        /** The stream is PyroWave now. */
        fun pyro(): Boolean
        /** Live changes can be tried (Polaris, its encoder takes them). Asked on a worker thread. */
        fun liveCapable(): Boolean
    }

    /** Process-wide: step-downs lately (a reconnect makes a new Game), step-ups not yet proven. */
    private val stepDowns = ArrayList<Long>()
    private var lastUpAt = 0L
    private var lastUpFrom = 0

    /** One stream's watcher. Created by Game when the stream starts, [stop]ped when it ends. Main thread only. */
    class Controller(private val ctx: Context, private val plan: Plan, private val stream: Stream, private val width: Int,
                     private val height: Int, private val fps: Int) {
        private val ui = Handler(Looper.getMainLooper())
        private var running = false
        /** What the stream runs at now, kbps. */
        @Volatile var currentKbps = plan.startKbps
            private set
        private var live: Boolean? = null
        private var activeSince = 0L
        private var settleUntil = 0L
        private var lastChangeAt = SystemClock.elapsedRealtime()
        private var upHold = plan.upHoldMs
        private val tot = LongArray(4)
        private var lp = -1L; private var lb = 0L; private var lm = 0L; private var lt = 0L
        private var streak = 0
        private var samples = ArrayList<Int>()
        private var clean = 0
        private var busy = false
        private var lastLogAt = 0L

        fun start() {
            if (!plan.on || running) return
            running = true
            LimeLog.info(TAG + "watching the stream at ${currentKbps / 1000} Mbps (set ${plan.ceilingKbps / 1000})")
            // Polaris: can this stream's bitrate change live? (one HTTPS call)
            Thread({
                val ok = try { stream.liveCapable() } catch (e: Exception) { LimeLog.warning(TAG + "live check: $e"); false }
                ui.post { live = ok; LimeLog.info(TAG + if (ok) "changes apply live (Polaris)" else "changes reconnect") }
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
            val p = tot[0]; val b = tot[1]; val m = tot[2]
            if (p == 0L) return
            if (activeSince == 0L) { activeSince = now; settleUntil = now + SETTLE_MS }
            val first = lp < 0 || p < lp
            val dp = (p - lp).toDouble(); val dm = (if (m >= lm) m - lm else 0L).toDouble()
            val dt = (now - lt) / 1000.0
            val db = b - lb
            lp = p; lb = b; lm = m; lt = now
            if (first || now < settleUntil || busy || dt < 0.2) return
            val wireKbps = (db * 8 / 1000 / dt).toInt()
            if (dp + dm < MIN_PACKETS * dt) return   // a still picture: nothing to judge
            val loss = dm / (dp + dm)
            val cautious = synchronized(stepDowns) { stepDowns.any { now - it < CAUTIOUS_MS } }
            val threshold = if (cautious) REPEAT_LOSS else LOSS
            val need = if (cautious) REPEAT_SECONDS else LOSS_SECONDS
            if (loss > threshold) { streak++; samples.add(wireKbps) } else { streak = 0; samples.clear() }
            // clean while the stream uses its bitrate (a quiet picture proves nothing about the link)
            if (loss >= CLEAN) clean = 0 else if (wireKbps * 10 >= currentKbps * 4) clean++
            if (loss > 0.005 && now - lastLogAt > 5000) {
                lastLogAt = now
                LimeLog.info(TAG + String.format(java.util.Locale.US, "%.1f%% missing, %d Mbps arrived at %d Mbps (%d s over)",
                        loss * 100, wireKbps / 1000, currentKbps / 1000, streak))
            }
            if (streak >= need) { linkFull(samples.sum() / samples.size, loss, cautious, now); return }
            if (clean >= CLEAN_SECONDS && currentKbps < plan.ceilingKbps && now - lastChangeAt >= upHold) stepUp(now)
        }

        private fun linkFull(carried: Int, loss: Double, cautious: Boolean, now: Long) {
            streak = 0; samples.clear(); clean = 0
            val cap = minOf(capKbps(carried), plan.ceilingKbps)
            LimeLog.info(TAG + "link full (" + (if (cautious) ">5% missing for 5 s, after a step-down" else ">2% missing for 3 s") +
                    ") at ${currentKbps / 1000} Mbps: ${carried / 1000} Mbps arrived, ${(loss * 100).toInt()}% missing; this PC here gets ${cap / 1000} Mbps")
            // a step-up that loses within a minute: wait twice as long before the next one
            if (lastUpAt != 0L && now - lastUpAt < UP_HOLD_MS) {
                upHold = minOf(upHold * 2, UP_HOLD_MAX_MS)
                LimeLog.info(TAG + "the step-up from ${lastUpFrom / 1000} Mbps lost again: next step-up after ${upHold / 1000} s")
                lastUpAt = 0L
            }
            plan.key?.let { remember(ctx, it, cap, carried, upHold) }
            // already at (or under) what the link carries: something else is losing packets
            if (currentKbps <= cap * 1.05) return
            synchronized(stepDowns) {
                stepDowns.removeAll { now - it > 60_000 }
                if (stepDowns.size >= 3) { LimeLog.info(TAG + "three step-downs in a minute: holding"); return }
                stepDowns.add(now)
            }
            change(cap, "Wi-Fi carries ~${carried / 1000} Mbps here; now ${cap / 1000} Mbps", down = true)
        }

        private fun stepUp(now: Long) {
            clean = 0
            val to = minOf(plan.ceilingKbps, maxOf(currentKbps + 1000, (currentKbps * STEP_UP).toInt() / 1000 * 1000))
            LimeLog.info(TAG + "clean for $CLEAN_SECONDS s at ${currentKbps / 1000} Mbps: stepping up to ${to / 1000} Mbps (set ${plan.ceilingKbps / 1000})")
            lastUpAt = now; lastUpFrom = currentKbps
            val wire = (to * WIRE_OVERHEAD / SHARE).toInt()
            // back at the bitrate set: nothing to remember any more
            plan.key?.let { remember(ctx, it, if (to >= plan.ceilingKbps) 0 else to, wire, upHold) }
            change(to, "Wi-Fi is clean; now ${to / 1000} Mbps", down = false)
        }

        private fun change(kbps: Int, note: String, down: Boolean) {
            val now = SystemClock.elapsedRealtime()
            lastChangeAt = now
            settleUntil = now + SETTLE_MS
            // PyroWave going below 2/3 of its recommended rate in 4:4:4: only a new start brings 4:2:0
            val rec = Pyrowave.recommendedKbps(width, height, fps)
            val needs420 = down && stream.pyro444() && kbps * 3 < rec * 2
            if (live == true && !needs420) {
                busy = true
                Thread({
                    val ok = try { stream.setLive(kbps) } catch (e: Exception) { LimeLog.warning(TAG + "live change: $e"); false }
                    ui.post {
                        busy = false
                        if (!running) return@post
                        if (ok) {
                            LimeLog.info(TAG + "now ${kbps / 1000} Mbps (live)")
                            currentKbps = kbps
                            settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
                            stream.toast(note)
                        } else {
                            live = false
                            reconnect(kbps, note)
                        }
                    }
                }, "mc-autobitrate-set").start()
                return
            }
            reconnect(kbps, note + if (needs420) ", 4:2:0" else "")
        }

        private fun reconnect(kbps: Int, note: String) {
            if (!stream.reconnect(note)) {
                LimeLog.info(TAG + "not reconnecting now (the Quick menu is open); asking again later")
                lastChangeAt = 0L
                return
            }
            LimeLog.info(TAG + "reconnecting at ${kbps / 1000} Mbps")
            running = false
            ui.removeCallbacks(tick)
        }
    }

    /** The stats line's bitrate: "N Mbps (auto)" while Automatic bitrate is on. */
    @JvmStatic
    fun label(kbps: Int, on: Boolean): String = "${kbps / 1000} Mbps" + if (on) " (auto)" else ""

    // ------------------------------------------------------------------ Polaris live bitrate

    /**
     * Polaris (papi-ux/polaris): this stream's scope and how its encoder rate relates to the bitrate asked for, from
     * GET /polaris/v1/session/status. Null when the session isn't ours or its encoder can't change rate live
     * (adaptive_runtime_update_supported: NVENC and PyroWave can).
     */
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
        val req = if (split > 0) split else units?.optDouble("requested_kbps", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        val enc = units?.optDouble("encoder_kbps", 0.0) ?: 0.0
        // the share of a request the encoder gets (FEC, audio and overhead taken off), as the handshake split it
        val share = if (req > 0 && enc > 0) (enc / req).coerceIn(0.3, 1.0) else 0.8
        LimeLog.info(TAG + "Polaris session: generation $gen, owned $owned, runtime bitrate $runtime, encoder ${enc.toInt()} of ${req.toInt()} kbps asked")
        if (id.isEmpty() || gen <= 0 || !owned || !runtime) return null
        val asked = units?.optDouble("requested_kbps", 0.0) ?: 0.0
        val hostCapped = split > 0 && asked > split
        return PolarisSession(id, gen, share, if (hostCapped) enc.toInt() else 0)
    }

    /** The body of POST /polaris/v1/session/bitrate for a stream bitrate of [kbps] (encoder kbps, 1000-500000). */
    @JvmStatic
    fun polarisBitrateBody(s: PolarisSession, kbps: Int): String =
        JSONObject().put("bitrate_kbps", (kbps * s.encoderShare).toInt()
                .let { if (s.hostCapEncoderKbps > 0) minOf(it, s.hostCapEncoderKbps) else it }.coerceIn(1000, 500_000))
                .put("app_session_id", s.appSessionId).put("session_generation", s.generation).toString()
}
