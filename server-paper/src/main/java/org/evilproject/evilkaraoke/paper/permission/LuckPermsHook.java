package org.evilproject.evilkaraoke.paper.permission;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;

/**
 * Holds all direct references to the LuckPerms API.
 *
 * <p>This class is intentionally separate from {@link PermissionService} so the JVM only loads
 * LP classes when LP is confirmed present. Never reference this class by name from
 * {@link PermissionService} except inside a try-catch for {@link NoClassDefFoundError}.</p>
 */
final class LuckPermsHook {

    private final LuckPerms api;

    private LuckPermsHook(LuckPerms api) {
        this.api = api;
    }

    /**
     * Returns a hook if LuckPerms is registered as a service, or {@code null} if not.
     * Must be called from inside a {@code try { } catch (NoClassDefFoundError) { }} block.
     */
    static LuckPermsHook create(Logger logger) {
        RegisteredServiceProvider<LuckPerms> provider =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            return null;
        }
        logger.info("LuckPerms detected — using LuckPerms integration.");
        return new LuckPermsHook(provider.getProvider());
    }

    String getGroup(Player player) {
        User user = api.getPlayerAdapter(Player.class).getUser(player);
        if (user == null) {
            return "default";
        }
        String group = user.getPrimaryGroup();
        return group != null ? group : "default";
    }

    int getMetaInt(Player player, String key, int defaultValue) {
        User user = api.getPlayerAdapter(Player.class).getUser(player);
        if (user == null) {
            return defaultValue;
        }
        CachedMetaData meta = user.getCachedData().getMetaData();
        String value = meta.getMetaValue(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
