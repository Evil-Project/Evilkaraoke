package org.evilproject.evilkaraoke.paper.command;

import java.io.File;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperMessages {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Component prefix;
    private final Component missingPermission;
    private final Component playerOnly;
    private final Component unknownCommand;
    private final Component clientRequired;

    private PaperMessages(Component prefix,
                          Component missingPermission,
                          Component playerOnly,
                          Component unknownCommand,
                          Component clientRequired) {
        this.prefix = prefix;
        this.missingPermission = missingPermission;
        this.playerOnly = playerOnly;
        this.unknownCommand = unknownCommand;
        this.clientRequired = clientRequired;
    }

    public static PaperMessages load(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        return from(YamlConfiguration.loadConfiguration(file));
    }

    static PaperMessages from(ConfigurationSection config) {
        return new PaperMessages(
                parse(config.getString("prefix", "<gold>[Evilkaraoke]</gold> ")),
                parse(config.getString("missingPermission", "<red>You do not have permission to use that Evilkaraoke command.</red>")),
                parse(config.getString("playerOnly", "<red>That command must be run by a player.</red>")),
                parse(config.getString("unknownCommand", "<red>Unknown Evilkaraoke subcommand. Try <yellow>/ek help</yellow>.</red>")),
                parse(config.getString("clientRequired", "<yellow>Install the Evilkaraoke client mod to hear playback.</yellow>"))
        );
    }

    public Component missingPermission() {
        return prefixed(missingPermission);
    }

    public Component playerOnly() {
        return prefixed(playerOnly);
    }

    public Component unknownCommand() {
        return prefixed(unknownCommand);
    }

    public Component clientRequired() {
        return prefixed(clientRequired);
    }

    private Component prefixed(Component message) {
        return prefix.append(message);
    }

    private static Component parse(String value) {
        return MINI_MESSAGE.deserialize(value);
    }
}
