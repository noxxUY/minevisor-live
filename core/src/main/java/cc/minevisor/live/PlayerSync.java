package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerSync<P> {

    public interface Platform<P> {
        UUID id(P player);

        long fingerprint(P player);

        void save(P player);
    }

    private static final int CHECK_TICKS = 20;
    private static final int MIN_SAVE_GAP_TICKS = 60;

    private final EventHub events;
    private final Platform<P> platform;
    private final Map<UUID, Long> fingerprints = new HashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final Set<UUID> flagged = ConcurrentHashMap.newKeySet();
    private long ticks;
    private long lastSave = -MIN_SAVE_GAP_TICKS;

    public PlayerSync(EventHub events, Platform<P> platform) {
        this.events = events;
        this.platform = platform;
    }

    public void flag(UUID player) {
        flagged.add(player);
    }

    public void tick(Collection<? extends P> online) {
        ticks++;
        if (events.clientCount() == 0) {
            fingerprints.clear();
            dirty.clear();
            flagged.clear();
            return;
        }
        if (ticks % CHECK_TICKS == 0) {
            Map<UUID, Long> seen = new HashMap<>();
            for (P p : online) {
                UUID id = platform.id(p);
                long print = platform.fingerprint(p);
                Long before = fingerprints.get(id);
                if (before != null && before != print) dirty.add(id);
                seen.put(id, print);
            }
            fingerprints.keySet().retainAll(seen.keySet());
            fingerprints.putAll(seen);
            dirty.addAll(flagged);
            flagged.clear();
        }
        if (dirty.isEmpty() || ticks - lastSave < MIN_SAVE_GAP_TICKS) return;
        JsonArray saved = new JsonArray();
        for (P p : online) {
            UUID id = platform.id(p);
            if (!dirty.contains(id)) continue;
            platform.save(p);
            saved.add(id.toString());
        }
        dirty.clear();
        lastSave = ticks;
        if (saved.isEmpty()) return;
        JsonObject event = new JsonObject();
        event.add("uuids", saved);
        events.broadcast("player", event.toString());
    }
}
