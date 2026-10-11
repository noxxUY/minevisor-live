package cc.minevisor.live;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RelayClient implements EventHub.Remote {

    private static final int PART_BYTES = 512 * 1024;
    private static final long PLAYERS_GAP_MS = 200;
    private static final long PING_SECONDS = 30;

    private final String relayUrl;
    private final LiveServer server;
    private final TokenStore tokens;
    private final boolean publicRead;
    private final Path identityFile;
    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(daemon("minevisor-relay-send"));
    private final ExecutorService workers = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)), daemon("minevisor-relay-req"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("minevisor-relay"));

    private String serverId;
    private String key;
    private volatile WebSocket socket;
    private volatile int viewers;
    private volatile boolean stopped;
    private long backoffMs = 2000;
    private ScheduledFuture<?> ping;
    private long lastPlayersAt;
    private String pendingPlayers;
    private boolean playersQueued;

    public RelayClient(String relayUrl, LiveServer server, TokenStore tokens, boolean publicRead, Path identityFile, Logger log) {
        this.relayUrl = relayUrl.replaceAll("/+$", "");
        this.server = server;
        this.tokens = tokens;
        this.publicRead = publicRead;
        this.identityFile = identityFile;
        this.log = log;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    public void start() {
        loadIdentity();
        server.events().setRemote(this);
        connect();
    }

    public void stop() {
        stopped = true;
        server.events().setRemote(null);
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "server stopping");
        timer.shutdownNow();
        workers.shutdownNow();
        sender.shutdown();
    }

    public String serverId() {
        return serverId;
    }

    public boolean connected() {
        return socket != null;
    }

    @Override
    public int viewers() {
        return socket == null ? 0 : viewers;
    }

    private void loadIdentity() {
        try {
            if (Files.exists(identityFile)) {
                JsonObject o = JsonParser.parseString(Files.readString(identityFile)).getAsJsonObject();
                serverId = o.get("server").getAsString();
                key = o.get("key").getAsString();
                if (serverId.matches("[A-Za-z0-9]{8,32}") && key.length() >= 32) return;
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Couldn't read " + identityFile.getFileName() + ", making a new relay identity");
        }
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 12; i++) id.append(alphabet.charAt(random.nextInt(alphabet.length())));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        serverId = id.toString();
        key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        JsonObject o = new JsonObject();
        o.addProperty("server", serverId);
        o.addProperty("key", key);
        try {
            Files.createDirectories(identityFile.getParent());
            Path tmp = identityFile.resolveSibling(identityFile.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString());
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
            }
            Files.move(tmp, identityFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warning("Couldn't save the relay identity; links through minevisor.cc change on every restart");
        }
    }

    private void connect() {
        if (stopped) return;
        URI uri = URI.create(relayUrl + "/publish?server=" + serverId + "&key="
                + URLEncoder.encode(key, StandardCharsets.UTF_8));
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(uri, new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        if (cause instanceof WebSocketHandshakeException h && h.getResponse().statusCode() == 403) {
                            log.warning("The relay refused this server's id (it belongs to another key). "
                                    + "Delete " + identityFile.getFileName() + " to get a new one.");
                            retry(10 * 60_000);
                        } else {
                            retry(backoffMs);
                            backoffMs = Math.min(60_000, backoffMs * 2);
                        }
                        return;
                    }
                    backoffMs = 2000;
                    log.info("Connected to the minevisor relay");
                    ping = timer.scheduleAtFixedRate(() -> send(() -> socket.sendText("ping", true)),
                            PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
                });
    }

    private void retry(long delayMs) {
        if (stopped) return;
        try {
            timer.schedule(this::connect, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
        }
    }

    private void lost() {
        if (socket == null) return;
        socket = null;
        viewers = 0;
        if (ping != null) ping.cancel(false);
        if (!stopped) {
            log.info("Lost the minevisor relay, reconnecting");
            retry(backoffMs);
        }
    }

    private void send(java.util.function.Supplier<CompletableFuture<WebSocket>> action) {
        try {
            sender.execute(() -> {
                if (socket == null) return;
                try {
                    action.get().get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.log(Level.FINE, "relay send failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
        }
    }

    private void sendJson(JsonObject o) {
        String text = o.toString();
        send(() -> socket.sendText(text, true));
    }

    public void reauth() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "reauth");
        sendJson(o);
    }

    @Override
    public void event(String event, String json) {
        if (socket == null || viewers == 0) return;
        if (event.equals("players")) {
            synchronized (this) {
                pendingPlayers = json;
                long wait = lastPlayersAt + PLAYERS_GAP_MS - System.currentTimeMillis();
                if (playersQueued) return;
                if (wait > 0) {
                    playersQueued = true;
                    timer.schedule(this::flushPlayers, wait, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            flushPlayers();
            return;
        }
        sendEvent(event, json, null);
    }

    private void flushPlayers() {
        String json;
        synchronized (this) {
            playersQueued = false;
            json = pendingPlayers;
            pendingPlayers = null;
            lastPlayersAt = System.currentTimeMillis();
        }
        if (json != null) sendEvent("players", json, null);
    }

    private void sendEvent(String event, String json, String to) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "event");
        o.addProperty("event", event);
        o.addProperty("data", json);
        if (to != null) o.addProperty("to", to);
        sendJson(o);
    }

    private void onMessage(String text) {
        if (text.equals("pong")) return;
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        switch (type) {
            case "hello" -> {
                String viewer = msg.get("viewer").getAsString();
                String token = msg.has("t") ? msg.get("t").getAsString() : "";
                boolean ok = publicRead || tokens.check(token);
                JsonObject auth = new JsonObject();
                auth.addProperty("type", "auth");
                auth.addProperty("viewer", viewer);
                auth.addProperty("ok", ok);
                sendJson(auth);
                if (ok) server.events().latestStates().forEach((event, json) -> sendEvent(event, json, viewer));
            }
            case "viewers" -> viewers = msg.get("count").getAsInt();
            case "req" -> {
                String viewer = msg.get("viewer").getAsString();
                int id = msg.get("id").getAsInt();
                String path = msg.get("path").getAsString();
                Map<String, String> query = new HashMap<>();
                if (msg.has("query") && msg.get("query").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : msg.getAsJsonObject("query").entrySet()) {
                        query.put(e.getKey(), e.getValue().getAsString());
                    }
                }
                try {
                    workers.execute(() -> answer(viewer, id, path, query));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                }
            }
            default -> {
            }
        }
    }

    private void answer(String viewer, int id, String path, Map<String, String> query) {
        LiveServer.ApiResponse res;
        try {
            res = server.api(path, query, true);
        } catch (IOException | RuntimeException e) {
            res = LiveServer.ApiResponse.text(500, "text/plain", "couldn't read that");
        }
        byte[] body = res.body();
        int parts = Math.max(1, (body.length + PART_BYTES - 1) / PART_BYTES);
        for (int part = 0; part < parts; part++) {
            JsonObject header = new JsonObject();
            header.addProperty("viewer", viewer);
            header.addProperty("id", id);
            header.addProperty("status", res.status());
            header.addProperty("type", res.type());
            header.addProperty("part", part);
            header.addProperty("parts", parts);
            byte[] head = header.toString().getBytes(StandardCharsets.UTF_8);
            int from = part * PART_BYTES;
            int length = Math.min(PART_BYTES, body.length - from);
            ByteBuffer frame = ByteBuffer.allocate(4 + head.length + Math.max(0, length));
            frame.putInt(head.length).put(head);
            if (length > 0) frame.put(body, from, length);
            frame.flip();
            send(() -> socket.sendBinary(frame, true));
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String message = text.toString();
                text.setLength(0);
                onMessage(message);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            lost();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            lost();
        }
    }
}
