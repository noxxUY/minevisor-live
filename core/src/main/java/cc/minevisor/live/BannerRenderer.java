package cc.minevisor.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class BannerRenderer {

    static final int WIDTH = 2000;
    static final int HEIGHT = 500;
    private static final int BASE_SCALE = 4;
    private static final int MAX_SCALE = 12;
    private static final int MAP_WATER = 0x4040ff;
    private static final int NICE_WATER = 0x3b6fd8;
    private static final int MAP_ICE = 0xa0a0ff;
    private static final int NICE_ICE = 0xb4d2f0;
    private static final int CLUSTER_RADIUS = 160;
    private static final Color BACKGROUND = new Color(0x0e1015);

    static {
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    private final HttpClient http;
    private final Map<String, BufferedImage> heads = new ConcurrentHashMap<>();

    BannerRenderer(HttpClient http) {
        this.http = http;
    }

    private record Spot(String name, double x, double z) {
    }

    private static List<Spot> overworldPlayers(JsonArray players) {
        List<Spot> out = new ArrayList<>();
        for (JsonElement el : players) {
            JsonObject p = el.getAsJsonObject();
            if (!"Overworld".equals(p.has("dimension") ? p.get("dimension").getAsString() : null)) continue;
            out.add(new Spot(p.get("name").getAsString(), p.get("x").getAsDouble(), p.get("z").getAsDouble()));
        }
        return out;
    }

    private static double[] crowdCentre(List<Spot> spots) {
        List<Spot> best = List.of();
        for (Spot s : spots) {
            List<Spot> group = spots.stream()
                    .filter(o -> Math.hypot(o.x() - s.x(), o.z() - s.z()) <= CLUSTER_RADIUS).toList();
            if (group.size() > best.size()) best = group;
        }
        if (best.isEmpty()) return null;
        return new double[]{best.stream().mapToDouble(Spot::x).average().orElse(0),
                best.stream().mapToDouble(Spot::z).average().orElse(0)};
    }

    byte[] render(WorldView view, JsonObject info, int[] fallback, DiscordConfig config) throws Exception {
        JsonArray players = info.has("players") ? info.getAsJsonArray("players") : new JsonArray();
        List<Spot> spots = overworldPlayers(players);
        double[] centre = crowdCentre(spots);
        if (centre == null && fallback != null) centre = new double[]{fallback[0], fallback[1]};
        if (centre == null) centre = new double[]{0, 0};

        int scale = BASE_SCALE;
        int minX = (int) Math.floor(centre[0]) - WIDTH / scale / 2;
        int minZ = (int) Math.floor(centre[1]) - HEIGHT / scale / 2;
        WorldView.Patch patch = view.sample(minX, minZ, WIDTH / scale, HEIGHT / scale).get(8, TimeUnit.SECONDS);
        if (patch == null) return null;

        int span = loadedSpan(patch);
        if (span > 0 && span < patch.width() * 0.9) {
            scale = Math.min(MAX_SCALE, (int) Math.ceil((double) WIDTH / span));
            minX = (int) Math.floor(centre[0]) - WIDTH / scale / 2;
            minZ = (int) Math.floor(centre[1]) - HEIGHT / scale / 2;
            WorldView.Patch zoomed = view.sample(minX, minZ, WIDTH / scale, HEIGHT / scale).get(8, TimeUnit.SECONDS);
            if (zoomed != null) patch = zoomed;
        }

        int known = 0;
        for (int c : patch.colors()) if (c != 0) known++;
        if (known < patch.colors().length / 20) return null;

        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            drawMap(g, patch, scale);
            drawShade(g);
            List<int[]> placed = new ArrayList<>();
            for (Spot s : spots) {
                int px = (int) Math.round((s.x() - minX) * scale);
                int py = (int) Math.round((s.z() - minZ) * scale);
                if (px < -40 || py < -40 || px > WIDTH + 40 || py > HEIGHT + 40) continue;
                boolean moved = true;
                while (moved) {
                    moved = false;
                    for (int[] p : placed) {
                        if (Math.abs(p[0] - px) < 80 && Math.abs(p[1] - py) < 80) {
                            px = p[0] + 84;
                            moved = true;
                        }
                    }
                }
                placed.add(new int[]{px, py});
                drawHead(g, s.name(), px, py);
            }
            drawTitle(g, info, config, spots.isEmpty() && fallback != null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int loadedSpan(WorldView.Patch p) {
        int best = 0;
        for (int z = p.height() / 3; z < p.height() * 2 / 3; z++) {
            int lo = -1, hi = -1;
            for (int x = 0; x < p.width(); x++) {
                if (p.colors()[z * p.width() + x] == 0) continue;
                if (lo < 0) lo = x;
                hi = x;
            }
            if (lo >= 0) best = Math.max(best, hi - lo + 1);
        }
        return best;
    }

    private static void drawMap(Graphics2D g, WorldView.Patch p, int scale) {
        int[] row = new int[p.width() * scale];
        BufferedImage strip = new BufferedImage(p.width() * scale, 1, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < p.height(); z++) {
            for (int x = 0; x < p.width(); x++) {
                int i = z * p.width() + x;
                int rgb = p.colors()[i] == MAP_WATER ? NICE_WATER : p.colors()[i] == MAP_ICE ? NICE_ICE : p.colors()[i];
                int out;
                if (rgb == 0) {
                    out = BACKGROUND.getRGB() & 0xffffff;
                } else {
                    int h = p.heights()[i];
                    int north = z > 0 && p.colors()[i - p.width()] != 0 ? p.heights()[i - p.width()] : h;
                    int light = h > north ? 255 : h < north ? 180 : 220;
                    int r = ((rgb >> 16) & 255) * light / 255;
                    int gg = ((rgb >> 8) & 255) * light / 255;
                    int b = (rgb & 255) * light / 255;
                    out = (r << 16) | (gg << 8) | b;
                }
                for (int k = 0; k < scale; k++) row[x * scale + k] = out;
            }
            strip.setRGB(0, 0, row.length, 1, row, 0, row.length);
            for (int k = 0; k < scale; k++) g.drawImage(strip, 0, z * scale + k, null);
        }
    }

    private static void drawShade(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(8, 10, 14, 235), WIDTH * 0.55f, 0, new Color(8, 10, 14, 0)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setPaint(new GradientPaint(0, HEIGHT * 0.65f, new Color(8, 10, 14, 0), 0, HEIGHT, new Color(8, 10, 14, 170)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private BufferedImage head(String name) {
        BufferedImage cached = heads.get(name);
        if (cached != null) return cached;
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create("https://mc-heads.net/avatar/" + name + "/64.png"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) return null;
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(res.body()));
            if (img != null) heads.put(name, img);
            return img;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void drawHead(Graphics2D g, String name, int cx, int cy) {
        int size = 64;
        int x = cx - size / 2, y = cy - size / 2;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.SrcOver.derive(0.45f));
        g.setColor(Color.BLACK);
        g.fill(new RoundRectangle2D.Float(x - 2, y + 6, size + 12, size + 12, 18, 18));
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(x - 6, y - 6, size + 12, size + 12, 18, 18));
        BufferedImage face = head(name);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (face != null) {
            g.drawImage(face, x, y, size, size, null);
        } else {
            g.setColor(new Color(0x5ad67a));
            g.fillRect(x, y, size, size);
        }
        g.setStroke(new BasicStroke(1));
    }

    private static void drawTitle(Graphics2D g, JsonObject info, DiscordConfig config, boolean busiestPlace) {
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String motd = info.has("motd") && !info.get("motd").isJsonNull()
                    ? info.get("motd").getAsString().replaceAll("§.", "").split("\n")[0].trim() : "";
            String title = config.hasName() ? config.serverName()
                    : config.showMotd() && !motd.isEmpty() && !motd.equals("A Minecraft Server") ? motd : null;
            if (title != null && title.length() > 28) title = title.substring(0, 27) + "…";
            int online = info.has("players") ? info.getAsJsonArray("players").size() : 0;
            int max = info.has("maxPlayers") ? info.get("maxPlayers").getAsInt() : 0;

            int lineY = 310;
            if (title != null) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 84));
                g.setColor(new Color(0, 0, 0, 120));
                g.drawString(title, 104, 244);
                g.setColor(Color.WHITE);
                g.drawString(title, 100, 240);
            } else {
                lineY = 270;
            }

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
            g.setColor(online > 0 ? new Color(0x5ad67a) : new Color(0xb5bac1));
            g.fillOval(102, lineY - 24, 22, 22);
            g.setColor(new Color(0xe3e5e8));
            String line = online > 0 ? online + " / " + max + " online now"
                    : busiestPlace ? "Nobody online · where everyone hangs out" : "Nobody online right now";
            g.drawString(line, 140, lineY);

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            g.setColor(new Color(255, 255, 255, 150));
            g.drawString("minevisor live", 100, 430);
        } catch (RuntimeException | LinkageError | InternalError e) {
        }
    }
}
