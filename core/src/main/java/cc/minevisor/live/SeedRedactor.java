package cc.minevisor.live;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Copies an NBT file with the world seed zeroed, so viewers of a server never
 * receive it. level.dat keeps it in Data.RandomSeed or
 * Data.WorldGenSettings.seed, newer worlds in data/minecraft/world_gen_settings.dat.
 */
final class SeedRedactor {

    private static final Set<String> SEED_TAGS = Set.of("RandomSeed", "seed");

    private SeedRedactor() {
    }

    /** The file with every seed long set to 0, or null when it isn't NBT this can copy. */
    static byte[] redact(byte[] file) {
        boolean gzip = file.length > 2 && (file[0] & 0xff) == 0x1f && (file[1] & 0xff) == 0x8b;
        try {
            InputStream raw = new ByteArrayInputStream(file);
            DataInputStream in = new DataInputStream(gzip ? new GZIPInputStream(raw) : raw);
            ByteArrayOutputStream buf = new ByteArrayOutputStream(file.length * 2);
            DataOutputStream out = gzip ? new DataOutputStream(new GZIPOutputStream(buf)) : new DataOutputStream(buf);
            int type = in.readUnsignedByte();
            if (type != 10) return null;
            out.writeByte(type);
            out.writeUTF(in.readUTF());
            copy(in, out, 10, false, 0);
            out.close();
            return buf.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private static void copy(DataInputStream in, DataOutputStream out, int type, boolean seed, int depth) throws IOException {
        if (depth > 64) throw new IOException("nested too deep");
        switch (type) {
            case 1 -> out.writeByte(in.readByte());
            case 2 -> out.writeShort(in.readShort());
            case 3 -> out.writeInt(in.readInt());
            case 4 -> {
                long v = in.readLong();
                out.writeLong(seed ? 0L : v);
            }
            case 5 -> out.writeFloat(in.readFloat());
            case 6 -> out.writeDouble(in.readDouble());
            case 7 -> {
                int n = length(in);
                out.writeInt(n);
                byte[] a = new byte[n];
                in.readFully(a);
                out.write(a);
            }
            case 8 -> out.writeUTF(in.readUTF());
            case 9 -> {
                int itemType = in.readUnsignedByte();
                int n = length(in);
                out.writeByte(itemType);
                out.writeInt(n);
                for (int i = 0; i < n; i++) copy(in, out, itemType, false, depth + 1);
            }
            case 10 -> {
                while (true) {
                    int t = in.readUnsignedByte();
                    out.writeByte(t);
                    if (t == 0) break;
                    String name = in.readUTF();
                    out.writeUTF(name);
                    copy(in, out, t, t == 4 && SEED_TAGS.contains(name), depth + 1);
                }
            }
            case 11 -> {
                int n = length(in);
                out.writeInt(n);
                for (int i = 0; i < n; i++) out.writeInt(in.readInt());
            }
            case 12 -> {
                int n = length(in);
                out.writeInt(n);
                for (int i = 0; i < n; i++) out.writeLong(in.readLong());
            }
            default -> throw new IOException("bad tag type " + type);
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 16_000_000) throw new IOException("bad length " + n);
        return n;
    }
}
