package cc.minevisor.live.fabric;

import cc.minevisor.live.BlockFeed;
import cc.minevisor.live.Discord;
import cc.minevisor.live.DiscordConfig;
import cc.minevisor.live.LiveConfig;
import cc.minevisor.live.LiveJson;
import cc.minevisor.live.LiveServer;
import cc.minevisor.live.PlayerSync;
import cc.minevisor.live.TimeSync;
import cc.minevisor.live.RelayClient;
import cc.minevisor.live.Swings;
import cc.minevisor.live.TokenStore;
import cc.minevisor.live.VirtualFiles;
import cc.minevisor.live.fabric.mixin.PlayerListAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

public final class MinevisorLiveMod implements ModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("minevisor-live");
    private static final int SAVED_DELAY_TICKS = 40;

    private static MinevisorLiveMod instance;

    private LiveConfig config;
    private TokenStore tokens;
    private LiveServer http;
    private BlockFeed<ServerLevel> blocks;
    private Discord discord;
    private DiscordConfig discordConfig;
    private java.util.logging.Logger julLogger;
    private MinecraftServer server;
    private volatile JsonObject info = new JsonObject();

    private final Map<UUID, String> online = new HashMap<>();
    private long ticks;
    private long savedAt = -1;
    private PlayerSync<ServerPlayer> playerSync;
    private TimeSync timeSync;
    private RelayClient relay;

    RelayClient relay() {
        return relay;
    }
    private boolean savedSince;
    private long lastSavedEvent = Long.MIN_VALUE / 2;

    private ScheduledExecutorService pausedFlusher;
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private volatile long lastTickNanos;
    private static final long STALLED_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    @Override
    public void onInitialize() {
        instance = this;
        config = ModConfig.load();
        tokens = new TokenStore(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                .resolve("minevisor-live").resolve("link-token.json"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
                MinevisorCommand.register(dispatcher, this));
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
        ServerTickEvents.END_SERVER_TICK.register(s -> tick());
        ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> savedSince = true);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (http != null && entity instanceof ServerPlayer p) {
                BlockPos pos = p.blockPosition();
                http.events().broadcast("death", LiveJson.death(p.getUUID(), name(p),
                        dimension(p.level()), pos.getX(), pos.getY(), pos.getZ()));
            }
            if (discord != null && entity instanceof ServerPlayer p) {
                BlockPos at = p.blockPosition();
                discord.playerDied(name(p), source.getLocalizedDeathMessage(p).getString(),
                        dimension(p.level()), at.getX(), at.getY(), at.getZ());
            }
        });
    }

    public static void onAdvancement(ServerPlayer player) {
        MinevisorLiveMod mod = instance;
        if (mod != null && mod.playerSync != null) mod.playerSync.flag(player.getUUID());
    }

    public static void onBlockChanged(Level level, BlockPos pos) {
        MinevisorLiveMod mod = instance;
        if (mod == null || mod.blocks == null || !(level instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.getServer().isSameThread()) return;
        mod.blocks.mark(serverLevel, pos.getX(), pos.getY(), pos.getZ());
    }

    private Discord newDiscord(DiscordConfig c) {
        return new Discord(c, () -> info.deepCopy(), server == null ? null : new FabricWorldView(server),
                net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minevisor-live"), julLogger);
    }

    int watchers() {
        return http == null ? 0 : http.events().clientCount();
    }

        DiscordConfig discordConfig() {
        return discordConfig != null ? discordConfig : ModConfig.discord();
    }

    MinecraftServer server() {
        return server;
    }

    void reconfigureDiscord(DiscordConfig next) {
        discordConfig = next;
        ModConfig.saveDiscord(next);
        if (discord != null) discord.stopQuietly();
        discord = null;
        if (server == null) return;
        discord = newDiscord(next);
        if (discord.enabled()) discord.start(false);
    }

    LiveConfig config() {
        return config;
    }

    TokenStore tokens() {
        return tokens;
    }

    private static final Map<BlockState, String> STATE_NAMES = new java.util.IdentityHashMap<>();

    public static final Swings SWINGS = new Swings();
    private String lastPlayers;

    private static long stackHash(ItemStack stack) {
        return stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack) * 31L + stack.getCount();
    }

    private static String item(ItemStack stack) {
        return stack.isEmpty() ? null : LiveJson.item(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    static String name(ServerPlayer player) {
        return player.getName().getString();
    }

    static String dimension(Level level) {
        ResourceKey<Level> key = level.dimension();
        if (key == Level.NETHER) return "Nether";
        if (key == Level.END) return "The End";
        return "Overworld";
    }

    private void start(MinecraftServer server) {
        this.server = server;
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path root = server.getServerDirectory().toAbsolutePath().normalize();
        VirtualFiles.Layout layout = new VirtualFiles.Layout() {
            public Path overworld() { return world; }
            public Path nether() { return world; }
            public Path end() { return world; }
            public Path serverRoot() { return root; }
        };
        java.util.logging.Logger jul = java.util.logging.Logger.getLogger("minevisor-live");
        jul.setUseParentHandlers(false);
        jul.addHandler(new Handler() {
            public void publish(LogRecord r) {
                int level = r.getLevel().intValue();
                if (level >= java.util.logging.Level.SEVERE.intValue()) LOG.error(r.getMessage(), r.getThrown());
                else if (level >= java.util.logging.Level.WARNING.intValue()) LOG.warn(r.getMessage(), r.getThrown());
                else if (level >= java.util.logging.Level.INFO.intValue()) LOG.info(r.getMessage(), r.getThrown());
                else LOG.debug(r.getMessage(), r.getThrown());
            }
            public void flush() { }
            public void close() { }
        });
        http = new LiveServer(config, new VirtualFiles(layout), tokens, () -> info.deepCopy(), jul);
        try {
            http.start();
        } catch (IOException e) {
            if (!config.relayEnabled()) {
                LOG.error("Couldn't open port {}: {}", config.port(), e.getMessage());
                http = null;
                return;
            }
            LOG.warn("Couldn't open port {} ({}); links go through minevisor.cc only", config.port(), e.getMessage());
        }
        if (config.relayEnabled()) {
            relay = new RelayClient(config.relayUrl(), http, tokens, config.publicRead(),
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("minevisor-live").resolve("relay.json"),
                    jul);
            relay.start();
        }
        blocks = new BlockFeed<>(http.events(), new BlockFeed.Reader<>() {
            public String dimension(ServerLevel level) {
                return MinevisorLiveMod.dimension(level);
            }

            public boolean isLoaded(ServerLevel level, int x, int z) {
                return level.hasChunk(x >> 4, z >> 4);
            }

            public String state(ServerLevel level, int x, int y, int z) {
                return STATE_NAMES.computeIfAbsent(level.getBlockState(new BlockPos(x, y, z)), BlockStateParser::serialize);
            }
        });
        timeSync = new TimeSync(http.events());
        playerSync = new PlayerSync<>(http.events(), new PlayerSync.Platform<>() {
            @Override
            public UUID id(ServerPlayer p) {
                return p.getUUID();
            }

            @Override
            public long fingerprint(ServerPlayer p) {
                long h = 1;
                var inv = p.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) h = h * 31 + stackHash(inv.getItem(i));
                var ender = p.getEnderChestInventory();
                for (int i = 0; i < ender.getContainerSize(); i++) h = h * 31 + stackHash(ender.getItem(i));
                h = h * 31 + p.experienceLevel;
                h = h * 31 + p.totalExperience;
                h = h * 31 + (int) p.getHealth();
                return h * 31 + p.getFoodData().getFoodLevel();
            }

            @Override
            public void save(ServerPlayer p) {
                ((PlayerListAccessor) server.getPlayerList()).minevisor$save(p);
            }
        });
        online.clear();
        publishPlayers();
        julLogger = jul;
        discordConfig = ModConfig.discord();
        discord = newDiscord(discordConfig);
        if (discord.enabled()) discord.start();
        ticks = 0;
        savedAt = -1;
        pausedFlusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "minevisor-paused-flush");
            t.setDaemon(true);
            return t;
        });
        BlockFeed<ServerLevel> feed = blocks;
        pausedFlusher.scheduleAtFixedRate(() -> {
            boolean stalled = System.nanoTime() - lastTickNanos > STALLED_NANOS;
            if (stalled && feed.hasPending() && flushQueued.compareAndSet(false, true)) {
                server.execute(() -> {
                    flushQueued.set(false);
                    feed.flush();
                });
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        LOG.info("minevisor live on {}:{}", config.bind(), config.port());
    }

    private void stop() {
        if (discord != null && discord.enabled()) discord.stop();
        discord = null;
        if (pausedFlusher != null) pausedFlusher.shutdownNow();
        pausedFlusher = null;
        if (relay != null) relay.stop();
        relay = null;
        if (http != null) http.stop();
        http = null;
        if (blocks != null) blocks.close();
        blocks = null;
        playerSync = null;
        server = null;
    }

    private void tick() {
        if (http == null || server == null) return;
        lastTickNanos = System.nanoTime();
        ticks++;
        blocks.flush();
        playerSync.tick(server.getPlayerList().getPlayers());
        ServerLevel overworld = server.overworld();
        timeSync.tick(overworld.getOverworldClockTime() % 24000L, overworld.getGameRules().get(GameRules.ADVANCE_TIME));
        long every = http.events().clientCount() > 0 ? config.playersIntervalTicks() : Math.max(20, config.playersIntervalTicks());
        if (ticks % every == 0) publishPlayers();
        if (config.saveIntervalSeconds() > 0 && ticks % (20L * config.saveIntervalSeconds()) == 0) saveIfWatched();
        long spacing = 20L * Math.max(60, config.refreshSeconds());
        if (savedSince && savedAt < 0 && ticks - lastSavedEvent >= spacing && http.events().clientCount() > 0) {
            savedSince = false;
            savedAt = ticks;
        }
        if (savedAt >= 0 && ticks - savedAt >= SAVED_DELAY_TICKS) {
            savedAt = -1;
            lastSavedEvent = ticks;
            http.events().broadcast("saved", "{}");
        }
    }

    private void publishPlayers() {
        List<LiveJson.PlayerState> states = new ArrayList<>();
        Map<UUID, String> now = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            now.put(p.getUUID(), name(p));
            var pos = p.position();
            states.add(new LiveJson.PlayerState(p.getUUID(), name(p), dimension(p.level()),
                    pos.x(), pos.y(), pos.z(), p.getYRot(), p.getXRot(), p.getHealth(),
                    p.gameMode().getName().toLowerCase(Locale.ROOT),
                    LiveJson.pose(p.getPose().name()), p.isPassenger(), SWINGS.count(p.getUUID()),
                    p.hurtTime > 0, item(p.getMainHandItem()), item(p.getOffhandItem())));
        }
        for (var e : now.entrySet()) {
            if (online.containsKey(e.getKey())) continue;
            http.events().broadcast("join", LiveJson.player(e.getKey(), e.getValue()));
            if (discord != null) discord.playerJoined(e.getValue());
        }
        for (var e : online.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            SWINGS.forget(e.getKey());
            http.events().broadcast("quit", LiveJson.player(e.getKey(), e.getValue()));
            if (discord != null) discord.playerLeft(e.getValue());
        }
        online.clear();
        online.putAll(now);
        JsonArray players = LiveJson.players(states);
        info = LiveJson.info(new LiveJson.ServerInfo(
                server.getWorldData().getLevelName(),
                server.getMotd(),
                server.getServerVersion(),
                "fabric",
                server.getPlayerList().getMaxPlayers()
        ), players);
        String event = LiveJson.playersEvent(players);
        if (http.events().clientCount() > 0 && !event.equals(lastPlayers)) {
            lastPlayers = event;
            http.events().publishState("players", event);
        }
    }

    private void saveIfWatched() {
        if (http.events().clientCount() == 0) return;
        server.getPlayerList().saveAll();
        server.saveAllChunks(true, false, true);
        savedAt = ticks;
    }
}
