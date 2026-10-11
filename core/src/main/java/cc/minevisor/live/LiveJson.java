package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class LiveJson {

    private LiveJson() {
    }

    public record PlayerState(
            UUID uuid,
            String name,
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            double health,
            String gameMode,
            String pose,
            boolean riding,
            int swings,
            boolean hurt,
            String mainHand,
            String offHand
    ) {
    }

    public static String pose(String raw) {
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "sneaking", "crouching" -> "sneaking";
            case "swimming" -> "swimming";
            case "fall_flying" -> "gliding";
            case "sleeping" -> "sleeping";
            case "spin_attack" -> "riptide";
            case "dying" -> "dying";
            default -> "standing";
        };
    }

    public static String item(String id) {
        return id == null || id.isEmpty() || id.endsWith(":air") ? null : id;
    }

    public record ServerInfo(String worldName, String motd, String version, String platform, int maxPlayers) {
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static JsonArray players(List<PlayerState> players) {
        JsonArray arr = new JsonArray();
        for (PlayerState p : players) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", p.uuid().toString());
            o.addProperty("name", p.name());
            o.addProperty("dimension", p.dimension());
            o.addProperty("x", round(p.x()));
            o.addProperty("y", round(p.y()));
            o.addProperty("z", round(p.z()));
            o.addProperty("yaw", round(p.yaw()));
            o.addProperty("pitch", round(p.pitch()));
            o.addProperty("health", round(p.health()));
            o.addProperty("gameMode", p.gameMode());
            o.addProperty("pose", p.pose());
            if (p.riding()) o.addProperty("riding", true);
            o.addProperty("swings", p.swings());
            if (p.hurt()) o.addProperty("hurt", true);
            if (p.mainHand() != null) o.addProperty("mainHand", p.mainHand());
            if (p.offHand() != null) o.addProperty("offHand", p.offHand());
            arr.add(o);
        }
        return arr;
    }

    public static JsonObject info(ServerInfo server, JsonArray players) {
        JsonObject info = new JsonObject();
        info.addProperty("name", server.worldName());
        info.addProperty("motd", server.motd());
        info.addProperty("version", server.version());
        info.addProperty("platform", server.platform());
        info.addProperty("maxPlayers", server.maxPlayers());
        info.add("players", players);
        return info;
    }

    public static String playersEvent(JsonArray players) {
        JsonObject event = new JsonObject();
        event.add("players", players);
        return event.toString();
    }

    public static String player(UUID uuid, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        return o.toString();
    }

    public static String death(UUID uuid, String name, String dimension, int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("name", name);
        o.addProperty("dimension", dimension);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o.toString();
    }

    public static int playerIntervalTicks(int perSecond) {
        return Math.max(1, Math.round(20f / Math.max(1, Math.min(20, perSecond))));
    }

    private static final String OLD_DEFAULT_WEB_URL = "https://minevisor.cc/workspace";

    public static String relayLink(LiveConfig config, String serverId, String token) {
        String web = config.relayWebUrl() == null || config.relayWebUrl().isBlank()
                ? LiveConfig.DEFAULT_RELAY_WEB : config.relayWebUrl();
        String link = web + "?s=" + serverId + "&t=" + token;
        String relay = config.relayUrl().replaceAll("/+$", "");
        if (!relay.equals(LiveConfig.DEFAULT_RELAY)) link += "&relay=" + URLEncoder.encode(relay, StandardCharsets.UTF_8);
        return link;
    }

    public static String link(LiveConfig config, String token) {
        String public_ = config.publicUrl().replaceAll("/+$", "");
        String web = config.webUrl() == null ? "" : config.webUrl().trim();
        boolean self = web.isEmpty() || web.equalsIgnoreCase("self") || web.equals(OLD_DEFAULT_WEB_URL);
        String page = self ? public_ + "/workspace/" : web;
        return page + "?live=" + URLEncoder.encode(public_, StandardCharsets.UTF_8) + "&t=" + token;
    }
}
