package cc.minevisor.live;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Swings {

    private final Map<UUID, Integer> counts = new ConcurrentHashMap<>();

    public void swing(UUID player) {
        counts.merge(player, 1, Integer::sum);
    }

    public int count(UUID player) {
        return counts.getOrDefault(player, 0);
    }

    public void forget(UUID player) {
        counts.remove(player);
    }
}
