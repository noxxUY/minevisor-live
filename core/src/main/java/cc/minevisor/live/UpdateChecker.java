package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for a newer release on GitHub when the server starts and every few
 * hours after. Releases are tagged v{version}-mc{minecraft}; the newest one
 * built for this Minecraft version wins.
 */
public final class UpdateChecker {

    public record Update(String version, String url) {
    }

    private static final URI RELEASES = URI.create("https://api.github.com/repos/noxxUY/minevisor-live/releases?per_page=30");
    private static final Pattern TAG = Pattern.compile("v(\\d+(?:\\.\\d+)*)-mc(\\d+\\.\\d+)(?:\\.\\d+)?");
    private static final long EVERY_HOURS = 6;

    private final String current;
    private final String minecraft;
    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private ScheduledExecutorService timer;
    private volatile Update update;
    private String announced;

    /**
     * @param current   this build's version, like 0.1.2
     * @param minecraft the server's Minecraft version, like 26.2 or 26.1.2
     */
    public UpdateChecker(String current, String minecraft, Logger log) {
        this.current = current;
        this.minecraft = majorMinor(minecraft);
        this.log = log;
    }

    public void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "minevisor-update-check");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::check, 5, TimeUnit.HOURS.toSeconds(EVERY_HOURS), TimeUnit.SECONDS);
    }

    public void stop() {
        if (timer != null) timer.shutdownNow();
    }

    /** The newer release, or null when this build is the latest (or GitHub couldn't be reached). */
    public Update available() {
        return update;
    }

    public String current() {
        return current;
    }

    private void check() {
        try {
            HttpRequest req = HttpRequest.newBuilder(RELEASES)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "minevisor-live/" + current)
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            Update best = newest(JsonParser.parseString(res.body()).getAsJsonArray());
            update = best;
            if (best != null && !best.version().equals(announced)) {
                announced = best.version();
                log.warning("A new version of minevisor live is out: " + best.version() + " (this server runs "
                        + current + "). Download it from " + best.url());
            }
        } catch (Exception e) {
            // Offline or rate limited: try again next round, quietly.
        }
    }

    private Update newest(JsonArray releases) {
        Update best = null;
        for (JsonElement el : releases) {
            JsonObject r = el.getAsJsonObject();
            if (bool(r, "draft") || bool(r, "prerelease") || !r.has("tag_name")) continue;
            Matcher m = TAG.matcher(r.get("tag_name").getAsString());
            if (!m.matches() || !m.group(2).equals(minecraft)) continue;
            String version = m.group(1);
            if (compare(version, current) <= 0) continue;
            if (best == null || compare(version, best.version()) > 0) {
                String url = r.has("html_url") ? r.get("html_url").getAsString()
                        : "https://github.com/noxxUY/minevisor-live/releases";
                best = new Update(version, url);
            }
        }
        return best;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    static String majorMinor(String version) {
        String[] parts = version.split("[^0-9.]", 2)[0].split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
    }

    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
