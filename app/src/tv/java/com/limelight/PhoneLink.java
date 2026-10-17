package com.limelight;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.home.Ps5Store;
import com.limelight.home.Xbox;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.CacheHelper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
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
    /** What /mc-native reports as "version". 2 = /play accepts xboxId (+ xboxName). 3 = POST /stop.
     *  4 = /play "pad":"direct" (the sender's controller goes straight to the PC) and POST /pad (relay after all).
     *  5 = with "pad":"direct" the TV's stream registers no gamepad at all, so the sender's session is pad 0.
     *  6 = GET /status: how the stream the last /play started is going ({"state","error","seq"}). */
    public static final int PROTOCOL_VERSION = 6;

    // GET /status: "idle", "starting" (after /play), "playing" or "failed" with the player's own words
    private static volatile String playState = "idle";
    private static volatile String playError = null;
    private static volatile int playSeq = 0;

    // The stream screens open right now (PC, PS5, Xbox), so /stop can end them. Tracked once for the whole app.
    private static final java.util.Set<android.app.Activity> streams =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static boolean tracking;

    private static synchronized void track(Context app) {
        if (tracking || !(app instanceof android.app.Application)) return;
        tracking = true;
        com.limelight.home.StreamReport.setListener((state, msg) -> {
            playState = state; playError = "failed".equals(state) ? msg : null;
            return kotlin.Unit.INSTANCE;
        });
        ((android.app.Application) app).registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            private boolean isStream(android.app.Activity a) {
                return a instanceof Game || a instanceof Ps5StreamActivity || a instanceof XboxStreamActivity;
            }
            @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { if (isStream(a)) synchronized (streams) { streams.add(a); } }
            @Override public void onActivityDestroyed(android.app.Activity a) { synchronized (streams) { streams.remove(a); } }
            @Override public void onActivityStarted(android.app.Activity a) { }
            @Override public void onActivityResumed(android.app.Activity a) { }
            @Override public void onActivityPaused(android.app.Activity a) { }
            @Override public void onActivityStopped(android.app.Activity a) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
        });
    }

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
        track(this.ctx);
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
                // version 2: /play takes xboxId too. "xbox" = this TV is signed in to Xbox (it plays with its own sign-in).
                // "id" stays the same when the TV's address changes, so a handheld can remember it.
                send(s, 200, "{\"mooncast_native\":1,\"name\":" + quote(android.os.Build.MODEL)
                        + ",\"pc\":" + quote(pc == null ? "" : pc.name)
                        + ",\"xbox\":" + Xbox.INSTANCE.signedIn(ctx)
                        + ",\"version\":" + PROTOCOL_VERSION
                        + ",\"id\":" + quote(tvId())
                        + ",\"identityKey\":" + quote(identityKey())
                        + ",\"paired\":" + (pc != null && pc.serverCert != null) + "}");
            } else if ("GET".equals(method) && path.startsWith("/status")) {
                send(s, 200, "{\"state\":" + quote(playState) + ",\"error\":" + quote(playError == null ? "" : playError)
                        + ",\"seq\":" + playSeq + "}");
            } else if ("POST".equals(method) && path.startsWith("/play")) {
                playSeq++; playState = "starting"; playError = null;
                String json = readBody(in, clen, 16384);   // read it all: a PSN token makes it bigger than one read
                String appId = field(json, "appId"), appName = field(json, "appName");
                String sender = field(json, "deviceName");
                if (sender != null && !sender.isEmpty()) from = sender;
                // The phone is the controller: remember where it is (the address it POSTed from, or an explicit phoneIp).
                String phoneIp = field(json, "phoneIp");
                if (phoneIp == null || phoneIp.isEmpty()) {
                    try { phoneIp = s.getInetAddress().getHostAddress(); } catch (Exception ignored) { }
                }
                // "pad":"direct" (PC games): the sender has its own controller session on the PC, so no relay from here.
                // Set before the key, which may restart the relay.
                PhoneGamepad.setDirect("direct".equals(field(json, "pad")) && field(json, "ps5host") == null && field(json, "xboxId") == null);
                PhoneGamepad.setPhoneHost(phoneIp);
                PhoneGamepad.setPadKey(field(json, "padKey"));   // key to authorize with the phone's pad server
                // A device with no PC pairing sent its identity: use it for this session only.
                String identity = field(json, "identity");
                if (identity != null && !identity.isEmpty() && !acceptIdentity(identity)) {
                    send(s, 400, "{\"error\":\"identity not accepted\"}");
                    s.close();
                    return;
                }
                // PS5 Remote Play (native chiaki): the phone sends the console's host + registration keys.
                String ps5host = field(json, "ps5host");
                if (ps5host != null && !ps5host.isEmpty()) {
                    if (playPs5(ps5host, field(json, "registKey"), field(json, "morning"), field(json, "ps5"),
                            field(json, "psnToken"), field(json, "duid"), field(json, "accountId"), from))
                        send(s, 200, "{\"ok\":1}");
                    else send(s, 400, "{\"error\":\"ps5 launch failed\"}");
                } else if (field(json, "xboxId") != null) {
                    // Xbox: this TV plays it with its OWN Xbox sign-in; the sender is only the controller.
                    String err = playXbox(field(json, "xboxId"), field(json, "xboxName"), from);
                    if (err == null) send(s, 200, "{\"ok\":1}");
                    else send(s, 400, "{\"error\":" + quote(err) + "}");
                } else if ((appId != null || appName != null) && play(appId, appName, from)) send(s, 200, "{\"ok\":1}");
                else send(s, 400, "{\"error\":\"no app id, or no paired PC\"}");
            } else if ("POST".equals(method) && path.startsWith("/stop")) {
                endSessionIdentity(ctx);
                // The sender takes its game back (Play here instead) or stops: end the stream it started here and go
                // home. Disconnect only; {"quit":true} quits the game on the PC too. Only the sender of the last
                // /play may do this (it presents the same padKey).
                String json = readBody(in, clen, 2048);
                String key = field(json, "padKey"), mine = PhoneGamepad.padKey();
                if (mine != null && !mine.isEmpty() && !mine.equals(key)) send(s, 403, "{\"error\":\"not the device that sent this game\"}");
                else {
                    boolean quit = json.replace(" ", "").contains("\"quit\":true");
                    stopStreams(quit);
                    send(s, 200, "{\"ok\":1}");
                }
            } else if ("POST".equals(method) && path.startsWith("/pad")) {
                // The sender's direct controller session didn't connect: relay its pad through this TV after all.
                String json = readBody(in, clen, 2048);
                String key = field(json, "padKey"), mine = PhoneGamepad.padKey();
                if (mine != null && !mine.isEmpty() && !mine.equals(key)) send(s, 403, "{\"error\":\"not the device that sent this game\"}");
                else {
                    String ip = field(json, "phoneIp");
                    if (ip == null || ip.isEmpty()) ip = s.getInetAddress().getHostAddress();
                    PhoneGamepad.setPhoneHost(ip);
                    PhoneGamepad.useRelay();
                    send(s, 200, "{\"ok\":1}");
                }
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
    /** The PC a sent identity came with, while its session is open. Null when this TV uses its own pairing. */
    private static volatile String sessionPcUuid;

    /** Opens an identity sent by another MoonCast device: saved as session files, and its PC added for this session. */
    private boolean acceptIdentity(String blob) {
        try {
            identityKey();   // makes this run's receiver key if it isn't there yet
            org.json.JSONObject j = com.limelight.identity.IdentityTransfer.openIdentity(
                    identityKeys.getPrivate(), com.limelight.identity.IdentityTransfer.rawPublic(identityKeys.getPublic()), blob);
            java.io.File dir = ctx.getFilesDir();
            writeFile(new java.io.File(dir, "session-client.crt"), android.util.Base64.decode(j.getString("cert"), android.util.Base64.DEFAULT));
            writeFile(new java.io.File(dir, "session-client.key"), android.util.Base64.decode(j.getString("key"), android.util.Base64.DEFAULT));

            ComputerDetails pc = new ComputerDetails();
            pc.uuid = j.getString("uuid");
            pc.name = j.getString("name");
            pc.localAddress = new ComputerDetails.AddressTuple(j.getString("address"), j.getInt("port"));
            pc.activeAddress = pc.localAddress;
            pc.serverCert = (java.security.cert.X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(
                            android.util.Base64.decode(j.getString("serverCert"), android.util.Base64.DEFAULT)));
            pc.pairState = com.limelight.nvstream.http.PairingManager.PairState.PAIRED;
            pc.state = ComputerDetails.State.ONLINE;
            ComputerDatabaseManager db = new ComputerDatabaseManager(ctx);
            try { db.updateComputer(pc); } finally { db.close(); }
            sessionPcUuid = pc.uuid;
            return true;
        } catch (Exception e) {
            endSessionIdentity(ctx);
            return false;
        }
    }

    private static void writeFile(java.io.File f, byte[] data) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(data); }
    }

    /** Removes the session identity and its PC entry. Called when the stream stops and when the TV app closes. */
    public static void endSessionIdentity(Context c) {
        new java.io.File(c.getFilesDir(), "session-client.crt").delete();
        new java.io.File(c.getFilesDir(), "session-client.key").delete();
        String uuid = sessionPcUuid;
        sessionPcUuid = null;
        if (uuid != null) {
            ComputerDatabaseManager db = new ComputerDatabaseManager(c);
            try {
                ComputerDetails pc = db.getComputerByUUID(uuid);
                if (pc != null) db.deleteComputer(pc);
            } finally { db.close(); }
        }
    }

    private boolean play(String appId, String appName, String from) {
        final ComputerDetails pc;
        if (sessionPcUuid != null) {
            ComputerDatabaseManager db = new ComputerDatabaseManager(ctx);
            try { pc = db.getComputerByUUID(sessionPcUuid); } finally { db.close(); }
        } else pc = firstComputer();
        if (pc == null) return false;
        // Any sender (phone, tablet, Odin) names the game, and the game ID is looked up on THIS TV's PC. A sender's own
        // ID is only the fallback: its PC list may number the games differently.
        final String localId = appName == null ? null : localAppId(pc.uuid, appName);
        if (localId == null && appId == null) return false;
        main.post(() -> listener.onIncoming(appName == null ? "Your game" : appName, from));
        main.postDelayed(() -> {
            replaceStreams();
            Intent i = new Intent(ctx, ShortcutTrampoline.class);
            i.putExtra(AppView.UUID_EXTRA, pc.uuid);
            i.putExtra(AppView.NAME_EXTRA, pc.name);
            i.putExtra(Game.EXTRA_APP_ID, localId != null ? localId : appId);
            if (appName != null) i.putExtra(Game.EXTRA_APP_NAME, appName);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }, 700);
        return true;
    }

    /** The game's ID in this TV's cached app list for the PC, or null if the list isn't cached or has no such game. */
    private String localAppId(String uuid, String name) {
        try {
            String raw = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(ctx.getCacheDir(), "applist", uuid));
            if (raw == null || raw.isEmpty()) return null;
            for (NvApp a : NvHTTP.getAppListByReader(new StringReader(raw)))
                if (a.getAppName().equals(name)) return String.valueOf(a.getAppId());
        } catch (Exception ignored) { }
        return null;
    }

    /** A new /play replaces whatever player is open (even one showing an error): close it, the new one starts fresh. */
    private static void replaceStreams() {
        java.util.List<android.app.Activity> list;
        synchronized (streams) { list = new java.util.ArrayList<>(streams); }
        for (android.app.Activity a : list) if (!a.isFinishing()) a.finish();
    }

    /** Finish every stream screen (PC, PS5, Xbox) and bring the home back. */
    private void stopStreams(boolean quit) {
        main.post(() -> {
            java.util.List<android.app.Activity> list;
            synchronized (streams) { list = new java.util.ArrayList<>(streams); }
            for (android.app.Activity a : list) {
                if (a.isFinishing()) continue;
                if (quit && a instanceof Game) ((Game) a).quitOnHostAndFinish();
                else a.finish();   // disconnect: the game keeps running (a PS5 stays on)
            }
            PhoneGamepad.stop();
            Intent home = new Intent(ctx, com.limelight.home.HomeActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try { ctx.startActivity(home); } catch (Exception ignored) { }
        });
    }

    /** Start an Xbox the sender named, if this TV's Xbox account has it. Returns an error for the sender, or null. */
    private String playXbox(String id, String name, String from) {
        if (!Xbox.INSTANCE.signedIn(ctx)) return "Sign in to Xbox on this TV first";
        Xbox.Console found = null;
        for (Xbox.Console c : Xbox.INSTANCE.consoles(ctx)) if (c.getId().equals(id)) found = c;
        if (found == null) {   // a console added since the TV last looked: ask Xbox network once
            try { for (Xbox.Console c : Xbox.INSTANCE.refreshConsoles(ctx)) if (c.getId().equals(id)) found = c; }
            catch (Exception ignored) { }
        }
        if (found == null) return "This TV's Xbox account doesn't have " + (name == null || name.isEmpty() ? "that Xbox" : name);
        final Xbox.Console c = found;
        XboxStreamActivity.setRemotePad(XboxPhonePad.INSTANCE);
        main.post(() -> listener.onIncoming(c.getName(), from));
        main.postDelayed(() -> {
            replaceStreams();
            Intent i = com.limelight.home.Launch.xboxIntent(ctx, c);
            i.putExtra(XboxStreamActivity.EXTRA_REMOTE_PAD, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        }, 700);
        return null;
    }

    /** Same for the life of this install; lets a handheld recognise this TV after its address changes. */
    /** This TV's public key for identity transfer (raw X25519, base64). Made once per run; the private half never leaves. */
    private static java.security.KeyPair identityKeys;

    private synchronized String identityKey() {
        try {
            if (identityKeys == null) identityKeys = com.limelight.identity.IdentityTransfer.newReceiverKeys();
            return android.util.Base64.encodeToString(
                    com.limelight.identity.IdentityTransfer.rawPublic(identityKeys.getPublic()), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private String tvId() {
        String a = android.provider.Settings.Secure.getString(ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        return a == null ? "" : Integer.toHexString(a.hashCode());
    }

    private boolean playPs5(String host, String registKey, String morning, String ps5,
                            String psnToken, String duid, String accountId, String from) {
        if (registKey == null || morning == null) return false;
        final boolean isPs5 = ps5 == null || !"false".equalsIgnoreCase(ps5);
        Ps5Store.INSTANCE.save(ctx, new Ps5Store.Console(host, "PS5", registKey, morning, isPs5));
        main.post(() -> { listener.onConsolesChanged(); listener.onIncoming("PlayStation 5", from); });
        main.postDelayed(() -> { replaceStreams(); ctx.startActivity(ps5Intent(ctx, host, registKey, morning, isPs5, psnToken, duid, accountId)); }, 700);
        return true;
    }

    /** The intent that starts a native PS5 Remote Play session. The PSN fields are only for play away from home. */
    public static Intent ps5Intent(Context ctx, String host, String registKey, String morning, boolean isPs5,
                                   String psnToken, String duid, String accountId) {
        return com.limelight.home.Launch.ps5Intent(ctx, host, registKey, morning, isPs5, psnToken, duid, accountId);
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
