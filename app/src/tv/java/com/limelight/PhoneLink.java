package com.limelight;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.home.Ps5Store;
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
 * The phone's channel to this TV: a tiny HTTP server on :8099. The phone (or Odin) POSTs a game to /play and this TV
 * starts it with native Moonlight or native chiaki; /register links a PlayStation through this TV; /mc-native lets the
 * phone find us. Moved out of the old receiver screen so the Big Picture home can keep listening while you browse.
 *
 * PS5 keys that arrive here are saved to {@link Ps5Store}, so the console also appears on the TV's own shelf.
 */
public class PhoneLink {
    public static final int PORT = 8099;

    /** What the home screen shows while a sent game starts. Called on the main thread. */
    public interface Listener {
        void onIncoming(String title, String from);
        void onConsolesChanged();
        /** The Odin started registering a PlayStation through this TV. */
        default void onRegistering(String host) { }
        /** Registration finished: the console's name, or null if it failed. */
        default void onRegistered(String host, String name) { }
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = true;
    private ServerSocket server;

    public PhoneLink(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() { new Thread(this::serve, "mc-phone-link").start(); }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    // Prefer a PAIRED PC (it has a stored server certificate - set only after a successful pairing), so a stray
    // auto-discovered PC that was never paired can't win. Fall back to the first PC if none is paired yet.
    private ComputerDetails firstComputer() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(ctx);
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
            String from = "your phone";
            if ("GET".equals(method) && path.startsWith("/mc-native")) {
                ComputerDetails pc = firstComputer();
                send(s, 200, "{\"mooncast_native\":1,\"name\":" + quote(android.os.Build.MODEL)
                        + ",\"pc\":" + quote(pc == null ? "" : pc.name) + "}");
            } else if ("POST".equals(method) && path.startsWith("/play")) {
                String json = readBody(in, clen, 16384);   // read it all: a PSN token makes it bigger than one read
                String appId = field(json, "appId"), appName = field(json, "appName");
                String sender = field(json, "deviceName");
                if (sender != null && !sender.isEmpty()) from = sender;
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
                            field(json, "psnToken"), field(json, "duid"), field(json, "accountId"), from))
                        send(s, 200, "{\"ok\":1}");
                    else send(s, 400, "{\"error\":\"ps5 launch failed\"}");
                } else if (appId != null && play(appId, appName, from)) send(s, 200, "{\"ok\":1}");
                else send(s, 400, "{\"error\":\"no app id, or no paired PC\"}");
            } else if ("POST".equals(method) && path.startsWith("/register")) {
                // Register a PlayStation (the PIN "Link Device" flow): host + PSN account id (base64) + 8-digit pin.
                String json = readBody(in, clen, 2048);
                String host = field(json, "host");
                main.post(() -> listener.onRegistering(host));
                try {
                    byte[] accountId = android.util.Base64.decode(field(json, "accountId"), android.util.Base64.DEFAULT);
                    int pin = Integer.parseInt(field(json, "pin").trim());
                    boolean isPs5 = !"false".equalsIgnoreCase(field(json, "ps5"));
                    Ps5Register.Result r = Ps5Register.INSTANCE.register(host, accountId, pin, isPs5, 30000L);
                    if (r != null) {
                        Ps5Store.INSTANCE.save(ctx, new Ps5Store.Console(host, r.getNickname() == null ? "PS5" : r.getNickname(),
                                r.getRegistKeyHex(), r.getMorningHex(), isPs5));
                        final String nick = r.getNickname() == null ? "PS5" : r.getNickname();
                        main.post(() -> { listener.onConsolesChanged(); listener.onRegistered(host, nick); });
                        send(s, 200, "{\"ok\":1,\"registKey\":\"" + r.getRegistKeyHex() + "\",\"morning\":\""
                                + r.getMorningHex() + "\",\"nickname\":" + quote(r.getNickname()) + "}");
                    }
                    else {
                        main.post(() -> listener.onRegistered(host, null));
                        send(s, 400, "{\"error\":\"registration failed - check the 8-digit code and that the console is on\"}");
                    }
                } catch (Exception e) {
                    main.post(() -> listener.onRegistered(host, null));
                    send(s, 400, "{\"error\":\"bad register request\"}");
                }
            } else {
                send(s, 404, "{\"error\":\"not found\"}");
            }
            s.close();
        } catch (Exception ignored) { try { s.close(); } catch (Exception e) { } }
    }

    private static String readBody(BufferedReader in, int clen, int max) throws java.io.IOException {
        char[] body = new char[Math.max(0, Math.min(clen, max))];
        int n = 0;
        while (n < body.length) { int r = in.read(body, n, body.length - n); if (r < 0) break; n += r; }
        return new String(body, 0, Math.max(0, n));
    }

    // Show "sent from" on the TV for a moment, then start the stream through the trampoline (it handles the paired
    // PC and the connection).
    private boolean play(String appId, String appName, String from) {
        final ComputerDetails pc = firstComputer();
        if (pc == null) return false;
        main.post(() -> listener.onIncoming(appName == null ? "Your game" : appName, from));
        main.postDelayed(() -> {
            Intent i = new Intent(ctx, ShortcutTrampoline.class);
            i.putExtra(AppView.UUID_EXTRA, pc.uuid);
            i.putExtra(AppView.NAME_EXTRA, pc.name);
            i.putExtra(Game.EXTRA_APP_ID, appId);
            if (appName != null) i.putExtra(Game.EXTRA_APP_NAME, appName);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }, 700);
        return true;
    }

    private boolean playPs5(String host, String registKey, String morning, String ps5,
                            String psnToken, String duid, String accountId, String from) {
        if (registKey == null || morning == null) return false;
        final boolean isPs5 = ps5 == null || !"false".equalsIgnoreCase(ps5);
        Ps5Store.INSTANCE.save(ctx, new Ps5Store.Console(host, "PS5", registKey, morning, isPs5));
        main.post(() -> { listener.onConsolesChanged(); listener.onIncoming("PlayStation 5", from); });
        main.postDelayed(() -> ctx.startActivity(ps5Intent(ctx, host, registKey, morning, isPs5, psnToken, duid, accountId)), 700);
        return true;
    }

    /** The intent that starts a native PS5 Remote Play session. The PSN fields are only for play away from home. */
    public static Intent ps5Intent(Context ctx, String host, String registKey, String morning, boolean isPs5,
                                   String psnToken, String duid, String accountId) {
        Intent i = new Intent(ctx, Ps5StreamActivity.class);
        i.putExtra(Ps5StreamActivity.EXTRA_HOST, host);
        i.putExtra(Ps5StreamActivity.EXTRA_REGIST_KEY, registKey);
        i.putExtra(Ps5StreamActivity.EXTRA_MORNING, morning);
        i.putExtra(Ps5StreamActivity.EXTRA_PS5, isPs5);
        if (psnToken != null && !psnToken.isEmpty()) i.putExtra(Ps5StreamActivity.EXTRA_PSN_TOKEN, psnToken);
        if (duid != null && !duid.isEmpty()) i.putExtra(Ps5StreamActivity.EXTRA_DUID, duid);
        if (accountId != null && !accountId.isEmpty()) i.putExtra(Ps5StreamActivity.EXTRA_ACCOUNT_ID, accountId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
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
}
