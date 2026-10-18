package com.limelight;

import android.util.Log;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * The phone IS the controller for the native receiver. The phone (MoonCast app) broadcasts its pad on a tiny
 * WebSocket server (ws://phone:8099/pad) as {"pads":[{"b":[17],"a":[4]}, ...]} (W3C gamepad order). This connects
 * there and feeds pad 0 straight into THIS stream's own Moonlight connection (conn.sendControllerInput) - the same
 * virtual gamepad the game already reads. That is why the native path needs no second Sunshine session for the
 * controller (which gave the game a gamepad it ignored, and a second audio stream that broke the sound).
 */
class PhoneGamepad {
    private static final String TAG = "MoonCast-PhonePad";
    private static final int PAD_PORT = 8099;        // the phone's PadServer
    private static final long GIVE_UP_MS = 30000;     // stop retrying after this long without reaching the sender

    private static volatile PhoneGamepad current;
    private static volatile NvConnection lastConn;    // the stream it feeds, until that stream stops (stop())
    // The sender drives the PC directly with its own controller-only session (/play "pad":"direct"): the next stream
    // registers no gamepad and gets no relay, or the game would get the pad twice (or the TV would take pad 0).
    // /pad turns the relay on later if the direct session can't connect.
    private static volatile boolean direct;
    private static volatile String phoneHost;         // set from the /play socket's remote address
    private static volatile String padKey;            // the phone's pad-server session key (for /pad?k=...)

    private final NvConnection conn;
    private volatile String host;
    private volatile boolean running = true;
    private volatile boolean quick;    // reconnect right away (the sender's key or address changed), no 1 s wait
    private volatile Socket sock;

    private PhoneGamepad(NvConnection conn, String host) { this.conn = conn; this.host = host; }

    /** Remember where the phone is, from the address that POSTed /play. */
    static void setPhoneHost(String ip) {
        if (ip == null || ip.isEmpty()) return;
        boolean changed = !ip.equals(phoneHost);
        phoneHost = ip;
        PhoneGamepad p = current;
        if (changed && p != null) { p.host = ip; p.kick(); }
    }

    /** The last phone address that drove a /play (used by the PS5 pad relay too). */
    static String phoneHost() { return phoneHost; }

    /**
     * The game the sender started has ended: forget the sender (#40). Otherwise a game started later on the TV itself
     * kept calling that device for its controller every few seconds. The next /play sets it again.
     */
    static void forgetPhone() {
        phoneHost = null;
        padKey = null;
    }

    /** The phone's pad-server session key, passed in /play; the relay presents it so the pad server authorizes us. */
    static void setPadKey(String k) {
        boolean changed = k == null ? padKey != null : !k.equals(padKey);
        padKey = k;
        // a new key from a new /play: the running link presents the old one and would only notice at the read timeout
        PhoneGamepad p = current;
        if (changed && p != null) p.kick();
        else if (p == null) revive();
    }

    // A new /play while the stream is still up but the link gave up (the sender was away for a while): start again.
    private static synchronized void revive() {
        NvConnection c = lastConn;
        if (current == null && c != null && !c.controllersBlocked()) { start(c, null); }
    }

    /** Drop the current link and connect again at once (with the latest key and address). */
    private void kick() {
        quick = true;
        try { Socket s = sock; if (s != null) s.close(); } catch (Exception ignored) { }
    }
    static String padKey() { return padKey; }

    /** Start feeding this connection from the phone's pad (uses the last known phone address, or an override). */
    /** /play said the sender's controller goes straight to the PC (true), or through us (false). */
    private static volatile int playGen;   // counts /play requests

    /** Which /play the stream being created belongs to (see [streamEnded]). */
    static int playGen() { return playGen; }

    static synchronized void setDirect(boolean d) {
        playGen++;
        direct = d;
        if (!d) return;
        // a relay from an earlier send would feed the same pad a second time: end it, keep the stream it fed
        PhoneGamepad p = current; current = null;
        if (p != null) { p.running = false; try { if (p.sock != null) p.sock.close(); } catch (Exception ignored) { } }
    }

    /** The next stream (the one /play starts) leaves the gamepads to the sender's direct session. */
    static boolean isDirect() { return direct; }

    /** The stream that /play started is over: streams started on the TV itself use its own controllers. */
    static void streamEnded(int gen) { if (gen == playGen) direct = false; }   // a newer /play keeps its own setting

    /** The sender asks for the relay after all (its direct session failed): start it on the running stream. */
    static synchronized void useRelay() {
        direct = false;
        NvConnection c = lastConn;
        if (c != null) c.setControllersBlocked(false);   // the relay's input goes through this stream now
        revive();
    }

    static synchronized void start(NvConnection conn, String overrideHost) {
        stop();
        // the stream itself knows (Game blocked its controllers when /play said direct): a stream of the TV's own isn't
        // affected by a flag left from an earlier send
        if (conn != null && conn.controllersBlocked()) { lastConn = conn; Log.i(TAG, "sender's controller goes direct to the PC: no relay"); return; }
        String h = overrideHost != null && !overrideHost.isEmpty() ? overrideHost : phoneHost;
        if (conn == null || h == null || h.isEmpty()) { Log.w(TAG, "no phone address yet - controller not started"); return; }
        PhoneGamepad p = new PhoneGamepad(conn, h);
        current = p;
        lastConn = conn;
        new Thread(p::run, "phone-gamepad").start();
    }

    /** The stream [c] ended: stop the relay only if it feeds that stream (a newer stream may already have its own). */
    static synchronized void stopFor(NvConnection c) {
        PhoneGamepad p = current;
        if (p != null && p.conn == c) stop();
        else if (lastConn == c) lastConn = null;
    }

    static synchronized void stop() {
        lastConn = null;
        PhoneGamepad p = current; current = null;
        if (p != null) { p.running = false; try { if (p.sock != null) p.sock.close(); } catch (Exception ignored) { } }
    }

    private void run() {
        long failingSince = 0;   // when the sender stopped answering (0 = the last link worked)
        while (running) {
            boolean linked = false;
            try { linked = session(); } catch (Exception e) { Log.w(TAG, "phone pad link: " + e); }
            if (!running) break;
            long now = System.currentTimeMillis();
            if (linked) failingSince = 0;
            else if (failingSince == 0) failingSince = now;
            else if (now - failingSince > GIVE_UP_MS) {   // the sender's pad server is gone (it stopped, or left the Wi-Fi)
                Log.i(TAG, "phone pad: no answer for " + (GIVE_UP_MS / 1000) + " s, giving up");
                synchronized (PhoneGamepad.class) { if (current == this) current = null; }
                running = false;
                break;
            }
            if (quick) { quick = false; continue; }
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }   // reconnect if the phone drops
        }
    }

    /** One link. True when it got through the handshake (so a later drop is a reconnect, not a dead sender). */
    private boolean session() throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, PAD_PORT), 2000);
        s.setTcpNoDelay(true);
        // The phone pings every ~8s; if neither a frame nor a ping arrives in the window the link died silently
        // (WiFi power-save / NAT) - time out the blocked read so run() reconnects instead of freezing the controller.
        s.setSoTimeout(25000);
        sock = s;
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        // WebSocket handshake (present the phone's pad-server key so it authorizes us: /pad?k=<key>)
        byte[] keyBytes = new byte[16]; new SecureRandom().nextBytes(keyBytes);
        String key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP);
        String path = padKey != null && !padKey.isEmpty() ? "/pad?k=" + padKey : "/pad";
        out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + PAD_PORT + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        // read response headers until blank line
        if (!readHeaders(in)) throw new Exception("handshake refused");
        boolean linked = true;

        // introduce ourselves so the phone sends pad states here (not expecting a web gamepad page)
        sendText(out, "{\"hello\":{\"id\":\"mctv-native\",\"kind\":\"mctv-native\"}}");

        try {
            while (running) {
                String msg = readTextFrame(in);
                if (msg == null) break;
                if (msg.isEmpty()) continue;   // ping/pong keep-alive: nothing to apply
                apply(msg);
            }
        } finally {
            try { s.close(); } catch (Exception ignored) { }
            // release every button on ANY exit (incl. a read timeout), so nothing sticks while we reconnect
            conn.sendControllerInput((short) 0, (short) 0x1, 0, (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        }
        return linked;
    }

    // {"pads":[{"b":[..17..],"a":[..4..]}, ...]} -> pad 0 into the stream
    private void apply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("pads")) return;             // control message (phoneInput/takeover/session): not a pad state
            JSONArray pads = o.optJSONArray("pads");
            if (pads == null || pads.length() == 0 || pads.isNull(0)) {
                conn.sendControllerInput((short) 0, (short) 0x1, 0, (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
                return;
            }
            JSONObject pad = pads.getJSONObject(0);
            JSONArray b = pad.optJSONArray("b"), a = pad.optJSONArray("a");
            double[] bt = new double[17], ax = new double[4];
            for (int i = 0; b != null && i < 17 && i < b.length(); i++) bt[i] = b.optDouble(i, 0);
            for (int i = 0; a != null && i < 4 && i < a.length(); i++) ax[i] = a.optDouble(i, 0);

            int flags = 0;
            if (bt[0] > .5) flags |= ControllerPacket.A_FLAG;
            if (bt[1] > .5) flags |= ControllerPacket.B_FLAG;
            if (bt[2] > .5) flags |= ControllerPacket.X_FLAG;
            if (bt[3] > .5) flags |= ControllerPacket.Y_FLAG;
            if (bt[4] > .5) flags |= ControllerPacket.LB_FLAG;
            if (bt[5] > .5) flags |= ControllerPacket.RB_FLAG;
            if (bt[8] > .5) flags |= ControllerPacket.BACK_FLAG;
            if (bt[9] > .5) flags |= ControllerPacket.PLAY_FLAG;
            if (bt[10] > .5) flags |= ControllerPacket.LS_CLK_FLAG;
            if (bt[11] > .5) flags |= ControllerPacket.RS_CLK_FLAG;
            if (bt[12] > .5) flags |= ControllerPacket.UP_FLAG;
            if (bt[13] > .5) flags |= ControllerPacket.DOWN_FLAG;
            if (bt[14] > .5) flags |= ControllerPacket.LEFT_FLAG;
            if (bt[15] > .5) flags |= ControllerPacket.RIGHT_FLAG;
            if (bt[16] > .5) flags |= ControllerPacket.SPECIAL_BUTTON_FLAG;

            byte lt = (byte) Math.max(0, Math.min(255, (int) (bt[6] * 255)));
            byte rt = (byte) Math.max(0, Math.min(255, (int) (bt[7] * 255)));
            short lx = axis(ax[0]), ly = axis(-ax[1]), rx = axis(ax[2]), ry = axis(-ax[3]);   // W3C Y is down-positive
            conn.sendControllerInput((short) 0, (short) 0x1, flags, lt, rt, lx, ly, rx, ry);
        } catch (Exception e) { Log.w(TAG, "bad pad state: " + e); }
    }

    private static short axis(double v) {
        int i = (int) Math.round(v * 32767);
        return (short) Math.max(-32767, Math.min(32767, i));
    }

    // ---- minimal WebSocket client ----
    private boolean readHeaders(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int c, nl = 0;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            if (c == '\n') { if (++nl == 2) break; } else if (c != '\r') nl = 0;
        }
        String h = sb.toString();
        return h.startsWith("HTTP/1.1 101") || h.contains(" 101 ");
    }

    private void sendText(OutputStream out, String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[4]; new SecureRandom().nextBytes(mask);
        java.io.ByteArrayOutputStream f = new java.io.ByteArrayOutputStream();
        f.write(0x81);                              // FIN + text
        int len = payload.length;
        if (len < 126) f.write(0x80 | len);
        else if (len < 65536) { f.write(0x80 | 126); f.write(len >> 8); f.write(len); }
        else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) len >> (8 * i))); }
        f.write(mask);
        for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i & 3]);
        synchronized (out) { out.write(f.toByteArray()); out.flush(); }
    }

    private String readTextFrame(InputStream in) throws Exception {
        int b0 = in.read(); if (b0 == -1) return null;
        int opcode = b0 & 0x0F;
        int b1 = in.read(); if (b1 == -1) return null;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) len = (read(in) << 8) | read(in);
        else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | read(in); }
        byte[] mask = new byte[4];
        if (masked) for (int i = 0; i < 4; i++) mask[i] = (byte) read(in);
        byte[] data = new byte[(int) len];
        int off = 0; while (off < data.length) { int r = in.read(data, off, data.length - off); if (r < 0) return null; off += r; }
        if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
        if (opcode == 0x8) return null;             // close
        if (opcode == 0x9 || opcode == 0xA || opcode == 0x0) return "";   // ping/pong/continuation: ignore
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int read(InputStream in) throws Exception { int c = in.read(); if (c == -1) throw new Exception("eof"); return c; }
}
