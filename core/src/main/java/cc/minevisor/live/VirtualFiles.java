package cc.minevisor.live;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class VirtualFiles {

    public interface Layout {
        Path overworld();

        Path nether();

        Path end();

        Path serverRoot();
    }

    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String PLAYER_FILE = "(" + UUID + "|[A-Za-z0-9_]{1,16})";
    private static final String REGION = "(region|entities)/r\\.-?\\d+\\.-?\\d+\\.mca";

    private static final List<Pattern> ALLOWED_FILES = Stream.of(
            "level\\.dat",
            "usercache\\.json",
            "((DIM-1|DIM1)/)?" + REGION,
            "dimensions/[a-z0-9_.-]+/[a-z0-9_./-]+/" + REGION,
            "(playerdata|players/data|players)/" + PLAYER_FILE + "\\.dat",
            "(players/)?(stats|advancements)/" + PLAYER_FILE + "\\.json",
            "data/([a-z0-9_.-]+/)?[A-Za-z0-9_.-]+\\.dat"
    ).map(Pattern::compile).toList();

    private static final List<Pattern> ALLOWED_DIRS = Stream.of(
            "region|entities|playerdata|stats|advancements|data|data/[a-z0-9_.-]+",
            "(DIM-1|DIM1)(/(region|entities))?",
            "players(/(data|stats|advancements))?",
            "dimensions(/[a-z0-9_.-]+(/[a-z0-9_./-]+)?)?"
    ).map(Pattern::compile).toList();

    private final Layout layout;

    public VirtualFiles(Layout layout) {
        this.layout = layout;
    }

    public static boolean isAllowedFile(String path) {
        return ALLOWED_FILES.stream().anyMatch(p -> p.matcher(path).matches());
    }

    private static boolean isAllowedDir(String path) {
        return path.isEmpty() || ALLOWED_DIRS.stream().anyMatch(p -> p.matcher(path).matches());
    }

    private static String clean(String path) {
        if (path == null) return null;
        String p = path.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        for (String part : p.split("/")) {
            if (part.equals("..") || part.equals(".")) return null;
        }
        return p;
    }

    private Path physical(String virtual) {
        if (virtual.equals("usercache.json")) return layout.serverRoot().resolve(virtual);
        Path home = layout.overworld();
        if (under(virtual, "DIM-1") || under(virtual, "dimensions/minecraft/the_nether")) home = layout.nether();
        if (under(virtual, "DIM1") || under(virtual, "dimensions/minecraft/the_end")) home = layout.end();
        Path mapped = virtual.isEmpty() ? home : home.resolve(virtual);
        if (home != layout.overworld() && !Files.exists(mapped)) return layout.overworld().resolve(virtual);
        return mapped;
    }

    private static boolean under(String path, String dir) {
        return path.equals(dir) || path.startsWith(dir + "/");
    }

    public byte[] read(String path) throws IOException {
        String virtual = clean(path);
        if (virtual == null || !isAllowedFile(virtual)) return null;
        Path file = physical(virtual);
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return null;
        return Files.readAllBytes(file);
    }

    private Path region(String path) throws IOException {
        String virtual = clean(path);
        if (virtual == null || !virtual.endsWith(".mca") || !isAllowedFile(virtual)) return null;
        Path file = physical(virtual);
        return Files.isRegularFile(file) ? file : null;
    }

    public byte[] regionHeader(String path) throws IOException {
        Path file = region(path);
        if (file == null) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 8192) return null;
            byte[] header = new byte[8192];
            raf.readFully(header);
            return header;
        }
    }

    public byte[] regionChunk(String path, int index) throws IOException {
        if (index < 0 || index >= 1024) return null;
        Path file = region(path);
        if (file == null) return null;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < 8192) return new byte[0];
            raf.seek(index * 4L);
            int location = raf.readInt();
            long offset = (location >>> 8) * 4096L;
            int sectors = location & 0xff;
            if (offset == 0 || sectors == 0 || offset + 5 > raf.length()) return new byte[0];
            raf.seek(offset);
            int length = raf.readInt();
            if (length <= 0 || length > sectors * 4096 || offset + 4 + length > raf.length()) return new byte[0];
            byte[] out = new byte[4 + length];
            out[0] = (byte) (length >>> 24);
            out[1] = (byte) (length >>> 16);
            out[2] = (byte) (length >>> 8);
            out[3] = (byte) length;
            raf.readFully(out, 4, length);
            return out;
        }
    }

    public List<String> list(String dir, boolean directories) throws IOException {
        String virtual = clean(dir);
        List<String> out = new ArrayList<>();
        if (virtual == null || !isAllowedDir(virtual)) return out;

        if (directories && (virtual.equals("dimensions") || virtual.equals("dimensions/minecraft"))) {
            for (Path home : List.of(layout.overworld(), layout.nether(), layout.end())) {
                Path folder = home.resolve(virtual);
                if (!Files.isDirectory(folder)) continue;
                try (Stream<Path> entries = Files.list(folder)) {
                    for (Path entry : (Iterable<Path>) entries::iterator) {
                        String name = entry.getFileName().toString();
                        if (Files.isDirectory(entry) && isAllowedDir(virtual + "/" + name) && !out.contains(name)) {
                            out.add(name);
                        }
                    }
                }
            }
            return out;
        }

        Path folder = physical(virtual);
        if (!Files.isDirectory(folder)) return out;
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                String child = virtual.isEmpty() ? name : virtual + "/" + name;
                if (directories ? Files.isDirectory(entry) && isAllowedDir(child)
                        : Files.isRegularFile(entry) && isAllowedFile(child)) {
                    out.add(name);
                }
            }
        }
        if (virtual.isEmpty()) {
            if (!directories && !out.contains("usercache.json") && Files.isRegularFile(physical("usercache.json"))) {
                out.add("usercache.json");
            }
            for (String dim : List.of("DIM-1", "DIM1")) {
                if (directories && !out.contains(dim) && Files.isDirectory(physical(dim))) out.add(dim);
            }
        }
        return out;
    }
}
