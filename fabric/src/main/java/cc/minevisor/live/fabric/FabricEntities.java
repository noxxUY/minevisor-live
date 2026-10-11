package cc.minevisor.live.fabric;

import cc.minevisor.live.EntityFeed;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The live entities for EntityFeed, on the server thread. Looks are the
 * entity's own saved NBT, cut down to the tags the workspace draws from.
 */
final class FabricEntities implements EntityFeed.Platform {

    /** Tags that change how an entity looks (variant, colour, age, what it holds...). */
    private static final Set<String> LOOK = Set.of(
            "Age", "IsBaby", "Owner", "Color", "Sheared", "VillagerData", "Variant", "variant", "Type", "MainGene",
            "HasNectar", "RabbitType", "PuffState", "Size", "ShowBottom", "Item", "Facing", "facing", "BlockState",
            "type", "CollarColor", "weather_state", "SkeletonType", "Profession");

    private final MinecraftServer server;

    FabricEntities(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<List<EntityFeed.Seen>> around(String dim, double x, double z, double radius, Predicate<UUID> wantLook) {
        CompletableFuture<List<EntityFeed.Seen>> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ResourceKey<Level> key = switch (dim) {
                    case "nether" -> Level.NETHER;
                    case "end" -> Level.END;
                    default -> Level.OVERWORLD;
                };
                ServerLevel level = server.getLevel(key);
                List<EntityFeed.Seen> out = new ArrayList<>();
                if (level != null) {
                    AABB box = new AABB(x - radius, level.getMinY(), z - radius, x + radius, level.getMaxY() + 1, z + radius);
                    for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player))) {
                        JsonObject look = wantLook.test(e.getUUID()) ? look(e) : null;
                        out.add(new EntityFeed.Seen(e.getUUID(), EntityType.getKey(e.getType()).toString(),
                                e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(), look));
                    }
                }
                result.complete(out);
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    private JsonObject look(Entity e) {
        JsonObject o = new JsonObject();
        try {
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
            e.saveWithoutId(output);
            CompoundTag tag = output.buildResult();
            for (String k : tag.keySet()) {
                if (!LOOK.contains(k)) continue;
                // Age only matters below zero (a baby); items count it up every tick.
                if (k.equals("Age") && tag.get(k) instanceof NumericTag n && n.box().doubleValue() >= 0) continue;
                o.add(k, json(tag.get(k)));
            }
        } catch (RuntimeException ignored) {
            // An entity that won't save: drawn with its defaults.
        }
        return o;
    }

    private static JsonElement json(Tag tag) {
        if (tag instanceof CompoundTag c) {
            JsonObject o = new JsonObject();
            for (String k : c.keySet()) o.add(k, json(c.get(k)));
            return o;
        }
        if (tag instanceof ListTag l) {
            JsonArray a = new JsonArray();
            for (Tag t : l) a.add(json(t));
            return a;
        }
        if (tag instanceof StringTag s) return new JsonPrimitive(s.value());
        if (tag instanceof NumericTag n) return new JsonPrimitive(n.box());
        return tag == null ? JsonNull.INSTANCE : new JsonPrimitive(tag.toString());
    }
}
