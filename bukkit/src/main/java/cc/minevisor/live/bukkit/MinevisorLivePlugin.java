package cc.minevisor.live.bukkit;

import cc.minevisor.live.BlockFeed;
import cc.minevisor.live.Discord;
import cc.minevisor.live.DiscordConfig;
import cc.minevisor.live.LiveConfig;
import cc.minevisor.live.LiveJson;
import cc.minevisor.live.LiveServer;
import cc.minevisor.live.PlayerSync;
import cc.minevisor.live.RelayClient;
import cc.minevisor.live.Swings;
import cc.minevisor.live.TimeSync;
import cc.minevisor.live.TokenStore;
import cc.minevisor.live.UpdateChecker;
import cc.minevisor.live.VirtualFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class MinevisorLivePlugin extends JavaPlugin {

    private LiveConfig liveConfig;
    private TokenStore tokens;
    private LiveServer server;
    private BlockFeed<World> blocks;
    private Discord discord;
    private DiscordConfig discordConfig;
    private UpdateChecker updates;
    private volatile JsonObject infoSnapshot = new JsonObject();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        liveConfig = readConfig(getConfig());
        tokens = new TokenStore(getDataFolder().toPath().resolve("link-token.json"));

        server = new LiveServer(liveConfig, new VirtualFiles(layout()), tokens, () -> infoSnapshot.deepCopy(), getLogger());
        server.entities(new BukkitEntities(this));
        try {
            server.start();
        } catch (IOException e) {
            if (!liveConfig.relayEnabled()) {
                getLogger().severe("Couldn't open port " + liveConfig.port() + ": " + e.getMessage());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().warning("Couldn't open port " + liveConfig.port() + " (" + e.getMessage()
                    + "); links go through minevisor.cc only");
        }
        if (liveConfig.relayEnabled()) {
            relay = new RelayClient(liveConfig.relayUrl(), server, tokens, liveConfig.publicRead(),
                    getDataFolder().toPath().resolve("relay.json"), getLogger());
            relay.start();
        }
        getLogger().info("minevisor live on " + liveConfig.bind() + ":" + liveConfig.port());

        blocks = new BlockFeed<>(server.events(), new BlockFeed.Reader<>() {
            public String dimension(World world) {
                return MinevisorLivePlugin.dimension(world);
            }

            public boolean isLoaded(World world, int x, int z) {
                return world.isChunkLoaded(x >> 4, z >> 4);
            }

            public String state(World world, int x, int y, int z) {
                return stateNames.computeIfAbsent(world.getBlockAt(x, y, z).getBlockData(), BlockData::getAsString);
            }
        });
        tick();
        SecretLogFilter.install();
        discordConfig = readDiscord(getConfig());
        discord = new Discord(discordConfig, () -> infoSnapshot.deepCopy(), new BukkitWorldView(this), getDataFolder().toPath(), getLogger());
        if (discord.enabled()) discord.start();
        getServer().getPluginManager().registerEvents(new LiveListener(server.events(), blocks, () -> discord, swings), this);
        getServer().getPluginManager().registerEvents(new SettingsGui(this), this);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onSave(org.bukkit.event.world.WorldSaveEvent e) {
                announceSaved();
            }
        }, this);
        updates = new UpdateChecker(getDescription().getVersion(), Bukkit.getBukkitVersion(), getLogger());
        updates.start();
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                Player player = e.getPlayer();
                UpdateChecker.Update update = updates.available();
                if (update == null || !player.hasPermission("minevisor.admin")) return;
                // After the join messages, so it isn't lost among them.
                Bukkit.getScheduler().runTaskLater(MinevisorLivePlugin.this, () -> {
                    if (player.isOnline()) MinevisorCommand.updateNotice(player, update, updates.current());
                }, 60L);
            }
        }, this);
        MinevisorCommand command = new MinevisorCommand(this);
        Objects.requireNonNull(getCommand("minevisor")).setExecutor(command);
        Objects.requireNonNull(getCommand("minevisor")).setTabCompleter(command);

        BukkitPlayerSaver saver = new BukkitPlayerSaver(getLogger());
        playerSync = new PlayerSync<>(server.events(), new PlayerSync.Platform<>() {
            @Override
            public UUID id(Player p) {
                return p.getUniqueId();
            }

            @Override
            public long fingerprint(Player p) {
                long h = 1;
                for (ItemStack item : p.getInventory().getContents()) h = h * 31 + Objects.hashCode(item);
                for (ItemStack item : p.getEnderChest().getContents()) h = h * 31 + Objects.hashCode(item);
                h = h * 31 + p.getLevel();
                h = h * 31 + p.getTotalExperience();
                h = h * 31 + (int) p.getHealth();
                return h * 31 + p.getFoodLevel();
            }

            @Override
            public void save(Player p) {
                saver.save(p);
            }
        });
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent e) {
                playerSync.flag(e.getPlayer().getUniqueId());
            }
        }, this);

        Bukkit.getScheduler().runTaskTimer(this, blocks::flush, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> playerSync.tick(Bukkit.getOnlinePlayers()), 1L, 1L);
        timeSync = new TimeSync(server.events());
        Bukkit.getScheduler().runTaskTimer(this, this::tickTime, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, liveConfig.playersIntervalTicks());
        if (liveConfig.saveIntervalSeconds() > 0) {
            long period = 20L * liveConfig.saveIntervalSeconds();
            Bukkit.getScheduler().runTaskTimer(this, this::flushIfWatched, period, period);
        }

    }

    @Override
    public void onDisable() {
        if (updates != null) updates.stop();
        if (discord != null && discord.enabled()) discord.stop();
        if (relay != null) relay.stop();
        if (server != null) server.stop();
        if (blocks != null) blocks.close();
    }

    LiveConfig liveConfig() {
        return liveConfig;
    }

    TokenStore tokens() {
        return tokens;
    }

    private static LiveConfig readConfig(FileConfiguration c) {
        return new LiveConfig(
                c.getString("http.bind", "0.0.0.0"),
                c.getInt("http.port", 8100),
                c.getBoolean("http.public-read", false),
                c.getString("http.allowed-origin", "*"),
                c.getString("public-url", "http://localhost:8100"),
                c.getString("web-url", "self"),
                c.getInt("http.max-viewers", 32),
                LiveJson.playerIntervalTicks(c.getInt("live.player-updates-per-second", 10)),
                c.getInt("live.save-interval-seconds", 0),
                c.getInt("live.refresh-seconds", 300),
                c.getString("relay.url", LiveConfig.DEFAULT_RELAY),
                c.getString("relay.web-url", LiveConfig.DEFAULT_RELAY_WEB)
        );
    }

    int watchers() {
        return server.events().clientCount();
    }

        DiscordConfig discordConfig() {
        return discordConfig;
    }

    void reconfigureDiscord(DiscordConfig next) {
        discordConfig = next;
        getConfig().set("discord.webhook-url", next.webhookUrl());
        getConfig().set("discord.bot-token", next.botToken());
        getConfig().set("discord.status-channel-id", next.statusChannelId());
        getConfig().set("discord.status-interval-minutes", next.statusIntervalMinutes());
        getConfig().set("discord.banner-url", next.bannerUrl());
        getConfig().set("discord.server-name", next.serverName());
        getConfig().set("discord.show-motd", next.showMotd());
        getConfig().set("discord.death-location", next.deathLocation());
        getConfig().set("discord.announce-joins", next.announceJoins());
        getConfig().set("discord.announce-deaths", next.announceDeaths());
        saveConfig();
        if (discord != null) discord.stopQuietly();
        discord = new Discord(next, () -> infoSnapshot.deepCopy(), new BukkitWorldView(this), getDataFolder().toPath(), getLogger());
        if (discord.enabled()) discord.start(false);
    }

    private static DiscordConfig readDiscord(FileConfiguration c) {
        return new DiscordConfig(
                c.getString("discord.webhook-url", ""),
                c.getString("discord.bot-token", ""),
                c.getString("discord.status-channel-id", ""),
                Math.max(1, c.getInt("discord.status-interval-minutes", 10)) * 60,
                c.getBoolean("discord.announce-joins", true),
                c.getBoolean("discord.announce-deaths", true),
                c.getString("discord.banner-url", "auto"),
                c.getString("discord.server-name", ""),
                c.getBoolean("discord.show-motd", true),
                c.getBoolean("discord.death-location", true),
                c.getString("discord.api-base", ""),
                c.getString("discord.gateway-url", "")
        );
    }

    private VirtualFiles.Layout layout() {
        World overworld = Bukkit.getWorlds().get(0);
        World nether = firstOf(World.Environment.NETHER, overworld);
        World end = firstOf(World.Environment.THE_END, overworld);
        Path root = Bukkit.getWorldContainer().toPath();
        return new VirtualFiles.Layout() {
            public Path overworld() { return saveRoot(overworld); }
            public Path nether() { return saveRoot(nether); }
            public Path end() { return saveRoot(end); }
            public Path serverRoot() { return root; }
        };
    }

    static Path saveRoot(World world) {
        Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        Path namespace = folder.getParent();
        Path dimensions = namespace == null ? null : namespace.getParent();
        if (dimensions != null && dimensions.getFileName() != null
                && dimensions.getFileName().toString().equals("dimensions") && dimensions.getParent() != null) {
            return dimensions.getParent();
        }
        return folder;
    }

    private static World firstOf(World.Environment env, World fallback) {
        return Bukkit.getWorlds().stream().filter(w -> w.getEnvironment() == env).findFirst().orElse(fallback);
    }

    static String dimension(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            default -> "Overworld";
        };
    }

    private final Swings swings = new Swings();
    private RelayClient relay;

    RelayClient relay() {
        return relay;
    }
    private PlayerSync<Player> playerSync;
    private TimeSync timeSync;
    private GameRule<Boolean> advanceTime;
    private boolean advanceTimeLooked;

    private void tickTime() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return;
        World world = worlds.get(0);
        timeSync.tick(world.getTime(), daylightCycle(world));
    }

    @SuppressWarnings("unchecked")
    private boolean daylightCycle(World world) {
        if (!advanceTimeLooked) {
            advanceTimeLooked = true;
            for (String name : new String[] {"advance_time", "advanceTime", "doDaylightCycle"}) {
                GameRule<?> rule = GameRule.getByName(name);
                if (rule != null && rule.getType() == Boolean.class) {
                    advanceTime = (GameRule<Boolean>) rule;
                    break;
                }
            }
        }
        if (advanceTime == null) return true;
        Boolean value = world.getGameRuleValue(advanceTime);
        return value == null || value;
    }
    private final java.util.Map<BlockData, String> stateNames = new java.util.HashMap<>();
    private String lastPlayers;

    Swings swings() {
        return swings;
    }

    private int idleTicks = 20;

    private void tick() {
        if (server.events().clientCount() == 0 && (idleTicks += liveConfig.playersIntervalTicks()) < 20) return;
        idleTicks = 0;
        List<LiveJson.PlayerState> states = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            var loc = p.getLocation();
            var inv = p.getInventory();
            String pose = p.isSneaking() && p.getPose() == org.bukkit.entity.Pose.STANDING ? "sneaking" : p.getPose().name();
            states.add(new LiveJson.PlayerState(p.getUniqueId(), p.getName(), dimension(p.getWorld()),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), p.getHealth(),
                    p.getGameMode().name().toLowerCase(Locale.ROOT),
                    LiveJson.pose(pose), p.isInsideVehicle(), swings.count(p.getUniqueId()),
                    p.getNoDamageTicks() > p.getMaximumNoDamageTicks() / 2,
                    LiveJson.item(inv.getItemInMainHand().getType().getKey().toString()),
                    LiveJson.item(inv.getItemInOffHand().getType().getKey().toString())));
        }
        JsonArray players = LiveJson.players(states);
        infoSnapshot = LiveJson.info(new LiveJson.ServerInfo(
                Bukkit.getWorlds().get(0).getName(),
                Bukkit.getMotd(),
                Bukkit.getBukkitVersion(),
                Bukkit.getName().toLowerCase(Locale.ROOT),
                Bukkit.getMaxPlayers()
        ), players);

        String event = LiveJson.playersEvent(players);
        if (server.events().clientCount() > 0 && !event.equals(lastPlayers)) {
            lastPlayers = event;
            server.events().publishState("players", event);
        }
    }

    private void flushIfWatched() {
        if (server.events().clientCount() == 0) return;
        Bukkit.savePlayers();
        for (World world : Bukkit.getWorlds()) world.save();
    }

    private boolean savedQueued;
    private long lastSavedEvent;

    void announceSaved() {
        if (savedQueued || server.events().clientCount() == 0) return;
        savedQueued = true;
        long spacing = 1000L * Math.max(60, liveConfig.refreshSeconds());
        long wait = Math.max(2000, lastSavedEvent + spacing - System.currentTimeMillis());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            savedQueued = false;
            lastSavedEvent = System.currentTimeMillis();
            server.events().broadcast("saved", "{}");
        }, wait / 50);
    }
}
