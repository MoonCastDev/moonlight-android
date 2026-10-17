package com.limelight

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * The sender's controller for an Xbox game sent to this TV. Same link as [PhoneGamepad] and [Ps5Pad]: connect to the
 * sender's pad server (ws://<phone>:8099/pad?k=<padKey>), read {"pads":[{"b":[17],"a":[4]}]} and hand pad 0 to
 * XboxStreamActivity, which drives player 1 with it. Registered with XboxStreamActivity.remotePad by [PhoneLink].
 */
object XboxPhonePad : XboxStreamActivity.RemotePad {
    private const val TAG = "MoonCast-XboxPad"
    private const val PAD_PORT = 8099
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile private var sink: ((DoubleArray, DoubleArray) -> Unit)? = null

    override fun start(sink: (DoubleArray, DoubleArray) -> Unit) {
        stop()
        val h = PhoneGamepad.phoneHost() ?: return
        this.sink = sink
        running = true
        Thread({ run(h) }, "xbox-phone-pad").start()
    }

    override fun stop() {
        running = false
        sink = null
        try { sock?.close() } catch (e: Exception) { }
        sock = null
    }

    private fun run(h: String) {
        var backoff = 1000L
        while (running) {
            try { session(h); backoff = 1000L } catch (e: Exception) { Log.w(TAG, "pad link: $e") }
            if (!running) break
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
            if (backoff < 8000L) backoff += 1000L
        }
    }

    private fun session(h: String) {
        val s = Socket()
        s.connect(InetSocketAddress(h, PAD_PORT), 2000)
        s.tcpNoDelay = true
        s.soTimeout = 25000   // the sender sends a heartbeat every few seconds; silence this long = a dead link
        sock = s
        val out = s.getOutputStream(); val inp = s.getInputStream()
        val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val pk = PhoneGamepad.padKey()
        val path = if (!pk.isNullOrEmpty()) "/pad?k=$pk" else "/pad"
        out.write(("GET $path HTTP/1.1\r\nHost: $h:$PAD_PORT\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        out.flush()
        if (!readHeaders(inp)) throw Exception("handshake refused")
        sendText(out, "{\"hello\":{\"id\":\"mctv-xbox\",\"kind\":\"mctv-xbox\"}}")
        try {
            while (running) {
                val msg = readTextFrame(inp) ?: break
                if (msg.isEmpty()) continue
                apply(msg)
            }
        } finally {
            try { s.close() } catch (e: Exception) { }
            sink?.invoke(DoubleArray(17), DoubleArray(4))   // release everything while we reconnect
        }
    }

    private fun apply(json: String) {
        try {
            val o = JSONObject(json)
            if (!o.has("pads")) return
            val pads = o.optJSONArray("pads") ?: return
            val bt = DoubleArray(17); val ax = DoubleArray(4)
            if (pads.length() > 0 && !pads.isNull(0)) {
                val pad = pads.getJSONObject(0)
                val b = pad.optJSONArray("b"); val a = pad.optJSONArray("a")
                for (i in 0 until 17) if (b != null && i < b.length()) bt[i] = b.optDouble(i, 0.0)
                for (i in 0 until 4) if (a != null && i < a.length()) ax[i] = a.optDouble(i, 0.0)
            }
            sink?.invoke(bt, ax)
        } catch (e: Exception) { Log.w(TAG, "bad pad: $e") }
    }

    // ---- minimal WebSocket client (as in PhoneGamepad) ----
    private fun readHeaders(inp: InputStream): Boolean {
        val sb = StringBuilder(); var nl = 0; var c: Int
        while (inp.read().also { c = it } != -1) {
            sb.append(c.toChar())
            if (c == '\n'.code) { if (++nl == 2) break } else if (c != '\r'.code) nl = 0
        }
        val h = sb.toString()
        return h.startsWith("HTTP/1.1 101") || h.contains(" 101 ")
    }

    private fun sendText(out: OutputStream, text: String) {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val f = java.io.ByteArrayOutputStream()
        f.write(0x81)
        val len = payload.size
        if (len < 126) f.write(0x80 or len) else { f.write(0x80 or 126); f.write(len shr 8); f.write(len and 0xff) }
        f.write(mask)
        for (i in payload.indices) f.write(payload[i].toInt() xor mask[i and 3].toInt())
        synchronized(out) { out.write(f.toByteArray()); out.flush() }
    }

    private fun readTextFrame(inp: InputStream): String? {
        val b0 = inp.read(); if (b0 == -1) return null
        val b1 = inp.read(); if (b1 == -1) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((rd(inp) shl 8) or rd(inp)).toLong()
        else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or rd(inp).toLong() } }
        if (len > 65536) return null
        val mask = ByteArray(4)
        if (masked) for (i in 0 until 4) mask[i] = rd(inp).toByte()
        val data = ByteArray(len.toInt())
        var off = 0
        while (off < data.size) { val r = inp.read(data, off, data.size - off); if (r < 0) return null; off += r }
        if (masked) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        if (opcode == 0x8) return null
        if (opcode == 0x9 || opcode == 0xA || opcode == 0x0) return ""
        return String(data, StandardCharsets.UTF_8)
    }

    private fun rd(inp: InputStream): Int { val c = inp.read(); if (c == -1) throw Exception("eof"); return c }
}
