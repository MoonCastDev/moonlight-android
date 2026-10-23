package com.limelight.home

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * PlayStation consoles this TV knows. The TV never signs in to PSN itself: the Odin registers a console through this
 * TV (POST /register) or sends its keys with a game (POST /play), and we keep them so the console shows up on the
 * shelf and can be woken and played from the TV at home.
 */
object Ps5Store {
    data class Console(val host: String, val name: String, val registKey: String, val morning: String, val ps5: Boolean)

    enum class Power { ON, REST, NOT_FOUND, CHECKING }
    data class Status(val power: Power, val runningApp: String? = null, val hostName: String? = null)

    private const val PREFS = "mc_ps5"
    private const val KEY = "consoles"

    fun all(ctx: Context): List<Console> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Console(it.getString("host"), it.optString("name", "PS5"), it.getString("registKey"),
                        it.getString("morning"), it.optBoolean("ps5", true))
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Add or update a console. Same registration key = same console (its address can change). */
    @Synchronized fun save(ctx: Context, c: Console) {
        val list = all(ctx).filter { it.registKey != c.registKey && it.host != c.host }.toMutableList()
        val old = all(ctx).firstOrNull { it.registKey == c.registKey }
        list.add(0, if (old != null && (c.name.isBlank() || c.name == "PS5")) c.copy(name = old.name) else c)
        write(ctx, list)
    }

    @Synchronized fun rename(ctx: Context, host: String, name: String) {
        val list = all(ctx)
        if (list.none { it.host == host && it.name != name }) return
        write(ctx, list.map { if (it.host == host) it.copy(name = name) else it })
    }

    @Synchronized fun remove(ctx: Context, host: String) = write(ctx, all(ctx).filter { it.host != host })

    private fun write(ctx: Context, list: List<Console>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("host", it.host).put("name", it.name).put("registKey", it.registKey)
                    .put("morning", it.morning).put("ps5", it.ps5))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    /**
     * Ask the console how it is: the same SRCH probe Remote Play uses. "200 Ok" = on, "620 Server Standby" = rest mode.
     * When a game is running the reply also names it (running-app-name). No reply = not on this network.
     */
    fun probe(host: String, ps5: Boolean): Status = try {
        DatagramSocket().use { s ->
            s.soTimeout = 700
            val msg = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:${if (ps5) "00030010" else "00020020"}\n".toByteArray()
            val dst = InetAddress.getByName(host)
            var reply: String? = null
            for (i in 0 until 2) {
                s.send(DatagramPacket(msg, msg.size, dst, if (ps5) 9302 else 987))
                try {
                    val buf = ByteArray(2048); val pk = DatagramPacket(buf, buf.size)
                    s.receive(pk)
                    reply = String(buf, 0, pk.length, Charsets.UTF_8); break
                } catch (e: java.net.SocketTimeoutException) { }
            }
            if (reply == null) Status(Power.NOT_FOUND)
            else {
                fun field(k: String) = reply.lineSequence().firstOrNull { it.startsWith("$k:") }?.substringAfter(':')?.trim()
                val first = reply.lineSequence().firstOrNull() ?: ""
                Status(if (first.contains(" 200")) Power.ON else Power.REST, field("running-app-name"), field("host-name"))
            }
        }
    } catch (e: Exception) { Status(Power.NOT_FOUND) }

    data class Found(val host: String, val name: String, val on: Boolean)

    /** Every PlayStation that answers on this network (on or in rest mode). Blocking, about 2 s. */
    fun discover(): List<Found> {
        val out = LinkedHashMap<String, Found>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 300
                val msg = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n".toByteArray()
                val bc = InetAddress.getByName("255.255.255.255")
                val end = System.currentTimeMillis() + 2000
                var sent = 0
                while (System.currentTimeMillis() < end) {
                    if (sent < 3) { s.send(DatagramPacket(msg, msg.size, bc, 9302)); sent++ }
                    try {
                        val buf = ByteArray(2048); val pk = DatagramPacket(buf, buf.size)
                        s.receive(pk)
                        val r = String(buf, 0, pk.length, Charsets.UTF_8)
                        val host = pk.address.hostAddress ?: continue
                        val name = r.lineSequence().firstOrNull { it.startsWith("host-name:") }?.substringAfter(':')?.trim() ?: "PS5"
                        out[host] = Found(host, name, r.lineSequence().first().contains(" 200"))
                    } catch (e: java.net.SocketTimeoutException) { }
                }
            }
        } catch (e: Exception) { }
        return out.values.toList()
    }

    /** chiaki's wake credential: the registration key's ASCII (up to the null pad) parsed as hex. */
    fun wakeCredential(registKeyHex: String): ULong? = try {
        val bytes = registKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it >= 0) it else bytes.size }
        String(bytes, 0, end, Charsets.UTF_8).toULong(16)
    } catch (e: Exception) { null }

    /** Wake from rest mode. Harmless if it's already on. */
    fun wake(c: Console): Boolean {
        val cred = wakeCredential(c.registKey) ?: return false
        return try { com.metallic.chiaki.lib.DiscoveryService.wakeup(null, c.host, cred, c.ps5); true } catch (e: Exception) { false }
    }
}
