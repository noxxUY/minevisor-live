package cc.minevisor.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class TokenStore {

    private final SecureRandom random = new SecureRandom();
    private final Path file;
    private volatile String token;

    public TokenStore(Path file) {
        this.file = file;
        this.token = load();
        if (token == null) rotate();
    }

    public String token() {
        return token;
    }

    public boolean check(String candidate) {
        if (candidate == null || candidate.isEmpty()) return false;
        return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    public String rotate() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        save();
        return token;
    }

    private String load() {
        if (file == null || !Files.exists(file)) return null;
        try {
            String t = JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("token").getAsString();
            return t.length() >= 16 ? t : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private synchronized void save() {
        if (file == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("token", token);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString());
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
        }
    }
}
