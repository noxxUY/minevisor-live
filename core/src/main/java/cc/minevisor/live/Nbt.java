package cc.minevisor.live;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Nbt {

    private Nbt() {
    }

    static Map<String, Object> readRoot(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        if (type != 10) throw new IOException("not a compound: " + type);
        in.readUTF();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) read(in, 10, 0);
        return root;
    }

    private static Object read(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("nested too deep");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] a = new byte[length(in)];
                in.readFully(a);
                yield a;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int itemType = in.readUnsignedByte();
                int n = length(in);
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) list.add(read(in, itemType, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String name = in.readUTF();
                    map.put(name, read(in, t, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int[] a = new int[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                yield a;
            }
            case 12 -> {
                long[] a = new long[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                yield a;
            }
            default -> throw new IOException("bad tag type " + type);
        };
    }

    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 16_000_000) throw new IOException("bad length " + n);
        return n;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    static long number(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }
}
