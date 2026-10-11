package cc.minevisor.live.fabric;

import cc.minevisor.live.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.concurrent.CompletableFuture;

final class FabricWorldView implements WorldView {

    private static final long BUDGET_NANOS = 2_000_000;

    private final MinecraftServer server;

    FabricWorldView(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Patch> sample(int minX, int minZ, int width, int height) {
        CompletableFuture<Patch> result = new CompletableFuture<>();
        int[] colors = new int[width * height];
        int[] heights = new int[width * height];
        int[] row = {0};
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Runnable step = new Runnable() {
            @Override
            public void run() {
                try {
                    ServerLevel level = server.overworld();
                    long until = System.nanoTime() + BUDGET_NANOS;
                    while (row[0] < height && System.nanoTime() < until) {
                        int z = row[0]++;
                        for (int x = 0; x < width; x++) {
                            int wx = minX + x, wz = minZ + z;
                            if (!level.hasChunk(wx >> 4, wz >> 4)) continue;
                            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                            pos.set(wx, y, wz);
                            BlockState state = level.getBlockState(pos);
                            int i = z * width + x;
                            colors[i] = state.getMapColor(level, pos).col;
                            heights[i] = y;
                        }
                    }
                    if (row[0] < height) server.execute(this);
                    else result.complete(new Patch(width, height, colors, heights));
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            }
        };
        server.execute(step);
        return result;
    }
}
