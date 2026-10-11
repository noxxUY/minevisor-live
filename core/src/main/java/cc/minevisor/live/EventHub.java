package cc.minevisor.live;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class EventHub {

    private static final byte[] PING = ": ping\n\n".getBytes(StandardCharsets.UTF_8);

    public interface Remote {
        void event(String event, String json);

        int viewers();
    }

    private final Set<BlockingQueue<byte[]>> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, byte[]> latest = new ConcurrentHashMap<>();
    private final Map<String, String> latestJson = new ConcurrentHashMap<>();
    private volatile Remote remote;

    public void setRemote(Remote remote) {
        this.remote = remote;
    }

    public int clientCount() {
        Remote r = remote;
        return clients.size() + (r == null ? 0 : r.viewers());
    }

    public Map<String, String> latestStates() {
        return Map.copyOf(latestJson);
    }

    private static byte[] frame(String event, String json) {
        return ("event: " + event + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    public void broadcast(String event, String json) {
        byte[] bytes = frame(event, json);
        for (BlockingQueue<byte[]> queue : clients) {
            queue.offer(bytes);
        }
        Remote r = remote;
        if (r != null) r.event(event, json);
    }

    public void publishState(String event, String json) {
        byte[] bytes = frame(event, json);
        latest.put(event, bytes);
        latestJson.put(event, json);
        for (BlockingQueue<byte[]> queue : clients) {
            queue.offer(bytes);
        }
        Remote r = remote;
        if (r != null) r.event(event, json);
    }

    void serve(OutputStream out) throws IOException {
        BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(512);
        latest.values().forEach(queue::offer);
        clients.add(queue);
        try {
            while (true) {
                byte[] next = queue.poll(15, TimeUnit.SECONDS);
                out.write(next != null ? next : PING);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(queue);
        }
    }
}
