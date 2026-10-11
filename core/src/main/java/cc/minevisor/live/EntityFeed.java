package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * The live entities around a viewer's camera: mobs, items, minecarts and the
 * rest (players have their own feed). Viewers poll a few times a second;
 * each answer carries every entity's position and, only when it's new or
 * changed, its look (variant, colour, profession, item...) under the save
 * file's own NBT names, so the page reads it like an entity from disk.
 */
public final class EntityFeed {

    /** One entity as the platform sees it; {@code look} is null when it wasn't asked for. */
    public record Seen(UUID id, String type, double x, double y, double z, float yaw, float pitch, JsonObject look) {
    }

    public interface Platform {
        /**
         * On the server thread: the non-player entities within {@code radius}
         * blocks (horizontally) of x, z in a dimension ("overworld", "nether",
         * "end"), building looks only for those {@code wantLook} accepts.
         */
        CompletableFuture<List<Seen>> around(String dim, double x, double z, double radius, Predicate<UUID> wantLook);
    }

    public static final double MAX_RADIUS = 192;
    private static final int MAX_ENTITIES = 1500;
    /** Looks are rebuilt this often, to catch a sheep being sheared or a villager getting a job. */
    private static final long LOOK_REFRESH_MS = 3000;
    private static final long FORGET_MS = 120_000;

    private record Known(long seq, String look, long checked, long seen) {
    }

    private final Platform platform;
    private final Map<UUID, Known> known = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private long lastPrune;

    public EntityFeed(Platform platform) {
        this.platform = platform;
    }

    /**
     * The answer to one poll. {@code since} is the highest look sequence the
     * viewer has; looks newer than that are included.
     */
    public JsonObject query(String dim, double x, double z, double radius, long since) throws Exception {
        long now = System.currentTimeMillis();
        double r = Math.max(8, Math.min(MAX_RADIUS, radius));
        List<Seen> list = platform.around(dim, x, z, r, id -> {
            Known k = known.get(id);
            return k == null || since < k.seq() || now - k.checked() > LOOK_REFRESH_MS;
        }).get(3, TimeUnit.SECONDS);

        JsonArray out = new JsonArray();
        list.stream()
                .sorted(Comparator.comparingDouble(s -> (s.x() - x) * (s.x() - x) + (s.z() - z) * (s.z() - z)))
                .limit(MAX_ENTITIES)
                .forEach(s -> {
                    Known k = known.get(s.id());
                    if (s.look() != null) {
                        String look = s.look().toString();
                        k = k == null || !k.look().equals(look)
                                ? new Known(seq.incrementAndGet(), look, now, now)
                                : new Known(k.seq(), k.look(), now, now);
                    } else if (k != null) {
                        k = new Known(k.seq(), k.look(), k.checked(), now);
                    }
                    if (k != null) known.put(s.id(), k);
                    JsonArray row = new JsonArray();
                    row.add(s.id().toString());
                    row.add(s.type());
                    row.add(round(s.x()));
                    row.add(round(s.y()));
                    row.add(round(s.z()));
                    row.add(Math.round(s.yaw() * 10) / 10.0);
                    row.add(Math.round(s.pitch() * 10) / 10.0);
                    if (k != null && k.seq() > since) row.add(com.google.gson.JsonParser.parseString(k.look()));
                    out.add(row);
                });

        if (now - lastPrune > 30_000) {
            lastPrune = now;
            known.values().removeIf(k -> now - k.seen() > FORGET_MS);
        }
        JsonObject body = new JsonObject();
        body.addProperty("seq", seq.get());
        body.add("e", out);
        return body;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
