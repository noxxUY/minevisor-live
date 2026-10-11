package cc.minevisor.live.bukkit;

import cc.minevisor.live.BlockFeed;
import cc.minevisor.live.Discord;
import cc.minevisor.live.EventHub;
import cc.minevisor.live.LiveJson;
import cc.minevisor.live.Swings;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.function.Supplier;

final class LiveListener implements Listener {

    private final EventHub events;
    private final BlockFeed<World> feed;
    private final Supplier<Discord> discord;
    private final Swings swings;

    LiveListener(EventHub events, BlockFeed<World> feed, Supplier<Discord> discord, Swings swings) {
        this.events = events;
        this.swings = swings;
        this.feed = feed;
        this.discord = discord;
    }

    private void mark(Block block) {
        feed.mark(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private void markAll(List<Block> blocks) {
        for (Block block : blocks) mark(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        mark(e.getBlock());
        mark(e.getBlock().getRelative(BlockFace.UP));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        mark(e.getBlockPlaced());
        mark(e.getBlockPlaced().getRelative(BlockFace.UP));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            mark(e.getClickedBlock());
            mark(e.getClickedBlock().getRelative(BlockFace.UP));
            mark(e.getClickedBlock().getRelative(BlockFace.DOWN));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        markAll(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        markAll(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        for (BlockState state : e.getBlocks()) mark(state.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        for (BlockState state : e.getBlocks()) mark(state.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        mark(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        pistonMoved(e.getBlock(), e.getDirection(), e.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        pistonMoved(e.getBlock(), e.getDirection(), e.getBlocks());
    }

    private void pistonMoved(Block piston, BlockFace direction, List<Block> moved) {
        mark(piston);
        mark(piston.getRelative(direction));
        mark(piston.getRelative(direction.getOppositeFace()));
        for (Block b : moved) {
            mark(b);
            mark(b.getRelative(direction));
            mark(b.getRelative(direction.getOppositeFace()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        events.broadcast("join", LiveJson.player(e.getPlayer().getUniqueId(), e.getPlayer().getName()));
        discord.get().playerJoined(e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) swings.swing(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        swings.forget(e.getPlayer().getUniqueId());
        events.broadcast("quit", LiveJson.player(e.getPlayer().getUniqueId(), e.getPlayer().getName()));
        discord.get().playerLeft(e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        var loc = p.getLocation();
        events.broadcast("death", LiveJson.death(p.getUniqueId(), p.getName(),
                MinevisorLivePlugin.dimension(p.getWorld()), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        discord.get().playerDied(p.getName(), e.getDeathMessage(),
                MinevisorLivePlugin.dimension(p.getWorld()), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
