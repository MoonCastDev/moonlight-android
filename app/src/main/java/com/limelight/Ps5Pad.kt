package com.limelight

import android.util.Base64
import android.util.Log
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.Session
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * The phone is the controller for a native PS5 session too. Connects to the phone's pad server
 * (ws://phone:8099/pad), reads {"pads":[{"b":[17 W3C],"a":[4]}]} and feeds pad 0 into the chiaki
 * Session via setControllerState (DualSense mapping). Same relay idea as [PhoneGamepad], but the
 * target is a chiaki session instead of the Moonlight connection.
 */
object Ps5Pad {
    private const val TAG = "MoonCast-PS5Pad"
    private const val PAD_PORT = 8099
    @Volatile private var running = false
    @Volatile private var sock: Socket? = null
    @Volatile private var thread: Thread? = null

    fun start(session: Session) {
        stop()   // never leave a previous relay thread running
        val h = PhoneGamepad.phoneHost() ?: return
        running = true
        thread = Thread({ run(session, h) }, "ps5-pad").also { it.start() }
    }

    fun stop() {
        running = false
        try { sock?.close() } catch (e: Exception) {}
        try { thread?.interrupt() } catch (e: Exception) {}
        thread = null
    }

    private fun run(session: Session, h: String) {
        var backoff = 1000L
        while (running) {
            try { session(session, h); backoff = 1000L } catch (e: Exception) { Log.w(TAG, "pad link: $e") }
            if (!running) break
            try { Thread.sleep(backoff) } catch (e: InterruptedException) { break }
            if (backoff < 8000L) backoff += 1000L   // the phone may not be reachable; don't busy-loop
        }
    }

    private fun session(session: Session, h: String) {
        val s = Socket()
        s.connect(InetSocketAddress(h, PAD_PORT), 2000)
        s.tcpNoDelay = true
        sock = s
        val out = s.getOutputStream()
        val inp = s.getInputStream()
        val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val pk = PhoneGamepad.phoneHost()?.let { PhoneGamepad.padKey() }   // present the phone's pad key so it authorizes us
        val path = if (!pk.isNullOrEmpty()) "/pad?k=$pk" else "/pad"
        out.write(("GET $path HTTP/1.1\r\nHost: $h:$PAD_PORT\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        out.flush()
        if (!readHeaders(inp)) throw Exception("handshake refused")
        sendText(out, "{\"hello\":{\"id\":\"mctv-ps5\",\"kind\":\"mctv-ps5\"}}")
        while (running) {
            val msg = readTextFrame(inp) ?: break
            apply(session, msg)
        }
        try { s.close() } catch (e: Exception) {}
        try { session.setControllerState(ControllerState()) } catch (e: Exception) {}  // neutral on drop
    }

    private fun apply(session: Session, json: String) {
        try {
            val o = JSONObject(json)
            if (!o.has("pads")) return
            val pads = o.optJSONArray("pads") ?: return
            if (pads.length() == 0 || pads.isNull(0)) { session.setControllerState(ControllerState()); return }
            val pad = pads.getJSONObject(0)
            val b = pad.optJSONArray("b"); val a = pad.optJSONArray("a")
            val bt = DoubleArray(17); val ax = DoubleArray(4)
            for (i in 0 until 17) if (b != null && i < b.length()) bt[i] = b.optDouble(i, 0.0)
            for (i in 0 until 4) if (a != null && i < a.length()) ax[i] = a.optDouble(i, 0.0)
            // Combos for the buttons an Xbox-style pad lacks (its Guide button is grabbed by the launcher):
            //   L2 + Start -> PS (home),  Select + Start -> Touchpad click.
            val select = bt[8] > .5; val start = bt[9] > .5; val l2held = bt[6] > .5
            val psCombo = l2held && start
            val tpCombo = select && start && !l2held
            var flags = 0u
            if (bt[0] > .5) flags = flags or ControllerState.BUTTON_CROSS
            if (bt[1] > .5) flags = flags or ControllerState.BUTTON_MOON
            if (bt[2] > .5) flags = flags or ControllerState.BUTTON_BOX
            if (bt[3] > .5) flags = flags or ControllerState.BUTTON_PYRAMID
            if (bt[4] > .5) flags = flags or ControllerState.BUTTON_L1
            if (bt[5] > .5) flags = flags or ControllerState.BUTTON_R1
            if (select && !tpCombo) flags = flags or ControllerState.BUTTON_SHARE
            if (start && !psCombo && !tpCombo) flags = flags or ControllerState.BUTTON_OPTIONS
            if (bt[10] > .5) flags = flags or ControllerState.BUTTON_L3
            if (bt[11] > .5) flags = flags or ControllerState.BUTTON_R3
            if (bt[12] > .5) flags = flags or ControllerState.BUTTON_DPAD_UP
            if (bt[13] > .5) flags = flags or ControllerState.BUTTON_DPAD_DOWN
            if (bt[14] > .5) flags = flags or ControllerState.BUTTON_DPAD_LEFT
            if (bt[15] > .5) flags = flags or ControllerState.BUTTON_DPAD_RIGHT
            if (bt[16] > .5 || psCombo) flags = flags or ControllerState.BUTTON_PS
            if (tpCombo) flags = flags or ControllerState.BUTTON_TOUCHPAD
            val st = ControllerState(
                buttons = flags,
                l2State = (if (psCombo) 0.0 else bt[6].coerceIn(0.0, 1.0)) .let { (it * 255).toInt().toUByte() },
                r2State = (bt[7].coerceIn(0.0, 1.0) * 255).toInt().toUByte(),
                leftX = axis(ax[0]), leftY = axis(ax[1]), rightX = axis(ax[2]), rightY = axis(ax[3])
            )
            session.setControllerState(st)
        } catch (e: Exception) { Log.w(TAG, "bad pad: $e") }
    }

    private fun axis(v: Double): Short =
        (Math.round(v * 32767).coerceIn(-32767L, 32767L)).toShort()

    // ---- minimal WebSocket client ----
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
        when { len < 126 -> f.write(0x80 or len)
            len < 65536 -> { f.write(0x80 or 126); f.write(len shr 8); f.write(len) }
            else -> { f.write(0x80 or 127); for (i in 7 downTo 0) f.write((len.toLong() shr (8 * i)).toInt()) } }
        f.write(mask)
        for (i in payload.indices) f.write(payload[i].toInt() xor mask[i and 3].toInt())
        synchronized(out) { out.write(f.toByteArray()); out.flush() }
    }

    private fun readTextFrame(inp: InputStream): String? {
        val b0 = inp.read(); if (b0 == -1) return null
        val opcode = b0 and 0x0F
        val b1 = inp.read(); if (b1 == -1) return null
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) len = ((rd(inp) shl 8) or rd(inp)).toLong()
        else if (len == 127L) { len = 0; for (i in 0 until 8) len = (len shl 8) or rd(inp).toLong() }
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
