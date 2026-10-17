package com.limelight.handheld

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds MoonCast TVs on this Wi-Fi, the way the old phone app's TvAppFinder did: every address on this /24 is asked
 * GET :8099/mc-native, and a MoonCast TV answers {"mooncast_native":1,"name","pc","xbox","version","id"}.
 *
 * TVs are remembered (with when they were last seen) so the picker can show a known TV as "Not on this network"
 * while it's away. A full sweep takes about two seconds; between sweeps only the remembered addresses are asked.
 */
object TvFinder {
    const val PORT = 8099
    private const val PREF_TVS = "hh_tvs"
    private const val PREF_LAST = "hh_last_tv"
    private const val PREF_TARGET = "hh_target_tv"
    /** A TV that answered within this long counts as on this network. */
    private const val HERE_MS = 150_000L

    class Tv(val id: String, val ip: String, val name: String, val pc: String, val xbox: Boolean, val version: Int, val seen: Long) {
        /** /play takes an Xbox (MoonCast TV from this release on). */
        val canXbox get() = version >= 2
        fun json(): JSONObject = JSONObject().put("id", id).put("ip", ip).put("name", name).put("pc", pc)
                .put("xbox", xbox).put("version", version).put("seen", seen)
        companion object {
            fun of(j: JSONObject) = Tv(j.optString("id"), j.optString("ip"), j.optString("name", "MoonCast TV"),
                    j.optString("pc"), j.optBoolean("xbox"), j.optInt("version", 1), j.optLong("seen"))
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val here = ConcurrentHashMap<String, Long>()   // id -> when it last answered, in this run of the app
    @Volatile private var sweeping = false
    @Volatile var lastSweepAt = 0L; private set
    /** Called on the main thread after a sweep or a check changed what's found. */
    var onChange: (() -> Unit)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mc_tv", Context.MODE_PRIVATE)

    /** Every TV this device has seen, the ones here now first, then the most recently seen. */
    fun known(ctx: Context): List<Tv> = try {
        val a = JSONArray(prefs(ctx).getString(PREF_TVS, "[]"))
        (0 until a.length()).map { Tv.of(a.getJSONObject(it)) }
                .sortedWith(compareByDescending<Tv> { isHere(it) }.thenByDescending { it.seen })
    } catch (e: Exception) { emptyList() }

    fun isHere(tv: Tv): Boolean = here[tv.id]?.let { System.currentTimeMillis() - it < HERE_MS } ?: false
    fun byId(ctx: Context, id: String?): Tv? = if (id.isNullOrEmpty()) null else known(ctx).firstOrNull { it.id == id }

    /** The TV used last (to move a game to), or null. */
    fun lastTv(ctx: Context): Tv? = byId(ctx, prefs(ctx).getString(PREF_LAST, null))

    /** Where games go when you press A: a TV, or null for this device. */
    fun target(ctx: Context): Tv? = byId(ctx, prefs(ctx).getString(PREF_TARGET, null))

    fun setTarget(ctx: Context, tv: Tv?) {
        val e = prefs(ctx).edit().putString(PREF_TARGET, tv?.id ?: "")
        if (tv != null) e.putString(PREF_LAST, tv.id)
        e.apply()
    }

    /** Remember [tv] as the one to move games to, without making it where games go by default. */
    fun setLast(ctx: Context, tv: Tv) = prefs(ctx).edit().putString(PREF_LAST, tv.id).apply()

    fun forget(ctx: Context, id: String) {
        save(ctx, known(ctx).filter { it.id != id })
        here.remove(id)
        if (prefs(ctx).getString(PREF_TARGET, "") == id) prefs(ctx).edit().putString(PREF_TARGET, "").apply()
        if (prefs(ctx).getString(PREF_LAST, "") == id) prefs(ctx).edit().remove(PREF_LAST).apply()
    }

    private fun save(ctx: Context, list: List<Tv>) {
        val a = JSONArray(); list.take(12).forEach { a.put(it.json()) }
        prefs(ctx).edit().putString(PREF_TVS, a.toString()).apply()
    }

    /** This device's address on the Wi-Fi (or Ethernet), or null when it isn't on a local network. */
    fun myIp(): String? = try {
        val all = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
        val ordered = all.sortedBy { n -> when { n.name.startsWith("wlan") -> 0; n.name.startsWith("eth") -> 1; else -> 2 } }
        ordered.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    /** Ask only the TVs we know (cheap: a handful of requests). */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        val list = known(app)
        if (list.isEmpty() || sweeping) return
        Thread({
            val found = list.mapNotNull { ask(it.ip) }
            merge(app, found, list.map { it.ip }.toSet())
        }, "tv-check").start()
    }

    /** Sweep the whole /24 for MoonCast TVs (about two seconds). [done] runs on the main thread. */
    fun sweep(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (sweeping) { done?.let { d -> ui.postDelayed({ d() }, 2500) }; return }
        val me = myIp()
        if (me == null) { done?.let { ui.post(it) }; return }
        sweeping = true
        Thread({
            val net = me.substring(0, me.lastIndexOf('.') + 1)
            val hits = java.util.concurrent.CopyOnWriteArrayList<Tv>()
            val pool = Executors.newFixedThreadPool(48)
            // the TVs we know first, so they're back quickly even if their address moved within the sweep
            val order = (known(app).map { it.ip }.filter { it.startsWith(net) } + (1..254).map { net + it }).distinct()
            for (ip in order) { if (ip != me) pool.execute { ask(ip)?.let { hits.add(it) } } }
            pool.shutdown()
            try { pool.awaitTermination(5, TimeUnit.SECONDS) } catch (e: InterruptedException) { }
            pool.shutdownNow()
            lastSweepAt = System.currentTimeMillis()
            merge(app, hits, null)
            sweeping = false
            done?.let { ui.post(it) }
        }, "tv-sweep").start()
    }

    /** Record what answered; [asked] = the addresses tried (null = the whole network, so anything silent is away). */
    @Synchronized private fun merge(ctx: Context, found: List<Tv>, asked: Set<String>?) {
        val before = known(ctx).filter { isHere(it) }.map { it.id }.toSet()
        val now = System.currentTimeMillis()
        val byId = LinkedHashMap<String, Tv>()
        known(ctx).forEach { byId[it.id] = it }
        // a TV whose address now belongs to another TV moved; drop the stale address
        found.forEach { f -> byId.values.filter { it.ip == f.ip && it.id != f.id }.forEach { byId[it.id] = Tv(it.id, "", it.name, it.pc, it.xbox, it.version, it.seen) } }
        found.forEach { f -> byId[f.id] = f; here[f.id] = now }
        val silent = byId.values.filter { t -> found.none { it.id == t.id } && (asked == null || t.ip in asked) }
        silent.forEach { here.remove(it.id) }
        save(ctx, byId.values.toList())
        val after = known(ctx).filter { isHere(it) }.map { it.id }.toSet()
        if (before != after || found.isNotEmpty()) ui.post { onChange?.invoke() }
    }

    /** One address: a MoonCast TV, or null. */
    fun ask(ip: String): Tv? {
        if (ip.isEmpty()) return null
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://$ip:$PORT/mc-native").openConnection() as HttpURLConnection
            c.connectTimeout = 400; c.readTimeout = 1200
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText().take(8192) })
            if (j.optInt("mooncast_native") != 1) return null
            val id = j.optString("id").ifEmpty { "ip:$ip" }   // TVs from before the "id" field: known by address
            Tv(id, ip, j.optString("name", "MoonCast TV").ifBlank { "MoonCast TV" }, j.optString("pc"), j.optBoolean("xbox"),
                    j.optInt("version", 1), System.currentTimeMillis())
        } catch (e: Exception) { null } finally { c?.disconnect() }
    }
}
