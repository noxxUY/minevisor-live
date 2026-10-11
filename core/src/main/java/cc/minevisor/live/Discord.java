package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Discord {

    private static final int GREEN = 0x5ad67a;
    private static final int RED = 0xe06c6c;
    private static final int GREY = 0x8a8f98;

    private final DiscordConfig config;
    private final Supplier<JsonObject> info;
    private final WorldView world;
    private final ActivityTracker activity;
    private final BannerRenderer banners;
    private volatile byte[] bannerPng;
    private volatile long bannerAt;
    private volatile String bannerPlayers = "";
    private final Path dataDir;
    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "minevisor-discord");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean stopping;
    private volatile WebSocket gateway;
    private volatile Integer sequence;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> statusLoop;
    private String statusMessageId;
    private volatile String appId;
    private final java.util.Map<String, String> headEmojis = new java.util.concurrent.ConcurrentHashMap<>();
    private ScheduledFuture<?> headLoop;

    public Discord(DiscordConfig config, Supplier<JsonObject> info, Path dataDir, Logger log) {
        this(config, info, null, dataDir, log);
    }

    public Discord(DiscordConfig config, Supplier<JsonObject> info, WorldView world, Path dataDir, Logger log) {
        this.config = config;
        this.info = info;
        this.world = world;
        this.dataDir = dataDir;
        this.log = log;
        this.activity = new ActivityTracker(dataDir.resolve("activity.json"));
        this.banners = new BannerRenderer(http);
    }

    public boolean enabled() {
        return config.webhookEnabled() || config.botEnabled();
    }

    public void start() {
        start(true);
    }

    public void start(boolean announce) {
        if (config.webhookEnabled() && announce) announce(serverEmbed(true));
        if (config.botEnabled()) worker.execute(this::startBot);
        if (config.botEnabled() && world != null) {
            worker.scheduleWithFixedDelay(() -> activity.record(players(info.get())), 30, 30, TimeUnit.SECONDS);
            worker.scheduleWithFixedDelay(activity::save, 300, 300, TimeUnit.SECONDS);
        }
    }

    public void stopQuietly() {
        stopping = true;
        activity.save();
        WebSocket ws = gateway;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "reconfigured");
        worker.shutdownNow();
        bannerThread.shutdownNow();
    }

    public void stop() {
        stopping = true;
        activity.save();
        CompletableFuture<Void> last = CompletableFuture.runAsync(() -> {
            if (config.webhookEnabled()) postWebhook(serverEmbed(false));
            if (config.statusEnabled()) updateStatus(false);
        }, worker);
        try {
            last.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
        }
        WebSocket ws = gateway;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        worker.shutdownNow();
        bannerThread.shutdownNow();
    }

    public void playerJoined(String name) {
        if (!config.webhookEnabled() || !config.announceJoins()) return;
        JsonObject i = info.get();
        int online = onlineCount(i, name, true);
        JsonObject e = playerEmbed(name + " joined the game", name, GREEN, i);
        e.addProperty("description", "**" + online + " / " + maxPlayers(i) + "** online");
        announce(e);
    }

    public void playerLeft(String name) {
        if (!config.webhookEnabled() || !config.announceJoins()) return;
        JsonObject i = info.get();
        int online = onlineCount(i, name, false);
        JsonObject e = playerEmbed(name + " left the game", name, GREY, i);
        e.addProperty("description", online == 0 ? "Nobody is online now" : "**" + online + " / " + maxPlayers(i) + "** online");
        announce(e);
    }

    public void playerDied(String name, String message, String dimension, int x, int y, int z) {
        if (!config.webhookEnabled() || !config.announceDeaths()) return;
        String text = message == null || message.isBlank() ? name + " died" : message;
        JsonObject e = playerEmbed(text, name, RED, info.get());
        if (config.deathLocation()) {
            JsonArray fields = new JsonArray();
            fields.add(field("Dimension", dimensionLabel(dimension)));
            fields.add(field("Coordinates", "`" + x + " " + y + " " + z + "`"));
            e.add("fields", fields);
        }
        announce(e);
    }

    private void announce(JsonObject embed) {
        worker.execute(() -> postWebhook(embed));
    }

    private void postWebhook(JsonObject embed) {
        JsonObject body = new JsonObject();
        body.addProperty("username", "minevisor");
        body.addProperty("avatar_url", ICON);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        send("POST", config.webhookUrl(), body, false);
    }

    private static JsonObject embed(String title, String description, int color) {
        JsonObject e = new JsonObject();
        e.addProperty("title", title);
        if (description != null) e.addProperty("description", description);
        e.addProperty("color", color);
        return e;
    }

    private static final String ICON = "https://minevisor.cc/icon-192.png";

    private static String head(String name) {
        return "https://mc-heads.net/avatar/" + name + "/64";
    }

    private static JsonObject footer(JsonObject info) {
        JsonObject f = new JsonObject();
        f.addProperty("text", "minevisor live");
        return f;
    }

    private static JsonObject stamped(JsonObject e, JsonObject info) {
        e.add("footer", footer(info));
        e.addProperty("timestamp", Instant.now().toString());
        return e;
    }

    private static JsonObject playerEmbed(String text, String name, int color, JsonObject info) {
        JsonObject e = new JsonObject();
        JsonObject author = new JsonObject();
        author.addProperty("name", text.length() > 250 ? text.substring(0, 250) + "…" : text);
        author.addProperty("icon_url", head(name));
        e.add("author", author);
        e.addProperty("color", color);
        return stamped(e, info);
    }

    private static int maxPlayers(JsonObject info) {
        return info.has("maxPlayers") ? info.get("maxPlayers").getAsInt() : 0;
    }

    private int onlineCount(JsonObject info, String name, boolean joined) {
        int count = 0;
        boolean listed = false;
        for (JsonElement p : players(info)) {
            count++;
            if (p.getAsJsonObject().get("name").getAsString().equals(name)) listed = true;
        }
        if (joined && !listed) count++;
        if (!joined && listed) count--;
        return Math.max(0, count);
    }

    private static String dimensionLabel(String dimension) {
        if (dimension == null) return "Unknown";
        return switch (dimension) {
            case "Nether" -> "Nether";
            case "The End" -> "The End";
            default -> "Overworld";
        };
    }

    private static String plainMotd(JsonObject info) {
        if (!info.has("motd") || info.get("motd").isJsonNull()) return null;
        String motd = info.get("motd").getAsString().replaceAll("§.", "").trim();
        return motd.isEmpty() || motd.equals("A Minecraft Server") ? null : motd;
    }

    private JsonObject serverEmbed(boolean online) {
        JsonObject i = info.get();
        String who = config.hasName() ? config.serverName() : "Server";
        JsonObject e = embed(who + (online ? " is online" : " is offline"),
                online && config.showMotd() ? plainMotd(i) : null, online ? GREEN : RED);
        if (online) {
            JsonArray fields = new JsonArray();
            if (i.has("version")) fields.add(field("Version", i.get("version").getAsString()));
            fields.add(field("Slots", String.valueOf(maxPlayers(i))));
            e.add("fields", fields);
        }
        return stamped(e, i);
    }

    public static JsonObject connectedEmbed() {
        JsonObject e = embed("minevisor live is connected",
                "This channel will get **server start/stop**, **joins**, **leaves** and **deaths**.", GREEN);
        JsonObject f = new JsonObject();
        f.addProperty("text", "minevisor live");
        e.add("footer", f);
        e.addProperty("timestamp", Instant.now().toString());
        return e;
    }

    private HttpResponse<String> send(String method, String url, JsonElement body, boolean bot) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "DiscordBot (https://minevisor.cc, 0.1)")
                        .header("Content-Type", "application/json")
                        .method(method, body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
                if (bot) req.header("Authorization", "Bot " + config.botToken());
                HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 429 && attempt == 0) {
                    double wait = 1;
                    try {
                        wait = JsonParser.parseString(res.body()).getAsJsonObject().get("retry_after").getAsDouble();
                    } catch (RuntimeException ignored) {
                    }
                    Thread.sleep((long) Math.min(30_000, wait * 1000) + 50);
                    continue;
                }
                if (res.statusCode() >= 400 && res.statusCode() != 404) {
                    log.warning("Discord " + method + " " + safe(url) + " -> " + res.statusCode() + " " + res.body());
                }
                return res;
            } catch (IOException e) {
                log.warning("Discord unreachable: " + e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static final String BANNER_FILE = "banner.png";

    private HttpResponse<String> sendWithBanner(String method, String url, JsonObject payload, byte[] png, boolean bot) {
        if (png == null) return send(method, url, payload, bot);
        JsonArray attachments = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("id", 0);
        a.addProperty("filename", BANNER_FILE);
        attachments.add(a);
        JsonObject target = payload.has("data") ? payload.getAsJsonObject("data") : payload;
        target.add("attachments", attachments);

        String boundary = "minevisor" + Long.toHexString(System.nanoTime());
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        try {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\""
                    + BANNER_FILE + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(png);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return send(method, url, payload, bot);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "DiscordBot (https://minevisor.cc, 0.1)")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
                if (bot) req.header("Authorization", "Bot " + config.botToken());
                HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 429 && attempt == 0) {
                    Thread.sleep(1500);
                    continue;
                }
                if (res.statusCode() >= 400 && res.statusCode() != 404) {
                    log.warning("Discord " + method + " " + safe(url) + " -> " + res.statusCode() + " " + res.body());
                }
                return res;
            } catch (IOException e) {
                log.warning("Discord unreachable: " + e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private final java.util.concurrent.ExecutorService bannerThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minevisor-banner");
        t.setDaemon(true);
        return t;
    });

    private byte[] bannerWithin(long timeoutMs) {
        if (!autoBanner()) return null;
        java.util.concurrent.Future<byte[]> f = bannerThread.submit(this::banner);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return bannerPng;
        } catch (Exception e) {
            return bannerPng;
        }
    }

    private static String bannerKey(JsonArray players) {
        StringBuilder key = new StringBuilder();
        for (JsonElement el : players) {
            JsonObject p = el.getAsJsonObject();
            key.append(p.get("name").getAsString()).append('@')
                    .append(p.has("x") ? (int) Math.floor(p.get("x").getAsDouble() / 64) : 0).append(',')
                    .append(p.has("z") ? (int) Math.floor(p.get("z").getAsDouble() / 64) : 0).append(';');
        }
        return key.toString();
    }

    private boolean autoBanner() {
        String b = config.bannerUrl();
        return world != null && (b == null || b.isBlank() || b.equalsIgnoreCase("auto"));
    }

    private synchronized byte[] banner() {
        if (!autoBanner()) return null;
        long now = System.currentTimeMillis();
        JsonObject i = info.get();
        String who = bannerKey(players(i));
        if (bannerPng != null && now - bannerAt < 45_000 && who.equals(bannerPlayers)) return bannerPng;
        try {
            byte[] png = banners.render(world, i, activity.busiest(), config);
            bannerPng = png;
            bannerAt = now;
            bannerPlayers = who;
            return png;
        } catch (Exception e) {
            log.fine("Couldn't draw the banner: " + e);
            return bannerPng;
        }
    }

    private static String safe(String url) {
        int i = url.indexOf("/webhooks/");
        return i < 0 ? url : url.substring(0, i) + "/webhooks/…";
    }

    private String api(String path) {
        String base = config.apiBase() == null || config.apiBase().isBlank() ? DiscordConfig.DEFAULT_API : config.apiBase();
        return base + path;
    }

    private void startBot() {
        HttpResponse<String> me = send("GET", api("/applications/@me"), null, true);
        if (me == null || me.statusCode() != 200) {
            log.warning("Discord bot token rejected, check discord.bot-token.");
            return;
        }
        String appId = JsonParser.parseString(me.body()).getAsJsonObject().get("id").getAsString();
        this.appId = appId;
        loadHeadEmojis();
        headLoop = worker.scheduleWithFixedDelay(this::ensureHeads, 1, 5, TimeUnit.SECONDS);
        JsonArray commands = new JsonArray();
        commands.add(command("status", "Is the server up, and who is on it"));
        commands.add(command("players", "Who is online right now"));
        send("PUT", api("/applications/" + appId + "/commands"), commands, true);
        connectGateway();
        if (config.statusEnabled()) {
            statusMessageId = readStatusId();
            int every = Math.max(60, config.statusIntervalSeconds());
            statusLoop = worker.scheduleAtFixedRate(() -> updateStatus(true), 2, every, TimeUnit.SECONDS);
        }
    }

    private static JsonObject command(String name, String description) {
        JsonObject c = new JsonObject();
        c.addProperty("name", name);
        c.addProperty("description", description);
        c.addProperty("type", 1);
        return c;
    }

    private void connectGateway() {
        if (stopping) return;
        String url = config.gatewayUrl() == null || config.gatewayUrl().isBlank() ? DiscordConfig.DEFAULT_GATEWAY : config.gatewayUrl();
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), new GatewayListener())
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        log.warning("Discord gateway unreachable: " + error.getMessage());
                        reconnectLater();
                    } else {
                        gateway = ws;
                    }
                });
    }

    private void reconnectLater() {
        if (stopping || worker.isShutdown()) return;
        if (heartbeat != null) heartbeat.cancel(false);
        worker.schedule(this::connectGateway, 5, TimeUnit.SECONDS);
    }

    private void sendGateway(JsonObject payload) {
        WebSocket ws = gateway;
        if (ws != null && !ws.isOutputClosed()) ws.sendText(payload.toString(), true);
    }

    private final class GatewayListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            gateway = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                worker.execute(() -> handleGateway(text));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (statusCode == 4004) {
                log.warning("Discord rejected the bot token.");
                return null;
            }
            reconnectLater();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.log(Level.FINE, "Discord gateway error", error);
            reconnectLater();
        }
    }

    private void handleGateway(String text) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        if (msg.has("s") && !msg.get("s").isJsonNull()) sequence = msg.get("s").getAsInt();
        switch (msg.get("op").getAsInt()) {
            case 10 -> {
                long interval = msg.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
                if (heartbeat != null) heartbeat.cancel(false);
                heartbeat = worker.scheduleAtFixedRate(this::beat, (long) (interval * Math.random()), interval, TimeUnit.MILLISECONDS);
                JsonObject props = new JsonObject();
                props.addProperty("os", "java");
                props.addProperty("browser", "minevisor-live");
                props.addProperty("device", "minevisor-live");
                JsonObject d = new JsonObject();
                d.addProperty("token", config.botToken());
                d.addProperty("intents", 0);
                d.add("properties", props);
                JsonObject identify = new JsonObject();
                identify.addProperty("op", 2);
                identify.add("d", d);
                sendGateway(identify);
            }
            case 1 -> beat();
            case 7, 9 -> {
                WebSocket ws = gateway;
                if (ws != null) ws.abort();
                sequence = null;
                reconnectLater();
            }
            case 0 -> {
                if ("INTERACTION_CREATE".equals(msg.get("t").getAsString())) interaction(msg.getAsJsonObject("d"));
            }
            default -> {
            }
        }
    }

    private void beat() {
        JsonObject hb = new JsonObject();
        hb.addProperty("op", 1);
        Integer s = sequence;
        if (s == null) hb.add("d", null);
        else hb.addProperty("d", s);
        sendGateway(hb);
    }

    private void interaction(JsonObject d) {
        if (d.get("type").getAsInt() != 2) return;
        String name = d.getAsJsonObject("data").get("name").getAsString();
        ensureHeads(1_000);
        byte[] png = bannerWithin(1_500);
        JsonObject data = switch (name) {
            case "status" -> card(true, false, png != null);
            case "players" -> card(true, true, png != null);
            default -> {
                JsonObject d2 = new JsonObject();
                d2.addProperty("content", "Unknown command.");
                yield d2;
            }
        };
        JsonObject reply = new JsonObject();
        reply.addProperty("type", 4);
        reply.add("data", data);
        sendWithBanner("POST", api("/interactions/" + d.get("id").getAsString() + "/" + d.get("token").getAsString() + "/callback"),
                reply, "status".equals(name) || "players".equals(name) ? png : null, false);
    }

    private JsonArray players(JsonObject info) {
        return info.has("players") && info.get("players").isJsonArray() ? info.getAsJsonArray("players") : new JsonArray();
    }

    private static String escape(String name) {
        return name.replace("_", "\\_").replace("*", "\\*");
    }

    private static JsonObject field(String name, String value) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", true);
        return f;
    }

    private static final int IS_COMPONENTS_V2 = 1 << 15;
    private static final int MAX_LISTED = 15;

    private static JsonObject textDisplay(String content) {
        JsonObject t = new JsonObject();
        t.addProperty("type", 10);
        t.addProperty("content", content);
        return t;
    }

    private static JsonObject separator(boolean big) {
        JsonObject sep = new JsonObject();
        sep.addProperty("type", 14);
        sep.addProperty("divider", true);
        sep.addProperty("spacing", big ? 2 : 1);
        return sep;
    }

    private static JsonObject media(String url) {
        JsonObject m = new JsonObject();
        m.addProperty("url", url);
        return m;
    }

    JsonObject card(boolean online, boolean playersFirst) {
        return card(online, playersFirst, false);
    }

    JsonObject card(boolean online, boolean playersFirst, boolean attachedBanner) {
        JsonObject i = info.get();
        JsonArray players = players(i);
        JsonArray parts = new JsonArray();

        String bannerSource = attachedBanner ? "attachment://" + BANNER_FILE
                : config.bannerUrl() != null && config.bannerUrl().startsWith("http") ? config.bannerUrl() : null;
        if (bannerSource != null) {
            JsonObject gallery = new JsonObject();
            gallery.addProperty("type", 12);
            JsonArray items = new JsonArray();
            JsonObject item = new JsonObject();
            item.add("media", media(bannerSource));
            items.add(item);
            gallery.add("items", items);
            parts.add(gallery);
        }

        StringBuilder heading = new StringBuilder();
        if (playersFirst) {
            heading.append("## Players online");
        } else if (config.hasName()) {
            heading.append("## ").append(escape(config.serverName()));
        }
        String motd = config.showMotd() ? plainMotd(i) : null;
        if (motd != null && !playersFirst) heading.append(heading.isEmpty() ? "" : "\n").append(escape(motd));
        if (!heading.isEmpty()) {
            parts.add(textDisplay(heading.toString()));
            parts.add(separator(false));
        }

        StringBuilder stats = new StringBuilder("**Status:** ").append(online ? "Online" : "Offline");
        if (online) {
            stats.append("\n**Players:** ").append(players.size()).append(" / ").append(maxPlayers(i));
            if (i.has("version")) stats.append("\n**Version:** ").append(i.get("version").getAsString());
        }
        parts.add(textDisplay(stats.toString()));

        if (online) {
            parts.add(separator(false));
            if (players.isEmpty()) {
                parts.add(textDisplay("-# Nobody is online right now."));
            } else {
                StringBuilder list = new StringBuilder();
                int shown = 0;
                for (JsonElement el : players) {
                    if (shown == MAX_LISTED) {
                        list.append("-# …and ").append(players.size() - shown).append(" more\n");
                        break;
                    }
                    list.append(playerLine(el.getAsJsonObject())).append('\n');
                    shown++;
                }
                parts.add(textDisplay(list.toString().trim()));
            }
            parts.add(separator(true));
        }
        long now = Instant.now().getEpochSecond();
        parts.add(textDisplay("-# Last update: <t:" + now + ":R> · minevisor live"));

        JsonObject container = new JsonObject();
        container.addProperty("type", 17);
        container.addProperty("accent_color", online ? GREEN : RED);
        container.add("components", parts);
        JsonArray top = new JsonArray();
        top.add(container);

        JsonObject message = new JsonObject();
        message.addProperty("flags", IS_COMPONENTS_V2);
        message.add("components", top);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        message.add("allowed_mentions", mentions);
        return message;
    }

    private String playerLine(JsonObject p) {
        String name = p.get("name").getAsString();
        String emoji = headEmojis.get(emojiName(name));
        return (emoji != null ? "<:" + emojiName(name) + ":" + emoji + "> " : "• ") + "`" + name + "`";
    }

    private static final int MAX_APP_EMOJIS = 2000;

    private static String emojiName(String player) {
        String clean = player.replaceAll("[^A-Za-z0-9_]", "_");
        return ("mv_" + clean).substring(0, Math.min(32, 3 + clean.length()));
    }

    private void loadHeadEmojis() {
        HttpResponse<String> res = send("GET", api("/applications/" + appId + "/emojis"), null, true);
        if (res == null || res.statusCode() != 200) return;
        try {
            JsonObject body = JsonParser.parseString(res.body()).getAsJsonObject();
            for (JsonElement e : body.getAsJsonArray("items")) {
                JsonObject emoji = e.getAsJsonObject();
                String n = emoji.get("name").getAsString();
                if (n.startsWith("mv_")) headEmojis.put(n, emoji.get("id").getAsString());
            }
        } catch (RuntimeException e) {
            log.fine("Couldn't read app emojis: " + e.getMessage());
        }
    }

    private void ensureHeads() {
        ensureHeads(10_000);
    }

    private void ensureHeads(long budgetMs) {
        if (appId == null || stopping) return;
        long deadline = System.currentTimeMillis() + budgetMs;
        JsonArray players = players(info.get());
        java.util.Set<String> online = new java.util.HashSet<>();
        for (JsonElement el : players) online.add(emojiName(el.getAsJsonObject().get("name").getAsString()));
        for (JsonElement el : players) {
            String player = el.getAsJsonObject().get("name").getAsString();
            String key = emojiName(player);
            if (headEmojis.containsKey(key)) continue;
            if (System.currentTimeMillis() > deadline) return;
            if (headEmojis.size() >= MAX_APP_EMOJIS - 10) evictHead(online);
            uploadHead(player, key);
        }
    }

    private void evictHead(java.util.Set<String> keep) {
        for (var e : headEmojis.entrySet()) {
            if (keep.contains(e.getKey())) continue;
            send("DELETE", api("/applications/" + appId + "/emojis/" + e.getValue()), null, true);
            headEmojis.remove(e.getKey());
            return;
        }
    }

    private void uploadHead(String player, String key) {
        try {
            HttpResponse<byte[]> img = http.send(HttpRequest.newBuilder(URI.create("https://mc-heads.net/avatar/" + player + "/64.png"))
                    .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (img.statusCode() != 200 || img.body().length == 0) return;
            JsonObject body = new JsonObject();
            body.addProperty("name", key);
            body.addProperty("image", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(img.body()));
            HttpResponse<String> res = send("POST", api("/applications/" + appId + "/emojis"), body, true);
            if (res != null && (res.statusCode() == 200 || res.statusCode() == 201)) {
                headEmojis.put(key, JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsString());
            }
        } catch (IOException e) {
            log.fine("Couldn't fetch the head of " + player + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateStatus(boolean online) {
        if (online) ensureHeads(5_000);
        byte[] png = online ? banner() : null;
        JsonObject body = card(online, false, png != null);
        String channel = api("/channels/" + config.statusChannelId() + "/messages");
        if (statusMessageId != null) {
            HttpResponse<String> res = sendWithBanner("PATCH", channel + "/" + statusMessageId, body, png, true);
            if (res != null && res.statusCode() == 200) return;
            if (res == null || res.statusCode() >= 500 || res.statusCode() == 429) return;
            statusMessageId = null;
        }
        HttpResponse<String> res = sendWithBanner("POST", channel, body, png, true);
        if (res != null && res.statusCode() == 200) {
            statusMessageId = JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsString();
            writeStatusId(statusMessageId);
        }
    }

    private Path statusFile() {
        return dataDir.resolve("discord-status-message.txt");
    }

    private String readStatusId() {
        try {
            String[] saved = Files.readString(statusFile()).trim().split(":");
            if (saved.length == 2 && saved[0].equals(config.statusChannelId()) && saved[1].matches("\\d+")) return saved[1];
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeStatusId(String id) {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(statusFile(), config.statusChannelId() + ":" + id);
        } catch (IOException e) {
            log.warning("Couldn't remember the Discord status message: " + e.getMessage());
        }
    }
}
