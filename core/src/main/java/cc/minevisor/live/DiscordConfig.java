package cc.minevisor.live;

public record DiscordConfig(
        String webhookUrl,
        String botToken,
        String statusChannelId,
        int statusIntervalSeconds,
        boolean announceJoins,
        boolean announceDeaths,
        String bannerUrl,
        String serverName,
        boolean showMotd,
        boolean deathLocation,
        String apiBase,
        String gatewayUrl
) {
    public static final String DEFAULT_API = "https://discord.com/api/v10";
    public static final String DEFAULT_GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json";

    public DiscordConfig with(String setting, String value) {
        return switch (setting) {
            case "webhook" -> new DiscordConfig(value, botToken, statusChannelId, statusIntervalSeconds, announceJoins,
                    announceDeaths, bannerUrl, serverName, showMotd, deathLocation, apiBase, gatewayUrl);
            case "token" -> new DiscordConfig(webhookUrl, value, statusChannelId, statusIntervalSeconds, announceJoins,
                    announceDeaths, bannerUrl, serverName, showMotd, deathLocation, apiBase, gatewayUrl);
            case "status_channel_id" -> new DiscordConfig(webhookUrl, botToken, value, statusIntervalSeconds, announceJoins,
                    announceDeaths, bannerUrl, serverName, showMotd, deathLocation, apiBase, gatewayUrl);
            case "banner" -> new DiscordConfig(webhookUrl, botToken, statusChannelId, statusIntervalSeconds, announceJoins,
                    announceDeaths, value, serverName, showMotd, deathLocation, apiBase, gatewayUrl);
            case "name" -> new DiscordConfig(webhookUrl, botToken, statusChannelId, statusIntervalSeconds, announceJoins,
                    announceDeaths, bannerUrl, value, showMotd, deathLocation, apiBase, gatewayUrl);
            case "motd" -> new DiscordConfig(webhookUrl, botToken, statusChannelId, statusIntervalSeconds, announceJoins,
                    announceDeaths, bannerUrl, serverName, "on".equalsIgnoreCase(value), deathLocation, apiBase, gatewayUrl);
            case "joins" -> new DiscordConfig(webhookUrl, botToken, statusChannelId, statusIntervalSeconds,
                    "on".equalsIgnoreCase(value), announceDeaths, bannerUrl, serverName, showMotd, deathLocation, apiBase, gatewayUrl);
            case "deaths" -> new DiscordConfig(webhookUrl, botToken, statusChannelId, statusIntervalSeconds, announceJoins,
                    "on".equalsIgnoreCase(value), bannerUrl, serverName, showMotd, deathLocation, apiBase, gatewayUrl);
            case "death_location" -> new DiscordConfig(webhookUrl, botToken, statusChannelId, statusIntervalSeconds, announceJoins,
                    announceDeaths, bannerUrl, serverName, showMotd, "on".equalsIgnoreCase(value), apiBase, gatewayUrl);
            case "status_interval" -> new DiscordConfig(webhookUrl, botToken, statusChannelId, Integer.parseInt(value) * 60,
                    announceJoins, announceDeaths, bannerUrl, serverName, showMotd, deathLocation, apiBase, gatewayUrl);
            default -> throw new IllegalArgumentException(setting);
        };
    }

    public int statusIntervalMinutes() {
        return Math.max(1, statusIntervalSeconds / 60);
    }

    public boolean hasName() {
        return serverName != null && !serverName.isBlank();
    }

    public boolean webhookEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    public boolean botEnabled() {
        return botToken != null && !botToken.isBlank();
    }

    public boolean statusEnabled() {
        return botEnabled() && statusChannelId != null && !statusChannelId.isBlank();
    }
}
