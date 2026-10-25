package dev.spotifyhud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Spotify Web API integration using OAuth 2.0 Authorization Code + PKCE.
 * Login happens in the user's browser on accounts.spotify.com; the mod never sees a password
 * and needs no client secret. Only a refresh token is stored (config/spotifyhud-tokens.json).
 */
public final class Spotify {
    public enum Status { NO_CLIENT_ID, NOT_CONNECTED, IDLE, OK, ERROR }

    public record Snapshot(Status status, String message, String title, String artist, String coverUrl,
                           long durationMs, long progressMs, boolean playing, long atNanos) {
        static Snapshot of(Status s, String msg) {
            return new Snapshot(s, msg, "", "", "", 0, 0, false, System.nanoTime());
        }
    }

    private static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ScheduledExecutorService EX = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SpotifyHUD-net");
        t.setDaemon(true);
        return t;
    });

    private static volatile Snapshot snap = Snapshot.of(Status.NOT_CONNECTED, "Not connected");
    private static volatile String accessToken, refreshToken, verifier, loginState;
    private static volatile long expiresAtMs, backoffUntilMs;
    private static volatile HttpServer server;
    private static String lastLogged = "";
    private static long lastWarnMs;

    private static class AuthRejected extends IOException {
        AuthRejected(String m) { super(m); }
    }

    private Spotify() {}

    public static Snapshot snapshot() { return snap; }

    public static long progressNow(Snapshot s) {
        if (!s.playing()) return s.progressMs();
        long p = s.progressMs() + (System.nanoTime() - s.atNanos()) / 1_000_000L;
        return Math.max(0, Math.min(p, s.durationMs()));
    }

    public static void init() {
        try {
            Path p = tokenPath();
            if (Files.exists(p)) {
                JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                if (o.has("refresh_token")) refreshToken = o.get("refresh_token").getAsString();
            }
        } catch (Exception e) {
            SpotifyHudMod.LOG.warn("Could not read saved Spotify login", e);
        }
        EX.scheduleWithFixedDelay(Spotify::poll, 1, 2, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ login

    public static String redirectUri() {
        return "http://127.0.0.1:" + HudConfig.get().redirectPort + "/callback";
    }

    /** Starts the loopback listener and returns the Spotify authorization URL to open in a browser. */
    public static synchronized String beginLogin() throws Exception {
        HudConfig c = HudConfig.get();
        if (c.clientId.isBlank()) throw new IllegalStateException("Enter your Spotify Client ID first.");
        stopServer();

        SecureRandom rnd = new SecureRandom();
        byte[] vb = new byte[64];
        rnd.nextBytes(vb);
        verifier = b64(vb);
        String challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        byte[] sb = new byte[16];
        rnd.nextBytes(sb);
        final String state = b64(sb);
        loginState = state;
        final String redirect = redirectUri();

        HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), c.redirectPort), 0);
        srv.createContext("/callback", ex -> {
            String msg;
            try {
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                if (!state.equals(q.get("state"))) throw new IOException("state mismatch");
                if (q.containsKey("error")) throw new IOException(q.get("error"));
                Map<String, String> f = new LinkedHashMap<>();
                f.put("grant_type", "authorization_code");
                f.put("code", q.get("code"));
                f.put("redirect_uri", redirect);
                f.put("client_id", HudConfig.get().clientId);
                f.put("code_verifier", verifier);
                tokenRequest(f);
                msg = "Spotify connected. You can close this tab and return to Minecraft.";
                SpotifyHudMod.msg("Spotify connected!");
                EX.execute(Spotify::poll);
            } catch (Exception e) {
                msg = "Login failed: " + e.getMessage();
                SpotifyHudMod.msg("Spotify login failed: " + e.getMessage());
            }
            byte[] out = ("<html><body style='font-family:sans-serif;text-align:center;margin-top:20vh'><h2>"
                    + msg.replace("&", "&amp;").replace("<", "&lt;") + "</h2></body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            EX.schedule(Spotify::stopServer, 1, TimeUnit.SECONDS);
        });
        srv.start();
        server = srv;
        EX.schedule(Spotify::stopServer, 5, TimeUnit.MINUTES);

        return "https://accounts.spotify.com/authorize?response_type=code"
                + "&client_id=" + enc(c.clientId)
                + "&scope=" + enc(SCOPES)
                + "&redirect_uri=" + enc(redirect)
                + "&state=" + enc(state)
                + "&code_challenge_method=S256&code_challenge=" + enc(challenge);
    }

    private static synchronized void stopServer() {
        HttpServer s = server;
        server = null;
        if (s != null) s.stop(0);
    }

    public static void logout() {
        clearTokens();
        snap = Snapshot.of(Status.NOT_CONNECTED, "Not connected");
    }

    private static void clearTokens() {
        accessToken = null;
        refreshToken = null;
        expiresAtMs = 0;
        try { Files.deleteIfExists(tokenPath()); } catch (IOException ignored) {}
    }

    private static synchronized void tokenRequest(Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 400 || r.statusCode() == 401) throw new AuthRejected("Spotify rejected the login (" + r.statusCode() + ")");
        if (r.statusCode() != 200) throw new IOException("Token endpoint returned " + r.statusCode());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        accessToken = o.get("access_token").getAsString();
        if (o.has("refresh_token")) refreshToken = o.get("refresh_token").getAsString();
        expiresAtMs = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L;
        JsonObject save = new JsonObject();
        save.addProperty("refresh_token", refreshToken);
        Files.createDirectories(tokenPath().getParent());
        Files.writeString(tokenPath(), save.toString());
    }

    private static boolean ensureToken() {
        if (accessToken != null && System.currentTimeMillis() < expiresAtMs - 60_000) return true;
        if (refreshToken == null) return false;
        try {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("grant_type", "refresh_token");
            f.put("refresh_token", refreshToken);
            f.put("client_id", HudConfig.get().clientId);
            tokenRequest(f);
            return true;
        } catch (AuthRejected e) {
            clearTokens();
            snap = Snapshot.of(Status.NOT_CONNECTED, "Login expired - reconnect");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            snap = Snapshot.of(Status.ERROR, "Can't reach Spotify");
        }
        return false;
    }

    // ---------------------------------------------------------------- polling

    private static void poll() {
        try {
            HudConfig c = HudConfig.get();
            if (!c.enabled) return;
            if (c.clientId.isBlank()) { snap = Snapshot.of(Status.NO_CLIENT_ID, "Set Spotify Client ID"); return; }
            if (refreshToken == null) { snap = Snapshot.of(Status.NOT_CONNECTED, "Not connected - /spotifyhud login"); return; }
            if (System.currentTimeMillis() < backoffUntilMs) return;
            if (!ensureToken()) return;

            HttpResponse<String> r = api("GET", "/me/player?additional_types=episode");
            switch (r.statusCode()) {
                case 200 -> parse(r.body());
                case 204 -> snap = Snapshot.of(Status.IDLE, "Nothing playing - open Spotify");
                case 401 -> expiresAtMs = 0; // refresh on next poll
                case 429 -> backoffUntilMs = System.currentTimeMillis() + 1000L * r.headers().firstValue("Retry-After").map(Integer::parseInt).orElse(5);
                default -> snap = Snapshot.of(Status.ERROR, "Spotify error " + r.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) { // never let the scheduler die, never crash the game
            snap = Snapshot.of(Status.ERROR, "Can't reach Spotify");
            logOnce(t.toString());
        }
    }

    private static void parse(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (!o.has("item") || !o.get("item").isJsonObject()) {
            snap = Snapshot.of(Status.IDLE, "Advertisement / nothing to show");
            return;
        }
        JsonObject item = o.getAsJsonObject("item");
        String artist = "";
        if (item.has("artists") && item.get("artists").isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement a : item.getAsJsonArray("artists")) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(str(a.getAsJsonObject(), "name"));
            }
            artist = sb.toString();
        } else if (item.has("show") && item.get("show").isJsonObject()) {
            artist = str(item.getAsJsonObject("show"), "publisher");
        }
        JsonArray imgs = null;
        if (item.has("album") && item.get("album").isJsonObject()) imgs = item.getAsJsonObject("album").getAsJsonArray("images");
        else if (item.has("images") && item.get("images").isJsonArray()) imgs = item.getAsJsonArray("images");
        String cover = "";
        int best = Integer.MAX_VALUE;
        if (imgs != null) {
            for (JsonElement e : imgs) { // smallest image that is at least 200px wide
                JsonObject im = e.getAsJsonObject();
                int w = im.has("width") && !im.get("width").isJsonNull() ? im.get("width").getAsInt() : 0;
                if (w >= 200 && w < best) { best = w; cover = str(im, "url"); }
            }
            if (cover.isEmpty() && imgs.size() > 0) cover = str(imgs.get(0).getAsJsonObject(), "url");
        }
        snap = new Snapshot(Status.OK, "", str(item, "name"), artist, cover,
                lng(item, "duration_ms"), lng(o, "progress_ms"),
                o.has("is_playing") && o.get("is_playing").getAsBoolean(), System.nanoTime());
    }

    // --------------------------------------------------------------- controls

    public static void togglePlay() {
        Snapshot s = snap;
        if (s.status() == Status.OK) {
            snap = new Snapshot(Status.OK, "", s.title(), s.artist(), s.coverUrl(), s.durationMs(),
                    progressNow(s), !s.playing(), System.nanoTime());
        }
        command("PUT", s.status() == Status.OK && s.playing() ? "/me/player/pause" : "/me/player/play");
    }

    public static void next() { command("POST", "/me/player/next"); }

    public static void previous() { command("POST", "/me/player/previous"); }

    private static void command(String method, String path) {
        EX.execute(() -> {
            try {
                if (!ensureToken()) { SpotifyHudMod.msg("Spotify isn't connected."); return; }
                HttpResponse<String> r = api(method, path);
                int code = r.statusCode();
                if (code == 403) throttledMsg("Spotify refused: playback control needs Premium.");
                else if (code == 404) throttledMsg("No active Spotify device - start playing in the Spotify app first.");
                else if (code == 401) expiresAtMs = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                logOnce(t.toString());
            }
            EX.schedule(Spotify::poll, 500, TimeUnit.MILLISECONDS);
        });
    }

    // ---------------------------------------------------------------- helpers

    private static HttpResponse<String> api(String method, String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + accessToken)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void throttledMsg(String m) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > 5000) { lastWarnMs = now; SpotifyHudMod.msg(m); }
    }

    private static void logOnce(String m) {
        if (!m.equals(lastLogged)) { lastLogged = m; SpotifyHudMod.LOG.warn("Spotify: {}", m); }
    }

    private static Path tokenPath() { return FabricLoader.getInstance().getConfigDir().resolve("spotifyhud-tokens.json"); }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

    private static long lng(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L; }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
