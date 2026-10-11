package cc.minevisor.live.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

final class BukkitPlayerSaver {

    private final Logger log;
    private Method handle;
    private Object playerList;
    private Method save;
    private boolean resolved;

    BukkitPlayerSaver(Logger log) {
        this.log = log;
    }

    void save(Player player) {
        try {
            if (!resolved) resolve(player);
            if (save != null) {
                save.invoke(playerList, handle.invoke(player));
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.fine("Full player save unavailable, saving player data only: " + e);
            save = null;
        }
        player.saveData();
    }

    private void resolve(Player player) throws ReflectiveOperationException {
        resolved = true;
        handle = player.getClass().getMethod("getHandle");
        Class<?> serverPlayer = handle.getReturnType();
        Object server = Bukkit.getServer();
        playerList = server.getClass().getMethod("getHandle").invoke(server);
        for (Class<?> c = playerList.getClass(); c != null && save == null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("save") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(serverPlayer)) {
                    m.setAccessible(true);
                    save = m;
                    break;
                }
            }
        }
    }
}
