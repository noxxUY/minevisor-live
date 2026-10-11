package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LiveServer {

    private final LiveConfig config;
    private final VirtualFiles files;
    private final TokenStore tokens;
    private final EventHub events = new EventHub();
    private final Supplier<JsonObject> info;
    private final Logger log;
    private final AtomicInteger viewers = new AtomicInteger();
    private HttpServer http;
    private ExecutorService pool;

    public LiveServer(LiveConfig config, VirtualFiles files, TokenStore tokens, Supplier<JsonObject> info, Logger log) {
        this.config = config;
        this.files = files;
        this.tokens = tokens;
        this.info = info;
        this.log = log;
    }

    public EventHub events() {
        return events;
    }

    private volatile EntityFeed entities;

    /** Lets viewers poll the live entities around their camera (see EntityFeed). */
    public void entities(EntityFeed.Platform platform) {
        this.entities = new EntityFeed(platform);
    }

    public void start() throws IOException {
        http = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "minevisor-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        http.createContext("/api/", this::handle);
        http.createContext("/", this::serveWeb);
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    public record ApiResponse(int status, String type, byte[] body) {
        static ApiResponse text(int status, String type, String body) {
            return new ApiResponse(status, type + "; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        }
    }

    public ApiResponse api(String path, Map<String, String> query, boolean trusted) throws IOException {
        boolean linked = trusted || tokens.check(query.get("t"));
        if (!linked && !config.publicRead()) {
            return ApiResponse.text(401, "application/json", "{\"error\":\"token required, run /minevisor link in game\"}");
        }
        switch (path) {
            case "/api/info" -> {
                JsonObject body = info.get();
                body.addProperty("viewer", linked ? "link" : null);
                return ApiResponse.text(200, "application/json", body.toString());
            }
            case "/api/fs/list" -> {
                List<String> names = files.list(query.getOrDefault("dir", ""), "dir".equals(query.get("kind")));
                JsonArray arr = new JsonArray();
                names.forEach(arr::add);
                return ApiResponse.text(200, "application/json", arr.toString());
            }
            case "/api/fs/read" -> {
                byte[] bytes = files.read(query.get("path"));
                return bytes == null ? ApiResponse.text(404, "text/plain", "not found")
                        : new ApiResponse(200, "application/octet-stream", bytes);
            }
            case "/api/fs/head" -> {
                byte[] bytes = files.regionHeader(query.get("path"));
                return bytes == null ? ApiResponse.text(404, "text/plain", "not found")
                        : new ApiResponse(200, "application/octet-stream", bytes);
            }
            case "/api/fs/chunk" -> {
                int index;
                try {
                    index = Integer.parseInt(query.getOrDefault("i", "-1"));
                } catch (NumberFormatException e) {
                    index = -1;
                }
                byte[] bytes = files.regionChunk(query.get("path"), index);
                if (bytes == null) return ApiResponse.text(404, "text/plain", "not found");
                if (bytes.length == 0) return new ApiResponse(204, "text/plain", new byte[0]);
                return new ApiResponse(200, "application/octet-stream", bytes);
            }
            case "/api/fs/bundle" -> {
                return new ApiResponse(200, "application/octet-stream", bundle());
            }
            case "/api/fs/chunks" -> {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream data = new java.io.DataOutputStream(out);
                String regionPath = query.get("path");
                String[] wanted = query.getOrDefault("i", "").split(",");
                if (wanted.length > 1024) return ApiResponse.text(400, "text/plain", "too many");
                for (String w : wanted) {
                    byte[] chunk = null;
                    try {
                        chunk = files.regionChunk(regionPath, Integer.parseInt(w.trim()));
                    } catch (NumberFormatException e) {
                    }
                    if (chunk == null || chunk.length == 0) {
                        data.writeInt(-1);
                    } else {
                        data.writeInt(chunk.length);
                        data.write(chunk);
                    }
                }
                return new ApiResponse(200, "application/octet-stream", out.toByteArray());
            }
            case "/api/fs/heads" -> {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                java.io.DataOutputStream data = new java.io.DataOutputStream(out);
                String[] paths = query.getOrDefault("paths", "").split(",");
                if (paths.length > 512) return ApiResponse.text(400, "text/plain", "too many");
                for (String p : paths) {
                    byte[] head = files.regionHeader(p);
                    if (head == null) {
                        data.writeInt(-1);
                    } else {
                        data.writeInt(head.length);
                        data.write(head);
                    }
                }
                return new ApiResponse(200, "application/octet-stream", out.toByteArray());
            }
            case "/api/entities" -> {
                EntityFeed feed = entities;
                if (feed == null) return ApiResponse.text(404, "text/plain", "not supported");
                try {
                    String dim = query.getOrDefault("dim", "overworld");
                    double x = Double.parseDouble(query.getOrDefault("x", "0"));
                    double z = Double.parseDouble(query.getOrDefault("z", "0"));
                    double r = Double.parseDouble(query.getOrDefault("r", "96"));
                    long since = Long.parseLong(query.getOrDefault("since", "0"));
                    return ApiResponse.text(200, "application/json", feed.query(dim, x, z, r, since).toString());
                } catch (NumberFormatException e) {
                    return ApiResponse.text(400, "text/plain", "bad number");
                } catch (Exception e) {
                    return ApiResponse.text(503, "text/plain", "server busy");
                }
            }
            case "/api/map/surface" -> {
                String regionPath = query.get("path");
                byte[] region = files.read(regionPath);
                if (region == null) return ApiResponse.text(404, "text/plain", "not found");
                try {
                    return new ApiResponse(200, "application/octet-stream", RegionSurface.of(regionPath, region));
                } catch (RegionSurface.Unsupported e) {
                    return ApiResponse.text(409, "text/plain", e.getMessage());
                }
            }
            default -> {
                return ApiResponse.text(404, "text/plain", "not found");
            }
        }
    }

    private static final String[] BUNDLE_LISTS = {
            "players", "players/data", "players/stats", "players/advancements", "playerdata", "stats", "advancements",
            "region", "entities", "DIM-1/region", "DIM-1/entities", "DIM1/region", "DIM1/entities",
            "dimensions/minecraft/overworld/region", "dimensions/minecraft/overworld/entities",
            "dimensions/minecraft/the_nether/region", "dimensions/minecraft/the_nether/entities",
            "dimensions/minecraft/the_end/region", "dimensions/minecraft/the_end/entities",
    };
    private static final String[] BUNDLE_FILES = {
            "players/data", "players/stats", "players/advancements", "playerdata", "stats", "advancements",
    };
    private static final int BUNDLE_MAX_BYTES = 8 * 1024 * 1024;

    private byte[] bundle() throws IOException {
        JsonObject lists = new JsonObject();
        JsonArray index = new JsonArray();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.util.function.BiConsumer<String, byte[]> add = (path, bytes) -> {
            if (bytes == null || body.size() + bytes.length > BUNDLE_MAX_BYTES) return;
            JsonArray entry = new JsonArray();
            entry.add(path);
            entry.add(bytes.length);
            index.add(entry);
            body.writeBytes(bytes);
        };
        add.accept("level.dat", files.read("level.dat"));
        add.accept("usercache.json", files.read("usercache.json"));
        for (String dir : BUNDLE_LISTS) {
            JsonArray names = new JsonArray();
            files.list(dir, false).forEach(names::add);
            lists.add(dir + "|file", names);
        }
        for (String dir : new String[]{"", "dimensions", "dimensions/minecraft", "players", "data"}) {
            JsonArray names = new JsonArray();
            files.list(dir, true).forEach(names::add);
            lists.add(dir + "|dir", names);
        }
        for (String dir : BUNDLE_FILES) {
            for (String name : files.list(dir, false)) add.accept(dir + "/" + name, files.read(dir + "/" + name));
        }
        JsonObject head = new JsonObject();
        head.add("files", index);
        head.add("lists", lists);
        byte[] json = head.toString().getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(4 + json.length + body.size());
        new java.io.DataOutputStream(out).writeInt(json.length);
        out.writeBytes(json);
        body.writeTo(out);
        return out.toByteArray();
    }

    private void handle(HttpExchange ex) {
        try {
            cors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, "text/plain", "method not allowed");
                return;
            }

            Map<String, String> query = query(ex.getRequestURI().getRawQuery());
            String path = ex.getRequestURI().getPath();
            if (path.equals("/api/events")) {
                if (!tokens.check(query.get("t")) && !config.publicRead()) {
                    send(ex, 401, "application/json", "{\"error\":\"token required, run /minevisor link in game\"}");
                    return;
                }
                serveEvents(ex);
                return;
            }
            ApiResponse res = api(path, query, false);
            if (res.status() == 204) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            sendBytes(ex, res.status(), res.type(), res.body());
        } catch (IOException e) {
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "minevisor request failed", e);
        } finally {
            ex.close();
        }
    }

    private static final String WEB_ROOT = "minevisor-web/";
    private static final java.util.regex.Pattern SAFE_PATH = java.util.regex.Pattern.compile("[A-Za-z0-9_./-]*");
    private static final java.util.regex.Pattern MOJANG_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,36}");
    private final java.net.http.HttpClient mojang = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8)).build();

    private static String contentType(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    private void serveWeb(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
                send(ex, 405, "text/plain", "method not allowed");
                return;
            }
            if (path.startsWith("/mojang-profile/") || path.startsWith("/mojang-user/")) {
                proxyMojang(ex, path);
                return;
            }
            if (path.equals("/") || path.equals("/workspace")) {
                ex.getResponseHeaders().set("Location", "/workspace/" + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
                ex.sendResponseHeaders(302, -1);
                return;
            }
            if (!SAFE_PATH.matcher(path).matches() || path.contains("..")) {
                send(ex, 404, "text/plain", "not found");
                return;
            }
            String resource = WEB_ROOT + path.substring(1) + (path.endsWith("/") ? "index.html" : "");
            byte[] bytes;
            try (var in = LiveServer.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    send(ex, 404, "text/plain", path.startsWith("/workspace")
                            ? "This build has no bundled workspace page." : "not found");
                    return;
                }
                bytes = in.readAllBytes();
            }
            ex.getResponseHeaders().set("Content-Type", contentType(resource));
            ex.getResponseHeaders().set("Cache-Control", path.startsWith("/_astro/") ? "public, max-age=31536000, immutable" : "no-cache");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException e) {
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "minevisor web request failed", e);
        } finally {
            ex.close();
        }
    }

    private void proxyMojang(HttpExchange ex, String path) throws IOException {
        boolean profile = path.startsWith("/mojang-profile/");
        String id = path.substring(profile ? "/mojang-profile/".length() : "/mojang-user/".length());
        if (!MOJANG_ID.matcher(id).matches()) {
            send(ex, 400, "text/plain", "bad id");
            return;
        }
        String target = profile ? "https://sessionserver.mojang.com/session/minecraft/profile/" + id
                : "https://api.mojang.com/users/profiles/minecraft/" + id;
        try {
            var res = mojang.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(target))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
            byte[] body = res.body();
            ex.sendResponseHeaders(res.statusCode(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) ex.getResponseBody().write(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 502, "text/plain", "mojang unreachable");
        } catch (IOException e) {
            send(ex, 502, "text/plain", "mojang unreachable");
        }
    }

    private void serveEvents(HttpExchange ex) throws IOException {
        if (viewers.incrementAndGet() > config.maxViewers()) {
            viewers.decrementAndGet();
            send(ex, 503, "text/plain", "too many viewers");
            return;
        }
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            events.serve(out);
        } finally {
            viewers.decrementAndGet();
        }
    }

    private void cors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", config.allowedOrigin());
        h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        h.set("Access-Control-Allow-Headers", "Content-Type");
        h.set("Access-Control-Allow-Private-Network", "true");
        h.set("Vary", "Origin");
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        sendBytes(ex, status, type + "; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Timing-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }
}
