package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ActivityTracker {

    private static final int MAX_CHUNKS = 20_000;

    private final Path file;
    private final Map<Long, Integer> visits = new ConcurrentHashMap<>();

    ActivityTracker(Path file) {
        this.file = file;
        load();
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    void record(JsonArray players) {
        for (JsonElement el : players) {
            JsonObject p = el.getAsJsonObject();
            if (!"Overworld".equals(p.has("dimension") ? p.get("dimension").getAsString() : null)) continue;
            int cx = (int) Math.floor(p.get("x").getAsDouble() / 16);
            int cz = (int) Math.floor(p.get("z").getAsDouble() / 16);
            visits.merge(key(cx, cz), 1, Integer::sum);
        }
        if (visits.size() > MAX_CHUNKS) {
            visits.entrySet().removeIf(e -> e.getValue() <= 1);
        }
    }

    int[] busiest() {
        long best = Long.MIN_VALUE;
        int bestScore = 0;
        for (Long k : visits.keySet()) {
            int cx = (int) (k >> 32), cz = (int) (long) k;
            int score = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) score += visits.getOrDefault(key(cx + dx, cz + dz), 0);
            }
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        if (bestScore == 0) return null;
        return new int[]{(int) (best >> 32) * 16 + 8, (int) best * 16 + 8};
    }

    void save() {
        JsonObject o = new JsonObject();
        for (var e : visits.entrySet()) o.addProperty(Long.toString(e.getKey()), e.getValue());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o.toString());
        } catch (IOException ignored) {
        }
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var e : o.entrySet()) visits.put(Long.parseLong(e.getKey()), e.getValue().getAsInt());
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
