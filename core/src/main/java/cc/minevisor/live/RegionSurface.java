package cc.minevisor.live;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

final class RegionSurface {

    static final class Unsupported extends IOException {
        Unsupported(String why) {
            super(why);
        }
    }

    private static final int NONE = 0xFFFF;
    private static final Pattern STRUCTURE = Pattern.compile(
            "village|fortress|stronghold|monument|mansion|ancient_city|bastion|end_city|trail_ruins|trial_chambers"
                    + "|pillager|ruined_portal|desert_pyramid|jungle_pyramid|igloo|swamp_hut|shipwreck|ocean_ruin|outpost");

    private static final Map<String, byte[]> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 24;
        }
    };

    private RegionSurface() {
    }

    static byte[] of(String path, byte[] region) throws IOException {
        if (region.length < 8192) throw new Unsupported("empty region");
        String key = path + "#" + java.util.Arrays.hashCode(java.util.Arrays.copyOf(region, 8192));
        synchronized (CACHE) {
            byte[] hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        byte[] built = build(path, region);
        synchronized (CACHE) {
            CACHE.put(key, built);
        }
        return built;
    }

    private record Structure(String id, int x, int z) {
    }

    private static byte[] build(String path, byte[] region) throws IOException {
        boolean nether = path.contains("DIM-1") || path.contains("the_nether");
        boolean end = !nether && (path.contains("DIM1/") || path.contains("the_end"));
        int minY = nether || end ? 0 : -64;
        int regionX = 0, regionZ = 0;
        var m = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca$").matcher(path);
        if (m.find()) {
            regionX = Integer.parseInt(m.group(1));
            regionZ = Integer.parseInt(m.group(2));
        }

        Map<String, Integer> blockIds = new HashMap<>();
        List<String> blocks = new ArrayList<>();
        Map<String, Integer> biomeIds = new HashMap<>();
        List<String> biomes = new ArrayList<>();
        short[] blockPlane = new short[512 * 512];
        short[] biomePlane = new short[512 * 512];
        short[] yPlane = new short[512 * 512];
        java.util.Arrays.fill(blockPlane, (short) NONE);
        java.util.Arrays.fill(biomePlane, (short) NONE);
        boolean[] present = new boolean[1024];
        double[] inhabited = new double[1024];
        List<List<Structure>> structures = new ArrayList<>(1024);
        for (int i = 0; i < 1024; i++) structures.add(List.of());

        ByteBuffer header = ByteBuffer.wrap(region);
        for (int index = 0; index < 1024; index++) {
            int location = header.getInt(index * 4);
            int offset = (location >>> 8) * 4096;
            if (offset == 0 || (location & 0xff) == 0 || offset + 5 > region.length) continue;
            Map<String, Object> root = readChunk(region, offset);
            if (root == null) continue;
            if (root.containsKey("Level") || !root.containsKey("sections")) throw new Unsupported("pre-1.18 chunk");
            Section[] sections = sections(root);
            if (sections.length == 0) continue;
            int minSection = Integer.MAX_VALUE, maxSection = Integer.MIN_VALUE;
            for (Section s : sections) {
                minSection = Math.min(minSection, s.y);
                maxSection = Math.max(maxSection, s.y);
            }
            Section[] byY = new Section[maxSection - minSection + 1];
            for (Section s : sections) if (byY[s.y - minSection] == null) byY[s.y - minSection] = s;
            int cx = index & 31, cz = index >> 5;
            present[index] = true;
            inhabited[index] = Nbt.number(root.get("InhabitedTime"), 0);
            structures.set(index, structures(root, regionX * 32 + cx, regionZ * 32 + cz));
            long[] heights = heightmap(root);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int column = x + z * 16;
                    int y = heights != null ? (int) unpack(heights, 256, 9, column) - 1 + minY : nether ? 127 : 80;
                    int maxSteps = nether ? 128 : Math.max(48, y - minY + 8);
                    int block = NONE;
                    int biome = NONE;
                    int foundY = y;
                    for (int step = 0; step < maxSteps; step++) {
                        int sy = Math.floorDiv(y, 16) - minSection;
                        Section s = sy >= 0 && sy < byY.length ? byY[sy] : null;
                        int entry = s == null ? -1 : s.entry(x, y - s.y * 16, z);
                        if (entry >= 0 && nether && s.bedrock[entry] && y > 96) {
                            y--;
                            continue;
                        }
                        if (entry < 0 || s.invisible[entry]) {
                            y--;
                            if (y < minY) break;
                            continue;
                        }
                        block = s.blockId(entry, blockIds, blocks);
                        biome = s.biomeId(x, y - s.y * 16, z, biomeIds, biomes);
                        foundY = y;
                        break;
                    }
                    int px = cx * 16 + x, pz = cz * 16 + z;
                    int i = pz * 512 + px;
                    if (block != NONE) blockPlane[i] = (short) block;
                    if (biome != NONE) biomePlane[i] = (short) biome;
                    yPlane[i] = (short) foundY;
                }
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.write("MVS1".getBytes(StandardCharsets.US_ASCII));
            strings(out, blocks);
            strings(out, biomes);
            for (int index = 0; index < 1024; index++) {
                out.writeByte(present[index] ? 1 : 0);
                if (!present[index]) continue;
                out.writeDouble(inhabited[index]);
                List<Structure> list = structures.get(index);
                out.writeByte(Math.min(255, list.size()));
                for (int s = 0; s < Math.min(255, list.size()); s++) {
                    out.writeUTF(list.get(s).id());
                    out.writeInt(list.get(s).x());
                    out.writeInt(list.get(s).z());
                }
            }
            for (short v : blockPlane) out.writeShort(v);
            for (short v : biomePlane) out.writeShort(v);
            for (short v : yPlane) out.writeShort(v);
        }
        return bytes.toByteArray();
    }

    private static void strings(DataOutputStream out, List<String> list) throws IOException {
        out.writeShort(list.size());
        for (String s : list) out.writeUTF(s);
    }

    private static int id(Map<String, Integer> ids, List<String> list, String name) {
        Integer id = ids.get(name);
        if (id != null) return id;
        if (list.size() >= NONE - 1) return NONE;
        ids.put(name, list.size());
        list.add(name);
        return list.size() - 1;
    }

    private static boolean invisible(String name) {
        return switch (name) {
            case "air", "cave_air", "void_air", "light", "barrier", "structure_void" -> true;
            default -> false;
        };
    }

    private static Map<String, Object> readChunk(byte[] region, int offset) throws IOException {
        int length = ByteBuffer.wrap(region, offset, 4).getInt();
        if (length <= 1 || offset + 4 + length > region.length) return null;
        int compression = region[offset + 4] & 0xff;
        InputStream raw = new ByteArrayInputStream(region, offset + 5, length - 1);
        InputStream in = switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> throw new Unsupported("chunk compression " + compression);
        };
        try (DataInputStream data = new DataInputStream(new java.io.BufferedInputStream(in))) {
            return Nbt.readRoot(data);
        }
    }

    /**
     * A chunk section with its palettes worked out once: which entries are
     * invisible, and their ids in the region's lists (given out on first
     * use, so the lists only hold what's on the surface).
     */
    private static final class Section {
        final int y;
        final String[] names;
        final long[] data;
        final int bits;
        final boolean[] invisible;
        final boolean[] bedrock;
        final int[] ids;
        final String[] biomes;
        final long[] biomeData;
        final int biomeBits;
        final int[] biomeIds;

        Section(int y, String[] names, long[] data, String[] biomes, long[] biomeData) {
            this.y = y;
            this.names = names;
            this.data = data;
            this.bits = Math.max(4, bitsFor(names.length));
            this.invisible = new boolean[names.length];
            this.bedrock = new boolean[names.length];
            this.ids = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                String plain = names[i].startsWith("minecraft:") ? names[i].substring(10) : names[i];
                invisible[i] = invisible(plain);
                bedrock[i] = plain.equals("bedrock");
                ids[i] = -1;
            }
            this.biomes = biomes;
            this.biomeData = biomeData;
            this.biomeBits = Math.max(1, bitsFor(biomes.length));
            this.biomeIds = new int[biomes.length];
            java.util.Arrays.fill(biomeIds, -1);
        }

        /** Palette entry at a local position, or -1 past the palette. */
        int entry(int x, int ly, int z) {
            int index = names.length == 1 ? 0 : (int) unpack(data, 4096, bits, (ly << 8) | (z << 4) | x);
            return index < names.length ? index : -1;
        }

        int blockId(int entry, Map<String, Integer> idMap, List<String> list) {
            int id = ids[entry];
            if (id < 0) id = ids[entry] = id(idMap, list, names[entry]);
            return id;
        }

        int biomeId(int x, int ly, int z, Map<String, Integer> idMap, List<String> list) {
            if (biomes.length == 0) return NONE;
            int index = biomes.length == 1 ? 0 : (int) unpack(biomeData, 64, biomeBits, ((ly >> 2) << 4) | ((z >> 2) << 2) | (x >> 2));
            if (index >= biomes.length || biomes[index].isEmpty()) return NONE;
            int id = biomeIds[index];
            if (id < 0) id = biomeIds[index] = id(idMap, list, biomes[index]);
            return id;
        }
    }

    private static String paletteName(Object entry) {
        if (entry instanceof String s) return s;
        Map<String, Object> c = Nbt.compound(entry);
        if (c == null) return "minecraft:air";
        for (String key : new String[]{"Name", "name", "id", ""}) {
            if (c.get(key) instanceof String s) return s;
        }
        return "minecraft:air";
    }

    private static String[] names(Object palette) {
        List<Object> list = Nbt.list(palette);
        String[] out = new String[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = paletteName(list.get(i));
        return out;
    }

    private static Section[] sections(Map<String, Object> root) {
        List<Section> out = new ArrayList<>();
        for (Object o : Nbt.list(root.get("sections"))) {
            Map<String, Object> s = Nbt.compound(o);
            if (s == null || !(s.get("Y") instanceof Number y)) continue;
            Map<String, Object> states = Nbt.compound(s.get("block_states"));
            if (states == null) continue;
            String[] palette = names(states.get("palette"));
            if (palette.length == 0) continue;
            Map<String, Object> b = Nbt.compound(s.get("biomes"));
            String[] biomePalette = b == null ? new String[0] : names(b.get("palette"));
            out.add(new Section(y.intValue(), palette, longs(states.get("data")),
                    biomePalette, b == null ? new long[0] : longs(b.get("data"))));
        }
        return out.toArray(new Section[0]);
    }

    private static long[] longs(Object o) {
        return o instanceof long[] a ? a : new long[0];
    }

    private static long[] heightmap(Map<String, Object> root) {
        Map<String, Object> maps = Nbt.compound(root.get("Heightmaps"));
        if (maps == null) return null;
        for (String key : new String[]{"WORLD_SURFACE", "MOTION_BLOCKING", "WORLD_SURFACE_WG"}) {
            if (maps.get(key) instanceof long[] a && a.length > 0) return a;
        }
        return null;
    }

    private static int bitsFor(int size) {
        return size <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(size - 1);
    }

    private static long unpack(long[] longs, int count, int bits, int index) {
        if (bits <= 0 || longs.length == 0) return 0;
        long mask = (1L << bits) - 1;
        int perLong = 64 / bits;
        if (longs.length == (count + perLong - 1) / perLong) {
            int li = index / perLong;
            return li < longs.length ? (longs[li] >>> ((index % perLong) * bits)) & mask : 0;
        }
        long start = (long) index * bits;
        int li = (int) (start >>> 6);
        int offset = (int) (start & 63);
        if (li >= longs.length) return 0;
        long value = longs[li] >>> offset;
        if (offset + bits > 64 && li + 1 < longs.length) value |= longs[li + 1] << (64 - offset);
        return value & mask;
    }

    private static List<Structure> structures(Map<String, Object> root, int chunkX, int chunkZ) {
        Map<String, Object> all = Nbt.compound(root.get("structures"));
        Map<String, Object> starts = all == null ? null : Nbt.compound(all.get("starts"));
        if (starts == null || starts.isEmpty()) return List.of();
        List<Structure> out = new ArrayList<>();
        for (var e : starts.entrySet()) {
            if (!STRUCTURE.matcher(e.getKey()).find()) continue;
            Map<String, Object> rec = Nbt.compound(e.getValue());
            if (rec == null) continue;
            Object bb = rec.containsKey("bb") ? rec.get("bb") : rec.get("BB");
            int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
            if (bb instanceof int[] a && a.length >= 6) {
                x = Math.floorDiv(a[0] + a[3], 2);
                z = Math.floorDiv(a[2] + a[5], 2);
            }
            out.add(new Structure(e.getKey(), x, z));
        }
        return out;
    }
}
