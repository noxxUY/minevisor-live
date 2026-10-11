package cc.minevisor.live;

public record LiveConfig(
        String bind,
        int port,
        boolean publicRead,
        String allowedOrigin,
        String publicUrl,
        String webUrl,
        int maxViewers,
        int playersIntervalTicks,

        int saveIntervalSeconds,

        int refreshSeconds,
        String relayUrl,
        String relayWebUrl
) {
    public static final String DEFAULT_RELAY = "wss://minevisor-relay.noxxzera.workers.dev";
    public static final String DEFAULT_RELAY_WEB = "https://minevisor.cc/workspace/";

    public boolean relayEnabled() {
        return relayUrl != null && !relayUrl.isBlank() && !relayUrl.equalsIgnoreCase("off");
    }
}
