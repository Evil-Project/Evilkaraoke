package org.evilproject.evilkaraoke.paper.permission;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Wraps LuckPerms when available and falls back to Bukkit's permission system when absent.
 *
 * <p>All LuckPerms type references are isolated in the nested {@link LuckPermsHook} class so that
 * the JVM only attempts to load LP classes after we have confirmed LP is present. This avoids
 * {@link NoClassDefFoundError} when LuckPerms is not installed.</p>
 */
public final class PermissionService {

    // Null when LuckPerms is not installed. Typed as Object so this class has no direct LP reference.
    private final LuckPermsHook hook;

    public PermissionService(Logger logger) {
        LuckPermsHook detected = null;
        try {
            // Attempt to load and instantiate the hook only if LP's service is registered.
            // The try-catch guards against NoClassDefFoundError if LP is absent from the classpath.
            detected = LuckPermsHook.create(logger);
        } catch (NoClassDefFoundError ignored) {
            // LuckPerms not installed — hook stays null
        }
        this.hook = detected;
        if (this.hook == null) {
            logger.fine("LuckPerms not found — falling back to Bukkit permission system.");
        }
    }

    /**
     * Returns true if the sender has the given permission node.
     * Delegates to {@link CommandSender#hasPermission(String)}, which LuckPerms hooks automatically.
     */
    public boolean hasPermission(CommandSender sender, String permission) {
        return sender.hasPermission(permission);
    }

    /**
     * Returns the player's primary LuckPerms group name,
     * or {@code "default"} if LuckPerms is unavailable or the user cannot be found.
     */
    public String getGroup(Player player) {
        return hook != null ? hook.getGroup(player) : "default";
    }

    /** Returns true if LuckPerms integration is active. */
    public boolean isLuckPermsEnabled() {
        return hook != null;
    }
}
