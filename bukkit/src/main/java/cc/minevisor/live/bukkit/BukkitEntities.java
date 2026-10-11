package cc.minevisor.live.bukkit;

import cc.minevisor.live.EntityFeed;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Llama;
import org.bukkit.entity.MushroomCow;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Panda;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.PufferFish;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * The live entities for EntityFeed, read on the main thread through the
 * Bukkit API. Looks are written under the NBT names a save uses, so the
 * workspace draws a live sheep the way it draws one from disk.
 */
final class BukkitEntities implements EntityFeed.Platform {

    private final Plugin plugin;

    BukkitEntities(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<List<EntityFeed.Seen>> around(String dim, double x, double z, double radius, Predicate<UUID> wantLook) {
        CompletableFuture<List<EntityFeed.Seen>> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                World world = world(dim);
                List<EntityFeed.Seen> out = new ArrayList<>();
                if (world != null) {
                    double half = (world.getMaxHeight() - world.getMinHeight()) / 2.0;
                    Location center = new Location(world, x, world.getMinHeight() + half, z);
                    for (Entity e : world.getNearbyEntities(center, radius, half + 1, radius)) {
                        if (e instanceof Player) continue;
                        Location l = e.getLocation();
                        JsonObject look = wantLook.test(e.getUniqueId()) ? look(e) : null;
                        out.add(new EntityFeed.Seen(e.getUniqueId(), e.getType().getKey().toString(),
                                l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), look));
                    }
                }
                result.complete(out);
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    private static World world(String dim) {
        World.Environment env = switch (dim) {
            case "nether" -> World.Environment.NETHER;
            case "end" -> World.Environment.THE_END;
            default -> World.Environment.NORMAL;
        };
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == env) return w;
        return null;
    }

    /** The parts of an entity's NBT its look depends on. */
    static JsonObject look(Entity e) {
        JsonObject o = new JsonObject();
        if (e instanceof Ageable a && !a.isAdult()) o.addProperty("Age", -1);
        if (e instanceof Zombie zo && !zo.isAdult()) o.addProperty("IsBaby", 1);
        if (e instanceof Tameable t && t.isTamed()) o.addProperty("Owner", true);
        if (e instanceof Sheep s) {
            if (s.getColor() != null) o.addProperty("Color", s.getColor().ordinal());
            o.addProperty("Sheared", s.isSheared() ? 1 : 0);
        }
        if (e instanceof Villager v) o.add("VillagerData", villager(v.getVillagerType(), v.getProfession()));
        if (e instanceof ZombieVillager v) o.add("VillagerData", villager(v.getVillagerType(), v.getVillagerProfession()));
        if (e instanceof Horse h) o.addProperty("Variant", h.getColor().ordinal() | (h.getStyle().ordinal() << 8));
        if (e instanceof Llama l) o.addProperty("Variant", l.getColor().ordinal());
        if (e instanceof Parrot p) o.addProperty("Variant", p.getVariant().ordinal());
        if (e instanceof Axolotl a) o.addProperty("Variant", a.getVariant().ordinal());
        if (e instanceof Fox f) o.addProperty("Type", f.getFoxType() == Fox.Type.SNOW ? "snow" : "red");
        if (e instanceof MushroomCow m) o.addProperty("Type", m.getVariant() == MushroomCow.Variant.BROWN ? "brown" : "red");
        if (e instanceof Panda p) o.addProperty("MainGene", p.getMainGene().name().toLowerCase());
        if (e instanceof Bee b) o.addProperty("HasNectar", b.hasNectar() ? 1 : 0);
        if (e instanceof Rabbit r) o.addProperty("RabbitType", switch (r.getRabbitType()) {
            case BROWN -> 0;
            case WHITE -> 1;
            case BLACK -> 2;
            case BLACK_AND_WHITE -> 3;
            case GOLD -> 4;
            case SALT_AND_PEPPER -> 5;
            case THE_KILLER_BUNNY -> 99;
        });
        if (e instanceof PufferFish p) o.addProperty("PuffState", p.getPuffState());
        if (e instanceof Shulker s) o.addProperty("Color", s.getColor() == null ? 16 : s.getColor().ordinal());
        if (e instanceof Slime s) o.addProperty("Size", s.getSize() - 1);
        if (e instanceof Phantom p) o.addProperty("Size", p.getSize());
        if (e instanceof Wolf w) {
            String v = key(call(w, "getVariant"));
            if (v != null) o.addProperty("variant", v);
            if (w.isAngry()) o.addProperty("AngerTime", 1);
        }
        if (e instanceof EnderCrystal c) o.addProperty("ShowBottom", c.isShowingBottom() ? 1 : 0);
        if (e instanceof Item i) o.add("Item", item(i.getItemStack()));
        if (e instanceof ItemFrame f) {
            o.addProperty("Facing", face3d(f.getFacing()));
            if (f.getItem() != null && !f.getItem().getType().isAir()) o.add("Item", item(f.getItem()));
        }
        if (e instanceof Painting p) {
            String art = key(p.getArt());
            if (art != null) o.addProperty("variant", art);
            o.addProperty("facing", face2d(p.getFacing()));
        } else if (e instanceof Hanging h && !(e instanceof ItemFrame)) {
            o.addProperty("Facing", face3d(h.getFacing()));
        }
        if (e instanceof FallingBlock f) {
            JsonObject state = new JsonObject();
            state.addProperty("Name", f.getBlockData().getMaterial().getKey().toString());
            o.add("BlockState", state);
        }
        // Registry-backed variants (cats, frogs, cows, pigs, chickens, salmon): the method
        // exists on newer servers only, so they're looked up by name.
        String variant = key(call(e, "getCatType"));
        if (variant == null && !(e instanceof Wolf) && !(e instanceof Axolotl) && !(e instanceof Parrot)
                && !(e instanceof Llama) && !(e instanceof MushroomCow)) {
            variant = key(call(e, "getVariant"));
        }
        if (variant != null && !o.has("variant")) {
            if (e.getType().getKey().getKey().equals("salmon")) o.addProperty("type", variant.replace("minecraft:", ""));
            else o.addProperty("variant", variant);
        }
        return o;
    }

    private static JsonObject villager(Object type, Object profession) {
        JsonObject data = new JsonObject();
        String t = key(type);
        String p = key(profession);
        if (t != null) data.addProperty("type", t);
        if (p != null) data.addProperty("profession", p);
        return data;
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("id", stack.getType().getKey().toString());
        o.addProperty("count", stack.getAmount());
        return o;
    }

    /** Direction's 3D index: down, up, north, south, west, east. */
    private static int face3d(BlockFace f) {
        return switch (f) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            default -> 5;
        };
    }

    /** Direction's 2D index: south, west, north, east. */
    private static int face2d(BlockFace f) {
        return switch (f) {
            case WEST -> 1;
            case NORTH -> 2;
            case EAST -> 3;
            default -> 0;
        };
    }

    private static Object call(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String key(Object value) {
        if (value == null) return null;
        if (value instanceof Keyed k) return k.getKey().toString();
        if (value instanceof DyeColor || value instanceof Enum<?>) return null;
        Object k = call(value, "getKey");
        return k == null ? null : k.toString();
    }
}
