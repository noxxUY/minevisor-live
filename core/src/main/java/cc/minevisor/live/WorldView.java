package cc.minevisor.live;

import java.util.concurrent.CompletableFuture;

public interface WorldView {

    record Patch(int width, int height, int[] colors, int[] heights) {
    }

    CompletableFuture<Patch> sample(int minX, int minZ, int width, int height);
}
