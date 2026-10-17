package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * MoonCast TV receiver: the simple "Ready to play - waiting for your phone" screen. The phone drives everything (it
 * picks the game and presses Play); this TV just waits, then streams with NATIVE Moonlight (hardware MediaCodec +
 * Sunshine's own protocol - low latency), not a browser. The phone POSTs the game to :8099 /play; we launch the stream
 * through ShortcutTrampoline (which handles the paired PC + connection), so there is no remote navigation on the TV.
 */
public class MoonCastReceiver extends Activity {
    static final int PORT = 8099;
    private volatile boolean running = true;
    private ServerSocket server;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_receiver);
        status = findViewById(R.id.mc_status);
        new Thread(this::serve, "mc-native-http").start();
    }

    @Override protected void onResume() {
        super.onResume();
        // Stay on the branded "waiting for your phone" screen even with no PC paired: the phone drives everything
        // (incl. PS5, which needs no PC pairing at all), and bouncing to Moonlight's add-PC screen both looked like
        // plain old Moonlight and tore down this receiver's pad server so casts couldn't connect. To pair a PC for
        // Moonlight games, open it from the phone (Add PC) or press MENU here.
        setStatus("Waiting for your phone");
    }

    // MENU opens the Moonlight add-PC screen, for the rare case someone wants to pair a game PC on the TV itself.
    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent e) {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, PcView.class));
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void setStatus(String s) {
        if (status != null) runOnUiThread(() -> status.setText(s));
    }

    // Pick the PC the phone should stream to. Prefer a PAIRED one (it has a stored server
    // certificate - set only after a successful pairing), so a stray auto-discovered PC that was
    // never paired (null cert) can't win the race. Fall back to the first PC if none is paired yet.
    private ComputerDetails firstComputer() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(this);
        try {
            List<ComputerDetails> all = db.getAllComputers();
            if (all.isEmpty()) return null;
            for (ComputerDetails c : all) {
                if (c.serverCert != null) return c;
            }
            return all.get(0);
        } finally {
            db.close();
        }
    }

    // ---- the phone's channel ----
    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            while (running) {
                Socket s = server.accept();
                new Thread(() -> handle(s)).start();
            }
        } catch (Exception ignored) { }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(4000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) { s.close(); return; }
            String[] p = line.split(" ");
            String method = p.length > 0 ? p[0] : "", path = p.length > 1 ? p[1] : "";
            int clen = 0; String l;
            while ((l = in.readLine()) != null && !l.isEmpty()) {
                int c = l.indexOf(':');
                if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase("Content-Length"))
                    try { clen = Integer.parseInt(l.substring(c + 1).trim()); } catch (Exception ignored) { }
            }
            if ("GET".equals(method) && path.startsWith("/mc-native")) {
                ComputerDetails pc = firstComputer();
                send(s, 200, "{\"mooncast_native\":1,\"name\":" + quote(android.os.Build.MODEL)
                        + ",\"pc\":" + quote(pc == null ? "" : pc.name) + "}");
            } else if ("POST".equals(method) && path.startsWith("/play")) {
                char[] body = new char[Math.max(0, Math.min(clen, 16384))];
                int n = 0;   // read the whole body (a PSN token makes it bigger than one read)
                while (n < body.length) { int r = in.read(body, n, body.length - n); if (r < 0) break; n += r; }
                String json = new String(body, 0, Math.max(0, n));
                String appId = field(json, "appId"), appName = field(json, "appName");
                // The phone is the controller: remember where it is (the address it POSTed from, or an explicit phoneIp).
                String phoneIp = field(json, "phoneIp");
                if (phoneIp == null || phoneIp.isEmpty()) {
                    try { phoneIp = s.getInetAddress().getHostAddress(); } catch (Exception ignored) { }
                }
                PhoneGamepad.setPhoneHost(phoneIp);
                PhoneGamepad.setPadKey(field(json, "padKey"));   // key to authorize with the phone's pad server
                // PS5 Remote Play (native chiaki): the phone sends the console's host + registration keys.
                String ps5host = field(json, "ps5host");
                if (ps5host != null && !ps5host.isEmpty()) {
                    if (playPs5(ps5host, field(json, "registKey"), field(json, "morning"), field(json, "ps5"),
                            field(json, "psnToken"), field(json, "duid"), field(json, "accountId")))
                        send(s, 200, "{\"ok\":1}");
                    else send(s, 400, "{\"error\":\"ps5 launch failed\"}");
                } else if (appId != null && play(appId, appName)) send(s, 200, "{\"ok\":1}");
                else send(s, 400, "{\"error\":\"no app id, or no paired PC\"}");
            } else if ("POST".equals(method) && path.startsWith("/register")) {
                // Register a PlayStation (the PIN "Link Device" flow): host + PSN account id (base64) + 8-digit pin.
                char[] body = new char[Math.max(0, Math.min(clen, 2048))];
                int n = clen > 0 ? in.read(body, 0, body.length) : 0;
                String json = new String(body, 0, Math.max(0, n));
                try {
                    String host = field(json, "host");
                    byte[] accountId = android.util.Base64.decode(field(json, "accountId"), android.util.Base64.DEFAULT);
                    int pin = Integer.parseInt(field(json, "pin").trim());
                    boolean isPs5 = !"false".equalsIgnoreCase(field(json, "ps5"));
                    Ps5Register.Result r = Ps5Register.INSTANCE.register(host, accountId, pin, isPs5, 30000L);
                    if (r != null)
                        send(s, 200, "{\"ok\":1,\"registKey\":\"" + r.getRegistKeyHex() + "\",\"morning\":\""
                                + r.getMorningHex() + "\",\"nickname\":" + quote(r.getNickname()) + "}");
                    else send(s, 400, "{\"error\":\"registration failed - check the 8-digit code and that the console is on\"}");
                } catch (Exception e) {
                    send(s, 400, "{\"error\":\"bad register request\"}");
                }
            } else {
                send(s, 404, "{\"error\":\"not found\"}");
            }
            s.close();
        } catch (Exception ignored) { try { s.close(); } catch (Exception e) { } }
    }

    // launch a native stream for the given app id on the paired PC, via the trampoline
    private boolean play(String appId, String appName) {
        final ComputerDetails pc = firstComputer();
        if (pc == null) return false;
        setStatus("Starting " + (appName == null ? "the game" : appName) + "…");
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent i = new Intent(MoonCastReceiver.this, ShortcutTrampoline.class);
            i.putExtra(AppView.UUID_EXTRA, pc.uuid);
            i.putExtra(AppView.NAME_EXTRA, pc.name);
            i.putExtra(Game.EXTRA_APP_ID, appId);
            if (appName != null) i.putExtra(Game.EXTRA_APP_NAME, appName);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getApplicationContext().startActivity(i);
        });
        return true;
    }

    // Launch a NATIVE PS5 Remote Play session (chiaki) for the given console + registration keys.
    private boolean playPs5(String host, String registKey, String morning, String ps5,
                            String psnToken, String duid, String accountId) {
        if (registKey == null || morning == null) return false;
        setStatus("Starting PlayStation…");
        final boolean isPs5 = ps5 == null || !"false".equalsIgnoreCase(ps5);
        new Handler(Looper.getMainLooper()).post(() -> {
            Intent i = new Intent(MoonCastReceiver.this, Ps5StreamActivity.class);
            i.putExtra(Ps5StreamActivity.EXTRA_HOST, host);
            i.putExtra(Ps5StreamActivity.EXTRA_REGIST_KEY, registKey);
            i.putExtra(Ps5StreamActivity.EXTRA_MORNING, morning);
            i.putExtra(Ps5StreamActivity.EXTRA_PS5, isPs5);
            // Remote Play over the internet: used when the console isn't on this TV's LAN
            if (psnToken != null && !psnToken.isEmpty()) i.putExtra(Ps5StreamActivity.EXTRA_PSN_TOKEN, psnToken);
            if (duid != null && !duid.isEmpty()) i.putExtra(Ps5StreamActivity.EXTRA_DUID, duid);
            if (accountId != null && !accountId.isEmpty()) i.putExtra(Ps5StreamActivity.EXTRA_ACCOUNT_ID, accountId);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getApplicationContext().startActivity(i);
        });
        return true;
    }

    private static String field(String json, String key) {
        try {
            int i = json.indexOf("\"" + key + "\"");
            if (i < 0) return null;
            int colon = json.indexOf(':', i);
            int a = json.indexOf('"', colon + 1);
            if (a < 0) {   // numeric value
                int st = colon + 1; while (st < json.length() && " \t".indexOf(json.charAt(st)) >= 0) st++;
                int e = st; while (e < json.length() && "0123456789".indexOf(json.charAt(e)) >= 0) e++;
                return e > st ? json.substring(st, e) : null;
            }
            int b = json.indexOf('"', a + 1);
            // org.json writes "/" as "\/" (base64 account ids and tokens can contain "/")
            return b > a ? json.substring(a + 1, b).replace("\\/", "/") : null;
        } catch (Exception e) { return null; }
    }

    private static String quote(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) { if (c == '"' || c == '\\') b.append('\\'); b.append(c >= 32 ? c : ' '); }
        return b.append('"').toString();
    }

    private static void send(Socket s, int code, String body) throws Exception {
        byte[] by = body.getBytes(StandardCharsets.UTF_8);
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: application/json\r\nContent-Length: " + by.length
                + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(by); o.flush();
    }

    @Override protected void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
