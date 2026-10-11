package cc.minevisor.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class DiscordAdmin {

    private DiscordAdmin() {
    }

    public static final Pattern SECRET_COMMAND = Pattern.compile("(?i)(/?minevisor\\s+(token|webhook))\\s+\\S+");

    private static final Pattern WEBHOOK = Pattern.compile(
            "https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[A-Za-z0-9_-]+");
    private static final Pattern CHANNEL = Pattern.compile("\\d{5,25}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.-]{20,200}");

    public static boolean isOff(String value) {
        return value.equalsIgnoreCase("off") || value.equalsIgnoreCase("none") || value.equals("-");
    }

    public static String checkWebhook(String url, DiscordConfig current) {
        boolean testing = current.apiBase() != null && !current.apiBase().isBlank();
        if (testing && url.matches("https?://\\S+")) return null;
        return WEBHOOK.matcher(url).matches() ? null
                : "That isn't a Discord webhook URL (https://discord.com/api/webhooks/<id>/<token>).";
    }

    public static String checkChannel(String id) {
        return CHANNEL.matcher(id).matches() ? null
                : "A channel ID is a long number: enable Developer Mode in Discord, right-click the channel, Copy ID.";
    }

    public static String checkToken(String token) {
        return TOKEN.matcher(token).matches() ? null
                : "That doesn't look like a bot token (Developer Portal > your app > Bot > Reset Token).";
    }

    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) return "not set";
        if (secret.length() <= 10) return "set";
        return secret.substring(0, 4) + "…" + secret.substring(secret.length() - 4);
    }

    public static String maskWebhook(String url) {
        if (url == null || url.isBlank()) return "not set";
        int i = url.lastIndexOf('/');
        return i < 0 ? "set" : url.substring(0, i + 1) + "…";
    }

    public static String redact(String line) {
        return line == null ? null : SECRET_COMMAND.matcher(line).replaceAll("$1 ****");
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static String api(DiscordConfig c) {
        return c.apiBase() == null || c.apiBase().isBlank() ? DiscordConfig.DEFAULT_API : c.apiBase();
    }

    public record Check(boolean ok, String message) {
    }

    public static CompletableFuture<Check> testWebhook(DiscordConfig c) {
        JsonObject body = new JsonObject();
        body.addProperty("username", "minevisor");
        com.google.gson.JsonArray embeds = new com.google.gson.JsonArray();
        embeds.add(Discord.connectedEmbed());
        body.add("embeds", embeds);
        HttpRequest req = HttpRequest.newBuilder(URI.create(c.webhookUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> switch (res.statusCode()) {
                    case 200, 204 -> new Check(true, "Webhook works: a test message was posted.");
                    case 401, 404 -> new Check(false, "Discord doesn't know that webhook (deleted, or the URL is incomplete).");
                    default -> new Check(false, "Discord answered " + res.statusCode() + " to the webhook.");
                })
                .exceptionally(e -> new Check(false, "Couldn't reach Discord: " + e.getMessage()));
    }

    public static CompletableFuture<Check> testToken(DiscordConfig c) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(api(c) + "/users/@me"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + c.botToken())
                .header("User-Agent", "DiscordBot (https://minevisor.cc, 0.1)")
                .GET()
                .build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() == 401) return new Check(false, "Discord rejected that token.");
                    if (res.statusCode() != 200) return new Check(false, "Discord answered " + res.statusCode() + " to the token.");
                    try {
                        String name = JsonParser.parseString(res.body()).getAsJsonObject().get("username").getAsString();
                        return new Check(true, "Bot connected as " + name + ". /status and /players can take a few minutes to appear in Discord.");
                    } catch (RuntimeException e) {
                        return new Check(true, "Token accepted.");
                    }
                })
                .exceptionally(e -> new Check(false, "Couldn't reach Discord: " + e.getMessage()));
    }

    public static String summary(DiscordConfig c) {
        return "webhook: " + maskWebhook(c.webhookUrl())
                + " | token: " + mask(c.botToken())
                + " | status channel: " + (c.statusChannelId() == null || c.statusChannelId().isBlank() ? "not set" : c.statusChannelId());
    }

    public static DiscordConfig withWebhook(DiscordConfig c, String url) {
        return c.with("webhook", url);
    }

    public static DiscordConfig withToken(DiscordConfig c, String token) {
        return c.with("token", token);
    }

    public static DiscordConfig withBanner(DiscordConfig c, String url) {
        return c.with("banner", url);
    }

    public static DiscordConfig withStatusChannel(DiscordConfig c, String id) {
        return c.with("status_channel_id", id);
    }

    public static String checkBanner(String url) {
        return url.equalsIgnoreCase("auto") || url.matches("https://\\S+") ? null
                : "Use auto (a map of where players are) or an https link to an image, about 2000x500.";
    }

    public static String checkName(String name) {
        return name.length() <= 40 ? null : "Keep the name under 40 characters.";
    }

    public static String checkMinutes(String value) {
        try {
            int m = Integer.parseInt(value);
            return m >= 1 && m <= 1440 ? null : "Use 1 to 1440 minutes.";
        } catch (NumberFormatException e) {
            return "Use a number of minutes, like 10.";
        }
    }

    public static String checkOnOff(String value) {
        return value.equalsIgnoreCase("on") || value.equalsIgnoreCase("off") ? null : "Use on or off.";
    }

    public record Change(DiscordConfig next, String error, String message) {
    }

    public static final java.util.List<String> SETTINGS = java.util.List.of(
            "webhook", "token", "status_channel_id", "status_interval", "banner", "name", "motd", "joins", "deaths", "death_location");

    public static boolean isToggle(String setting) {
        return setting.equals("motd") || setting.equals("joins") || setting.equals("deaths") || setting.equals("death_location");
    }

    public static String label(String setting) {
        return switch (setting) {
            case "webhook" -> "Webhook";
            case "token" -> "Bot token";
            case "status_channel_id" -> "Status channel";
            case "status_interval" -> "Status update interval";
            case "banner" -> "Banner";
            case "name" -> "Server name";
            case "motd" -> "MOTD on the cards";
            case "joins" -> "Join and leave alerts";
            case "deaths" -> "Death alerts";
            case "death_location" -> "Death location";
            default -> setting;
        };
    }

    public static Change apply(DiscordConfig current, String setting, String rawValue) {
        String value = rawValue.trim();
        if (isToggle(setting)) {
            String problem = checkOnOff(value);
            if (problem != null) return new Change(null, problem, null);
            boolean on = value.equalsIgnoreCase("on");
            return new Change(current.with(setting, on ? "on" : "off"), null, label(setting) + (on ? " on." : " off."));
        }
        if (setting.equals("status_interval")) {
            String problem = checkMinutes(value);
            if (problem != null) return new Change(null, problem, null);
            int minutes = Integer.parseInt(value);
            return new Change(current.with(setting, value), null,
                    "The status message now updates every " + minutes + (minutes == 1 ? " minute." : " minutes."));
        }
        boolean off = isOff(value);
        String problem = off ? null : switch (setting) {
            case "webhook" -> checkWebhook(value, current);
            case "token" -> checkToken(value);
            case "status_channel_id" -> checkChannel(value);
            case "banner" -> checkBanner(value);
            case "name" -> checkName(value);
            default -> "Unknown setting.";
        };
        if (problem != null) return new Change(null, problem, null);
        String stored = off ? (setting.equals("banner") ? "off" : "") : value;
        String message = label(setting) + (off ? " turned off." : setting.equals("name") ? " set to \"" + value + "\"." : " saved.");
        return new Change(current.with(setting, stored), null, message);
    }

    public static boolean isOn(DiscordConfig c, String setting) {
        return switch (setting) {
            case "motd" -> c.showMotd();
            case "joins" -> c.announceJoins();
            case "deaths" -> c.announceDeaths();
            case "death_location" -> c.deathLocation();
            default -> false;
        };
    }
}
