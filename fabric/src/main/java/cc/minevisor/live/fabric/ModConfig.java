package cc.minevisor.live.fabric;

import cc.minevisor.live.DiscordConfig;
import cc.minevisor.live.LiveConfig;
import cc.minevisor.live.LiveJson;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ModConfig() {
    }

    private static JsonObject defaults() {
        JsonObject http = new JsonObject();
        http.addProperty("bind", "0.0.0.0");
        http.addProperty("port", 8100);
        http.addProperty("public-read", false);
        http.addProperty("allowed-origin", "*");
        http.addProperty("max-viewers", 32);

        JsonObject root = new JsonObject();
        root.add("http", http);
        root.addProperty("public-url", "http://localhost:8100");
        root.addProperty("web-url", "self");
        root.addProperty("relay-url", LiveConfig.DEFAULT_RELAY);
        root.addProperty("relay-web-url", LiveConfig.DEFAULT_RELAY_WEB);
        root.addProperty("player-updates-per-second", 10);
        root.addProperty("refresh-seconds", 300);
        root.addProperty("save-interval-seconds", 0);

        JsonObject discord = new JsonObject();
        discord.addProperty("webhook-url", "");
        discord.addProperty("announce-joins", true);
        discord.addProperty("announce-deaths", true);
        discord.addProperty("bot-token", "");
        discord.addProperty("status-channel-id", "");
        discord.addProperty("status-interval-minutes", 10);
        discord.addProperty("banner-url", "auto");
        discord.addProperty("server-name", "");
        discord.addProperty("show-motd", true);
        discord.addProperty("death-location", true);
        root.add("discord", discord);
        return root;
    }

    private static JsonObject lastRoot = defaults();

    static DiscordConfig discord() {
        JsonObject d = lastRoot.getAsJsonObject("discord");
        return new DiscordConfig(
                d.get("webhook-url").getAsString(),
                d.get("bot-token").getAsString(),
                d.get("status-channel-id").getAsString(),
                Math.max(1, d.get("status-interval-minutes").getAsInt()) * 60,
                d.get("announce-joins").getAsBoolean(),
                d.get("announce-deaths").getAsBoolean(),
                d.has("banner-url") ? d.get("banner-url").getAsString() : "auto",
                d.has("server-name") ? d.get("server-name").getAsString() : "",
                !d.has("show-motd") || d.get("show-motd").getAsBoolean(),
                !d.has("death-location") || d.get("death-location").getAsBoolean(),
                d.has("api-base") ? d.get("api-base").getAsString() : "",
                d.has("gateway-url") ? d.get("gateway-url").getAsString() : ""
        );
    }

    static void saveDiscord(DiscordConfig c) {
        JsonObject d = lastRoot.getAsJsonObject("discord");
        d.addProperty("webhook-url", c.webhookUrl());
        d.addProperty("bot-token", c.botToken());
        d.addProperty("status-channel-id", c.statusChannelId());
        d.addProperty("status-interval-minutes", c.statusIntervalMinutes());
        d.addProperty("banner-url", c.bannerUrl());
        d.addProperty("server-name", c.serverName());
        d.addProperty("show-motd", c.showMotd());
        d.addProperty("death-location", c.deathLocation());
        d.addProperty("announce-joins", c.announceJoins());
        d.addProperty("announce-deaths", c.announceDeaths());
        Path file = FabricLoader.getInstance().getConfigDir().resolve("minevisor-live.json");
        try {
            Files.writeString(file, GSON.toJson(lastRoot));
        } catch (IOException e) {
            MinevisorLiveMod.LOG.warn("Couldn't save {}: {}", file, e.getMessage());
        }
    }

    static LiveConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("minevisor-live.json");
        JsonObject root = defaults();
        try {
            if (Files.exists(file)) {
                JsonObject saved = GSON.fromJson(Files.readString(file), JsonObject.class);
                for (var e : saved.entrySet()) {
                    if ((e.getKey().equals("http") || e.getKey().equals("discord")) && e.getValue().isJsonObject()) {
                        for (var h : e.getValue().getAsJsonObject().entrySet()) {
                            root.getAsJsonObject(e.getKey()).add(h.getKey(), h.getValue());
                        }
                    } else {
                        root.add(e.getKey(), e.getValue());
                    }
                }
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException | RuntimeException e) {
            MinevisorLiveMod.LOG.warn("Couldn't read {}, using defaults: {}", file, e.getMessage());
        }

        lastRoot = root;
        JsonObject http = root.getAsJsonObject("http");
        return new LiveConfig(
                http.get("bind").getAsString(),
                http.get("port").getAsInt(),
                http.get("public-read").getAsBoolean(),
                http.get("allowed-origin").getAsString(),
                root.get("public-url").getAsString(),
                root.get("web-url").getAsString(),
                http.get("max-viewers").getAsInt(),
                LiveJson.playerIntervalTicks(root.get("player-updates-per-second").getAsInt()),
                root.get("save-interval-seconds").getAsInt(),
                root.get("refresh-seconds").getAsInt(),
                root.get("relay-url").getAsString(),
                root.get("relay-web-url").getAsString()
        );
    }
}
