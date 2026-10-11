package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class BlockFeed<W> {

    public interface Reader<W> {
        String dimension(W world);

        boolean isLoaded(W world, int x, int z);

        String state(W world, int x, int y, int z);
    }

    private static final int MAX_PER_TICK = 2048;

    private static final int MAX_WAITING = 100_000;

    private record Pos<W>(W world, int x, int y, int z) {
    }

    private final EventHub events;
    private final Reader<W> reader;
    private final Set<Pos<W>> dirty = new LinkedHashSet<>();
    private final java.util.concurrent.ExecutorService encoder = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "minevisor-blocks");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean pending;

    public BlockFeed(EventHub events, Reader<W> reader) {
        this.events = events;
        this.reader = reader;
    }

    public void mark(W world, int x, int y, int z) {
        if (events.clientCount() == 0 || dirty.size() >= MAX_WAITING) return;
        dirty.add(new Pos<>(world, x, y, z));
        pending = true;
    }

    public boolean hasPending() {
        return pending;
    }

    public void flush() {
        if (dirty.isEmpty()) return;
        if (events.clientCount() == 0) {
            dirty.clear();
            pending = false;
            return;
        }
        Map<String, Batch> byWorld = new LinkedHashMap<>();
        Iterator<Pos<W>> it = dirty.iterator();
        int sent = 0;
        while (it.hasNext() && sent < MAX_PER_TICK) {
            Pos<W> pos = it.next();
            it.remove();
            if (!reader.isLoaded(pos.world(), pos.x(), pos.z())) continue;
            byWorld.computeIfAbsent(reader.dimension(pos.world()), d -> new Batch())
                    .add(pos.x(), pos.y(), pos.z(), reader.state(pos.world(), pos.x(), pos.y(), pos.z()));
            sent++;
        }
        pending = !dirty.isEmpty();
        if (byWorld.isEmpty()) return;
        encoder.execute(() -> {
            for (Map.Entry<String, Batch> entry : byWorld.entrySet()) {
                events.broadcast("blocks", entry.getValue().json(entry.getKey()));
            }
        });
    }

    public void close() {
        encoder.shutdown();
    }

    private static final class Batch {
        private int[] xyz = new int[48];
        private final java.util.ArrayList<String> states = new java.util.ArrayList<>();

        void add(int x, int y, int z, String state) {
            int i = states.size() * 3;
            if (i + 3 > xyz.length) xyz = java.util.Arrays.copyOf(xyz, xyz.length * 2);
            xyz[i] = x;
            xyz[i + 1] = y;
            xyz[i + 2] = z;
            states.add(state);
        }

        String json(String dimension) {
            JsonArray changes = new JsonArray();
            for (int n = 0; n < states.size(); n++) {
                JsonArray change = new JsonArray();
                change.add(xyz[n * 3]);
                change.add(xyz[n * 3 + 1]);
                change.add(xyz[n * 3 + 2]);
                change.add(states.get(n));
                changes.add(change);
            }
            JsonObject o = new JsonObject();
            o.addProperty("dimension", dimension);
            o.add("changes", changes);
            return o.toString();
        }
    }
}
