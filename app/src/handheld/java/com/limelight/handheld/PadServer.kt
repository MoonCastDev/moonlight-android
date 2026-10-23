package com.limelight.handheld

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The handheld's controller, served to the MoonCast TV that plays the game: a tiny WebSocket server on :8099
 * (ws://<handheld>:8099/pad?k=<padKey>). The TV's relays (PhoneGamepad for a PC game, Ps5Pad for a PS5, XboxPhonePad
 * for an Xbox) connect here after /play and read
 *
 *     {"pads":[{"b":[17 buttons, 0..1],"a":[4 axes, -1..1]}]}
 *
 * in W3C Standard Gamepad order (b: A B X Y LB RB LT RT Select Start L3 R3 Up Down Left Right Guide;
 * a: LX LY RX RY, Y down-positive). A frame goes out at most 60 times a second when something changed, and the full
 * state is repeated every 500 ms so the link never looks dead (the relays time out after 25 s of silence).
 *
 * A PS5 relay also takes UDP on the TV's :8100 ({"seq","k","pads"}, resent every 50 ms), which is not held back by a
 * lost TCP segment on a jittery Wi-Fi; it is sent to relays that said hello as "mctv-ps5". Only a client presenting
 * a pad key from [keys] (one per TV, see [PadLink]) is accepted. This is part of the SENDER: it serves the controller;
 * it never receives games.
 */
class PadServer {
    /** The pad keys handed to TVs in /play; a TV presents its own in /pad?k=. */
    val keys = java.util.concurrent.CopyOnWriteArraySet<String>()
    companion object {
        const val PORT = 8099
        const val UDP_PORT = 8100
        private const val TAG = "MoonCast-PadServer"
        const val BUTTONS = 17
        const val AXES = 4
    }

    private class Client(val sock: Socket, val out: OutputStream, val ip: String, val key: String) { @Volatile var kind: String? = null }

    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var udp: DatagramSocket? = null
    private var seq = 0L

    // the pad state, written on the main thread, read by the ticker
    private val lock = Any()
    private val b = FloatArray(BUTTONS)
    private val a = FloatArray(AXES)
    @Volatile private var dirty = true
    private var lastWs = 0L
    private var lastUdp = 0L

    /** Called (on a background thread) when the number of connected TVs changes. */
    @Volatile var onClients: ((Int) -> Unit)? = null
    val clientCount get() = clients.size

    val isRunning get() = running

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                server = s
                while (running) { val c = s.accept(); Thread({ handle(c) }, "pad-client").start() }
            } catch (e: Exception) { if (running) Log.w(TAG, "pad server stopped: $e") }
        }, "pad-server").start()
        ticker.scheduleWithFixedDelay({ tick() }, 16, 16, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { }
        ticker.shutdownNow()
        // let go of everything on the TV before the link closes, so nothing stays held (off the main thread)
        synchronized(lock) { b.fill(0f); a.fill(0f) }
        val f = frame(stateJson())
        val list = clients.toList()
        clients.clear()
        Thread({
            list.forEach { c ->
                try { synchronized(c.out) { c.out.write(f); c.out.write(byteArrayOf(0x88.toByte(), 0)); c.out.flush() } } catch (e: Exception) { }
                try { c.sock.close() } catch (e: Exception) { }
            }
            try { udp?.close() } catch (e: Exception) { }
        }, "pad-server-stop").start()
    }

    /** The newest pad state (copied). */
    fun update(buttons: FloatArray, axes: FloatArray) {
        synchronized(lock) {
            var changed = false
            for (i in 0 until BUTTONS) if (b[i] != buttons[i]) { b[i] = buttons[i]; changed = true }
            for (i in 0 until AXES) if (a[i] != axes[i]) { a[i] = axes[i]; changed = true }
            if (changed) dirty = true
        }
    }

    private fun stateJson(): String = synchronized(lock) {
        val sb = StringBuilder(160).append("{\"pads\":[{\"b\":[")
        for (i in 0 until BUTTONS) { if (i > 0) sb.append(','); num(sb, b[i]) }
        sb.append("],\"a\":[")
        for (i in 0 until AXES) { if (i > 0) sb.append(','); num(sb, a[i]) }
        sb.append("]}]}").toString()
    }

    private fun num(sb: StringBuilder, v: Float) {
        if (v == 0f) sb.append('0') else if (v == 1f) sb.append('1') else sb.append(String.format(java.util.Locale.US, "%.3f", v))
    }

    private fun tick() {
        if (!running || clients.isEmpty()) return
        val now = System.currentTimeMillis()
        val changed = dirty
        if (!changed && now - lastWs < 500 && now - lastUdp < 50) return
        dirty = false
        val json = stateJson()
        if (changed || now - lastWs >= 500) {
            lastWs = now
            val f = frame(json)
            for (c in clients) {
                try { synchronized(c.out) { c.out.write(f); c.out.flush() } } catch (e: Exception) { drop(c) }
            }
        }
        if (changed || now - lastUdp >= 50) {
            lastUdp = now
            val targets = clients.filter { it.kind == "mctv-ps5" }
            if (targets.isNotEmpty()) try {
                val sock = udp ?: DatagramSocket().also { udp = it }
                val n = ++seq
                for (c in targets) {
                    val bytes = ("{\"seq\":" + n + ",\"k\":\"" + c.key + "\"," + json.substring(1)).toByteArray(StandardCharsets.UTF_8)
                    sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(c.ip), UDP_PORT))
                }
            } catch (e: Exception) { Log.w(TAG, "udp pad: $e") }
        }
    }

    private fun drop(c: Client) {
        if (clients.remove(c)) onClients?.invoke(clients.size)
        try { c.sock.close() } catch (e: Exception) { }
    }

    private fun handle(s: Socket) {
        var me: Client? = null
        try {
            s.tcpNoDelay = true   // controller input: never batch small packets
            s.soTimeout = 0
            val inp = s.getInputStream(); val out = s.getOutputStream()
            val head = readHeader(inp)
            val line = head.substringBefore("\r\n")
            val path = line.split(' ').getOrNull(1) ?: ""
            val given = Regex("[?&]k=([^&\\s]+)").find(path)?.groupValues?.get(1) ?: ""
            val wsKey = Regex("(?im)^Sec-WebSocket-Key:\\s*(\\S+)").find(head)?.groupValues?.get(1)
            if (!path.startsWith("/pad") || wsKey == null ||
                    keys.none { MessageDigest.isEqual(given.toByteArray(StandardCharsets.US_ASCII), it.toByteArray(StandardCharsets.US_ASCII)) }) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                out.flush(); s.close(); return
            }
            val accept = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(
                    (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)), Base64.NO_WRAP)
            val c = Client(s, out, s.inetAddress.hostAddress ?: "", given)
            synchronized(out) {
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                out.write(frame(stateJson()))   // the current state right away
                out.flush()
            }
            me = c
            clients.add(c)
            Log.i(TAG, "TV controller link from ${c.ip}")
            onClients?.invoke(clients.size)
            // read what the TV sends: its hello ({"hello":{"kind":...}}), pings, and the close
            val din = DataInputStream(inp)
            while (running) {
                val b0 = din.readUnsignedByte(); val b1 = din.readUnsignedByte()
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) len = din.readUnsignedShort().toLong() else if (len == 127L) len = din.readLong()
                if (len > 65536) break
                val mask = ByteArray(4)
                if (b1 and 0x80 != 0) din.readFully(mask)
                val data = ByteArray(len.toInt()); din.readFully(data)
                for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
                when (b0 and 0x0f) {
                    0x8 -> break
                    0x9 -> synchronized(out) { out.write(byteArrayOf(0x8A.toByte(), data.size.toByte()) + data); out.flush() }   // pong
                    0x1 -> {
                        val t = String(data, StandardCharsets.UTF_8)
                        Regex("\"kind\"\\s*:\\s*\"([^\"]+)\"").find(t)?.groupValues?.get(1)?.let { c.kind = it; dirty = true }
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            if (me != null) drop(me) else try { s.close() } catch (e: Exception) { }
        }
    }

    private fun frame(text: String): ByteArray {
        val d = text.toByteArray(StandardCharsets.UTF_8)
        val f = ByteArrayOutputStream(d.size + 4)
        f.write(0x81)
        if (d.size < 126) f.write(d.size) else { f.write(126); f.write(d.size shr 8); f.write(d.size and 0xff) }
        f.write(d, 0, d.size)
        return f.toByteArray()
    }

    private fun readHeader(inp: InputStream): String {
        val b = ByteArrayOutputStream()
        var state = 0
        while (b.size() < 8192) {
            val c = inp.read()
            if (c == -1) break
            b.write(c)
            state = if (c == '\r'.code || c == '\n'.code) state + 1 else 0
            if (state == 4) break
        }
        return b.toString("US-ASCII")
    }
}
