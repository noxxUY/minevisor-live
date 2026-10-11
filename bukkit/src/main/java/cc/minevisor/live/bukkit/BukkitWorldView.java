package cc.minevisor.live.bukkit;

import cc.minevisor.live.WorldView;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.CompletableFuture;

final class BukkitWorldView implements WorldView {

    private static final long BUDGET_NANOS = 2_000_000;

    private final Plugin plugin;

    BukkitWorldView(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Patch> sample(int minX, int minZ, int width, int height) {
        CompletableFuture<Patch> result = new CompletableFuture<>();
        int[] colors = new int[width * height];
        int[] heights = new int[width * height];
        new BukkitRunnable() {
            private int row;

            @Override
            public void run() {
                try {
                    World world = Bukkit.getWorlds().stream()
                            .filter(w -> w.getEnvironment() == World.Environment.NORMAL).findFirst().orElse(null);
                    if (world == null) row = height;
                    long until = System.nanoTime() + BUDGET_NANOS;
                    while (row < height && System.nanoTime() < until) {
                        int z = row++;
                        for (int x = 0; x < width; x++) {
                            int wx = minX + x, wz = minZ + z;
                            if (!world.isChunkLoaded(wx >> 4, wz >> 4)) continue;
                            Block top = world.getHighestBlockAt(wx, wz);
                            int i = z * width + x;
                            colors[i] = top.getBlockData().getMapColor().asRGB();
                            heights[i] = top.getY();
                        }
                    }
                    if (row >= height) {
                        cancel();
                        result.complete(new Patch(width, height, colors, heights));
                    }
                } catch (RuntimeException e) {
                    cancel();
                    result.completeExceptionally(e);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return result;
    }
}
