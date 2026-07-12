package org.evilproject.evilkaraoke.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PaperMessagesTest {
    @Test
    void loadsMiniMessageFormattingAndPrefix() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("prefix", "<gold>[EK]</gold> ");
        config.set("missingPermission", "<red>No permission.</red>");

        PaperMessages messages = PaperMessages.from(config);

        assertEquals(MiniMessage.miniMessage().deserialize("<gold>[EK]</gold> <red>No permission.</red>"),
                messages.missingPermission());
    }

    @Test
    void suppliesDefaultsForMissingKeys() {
        PaperMessages messages = PaperMessages.from(new YamlConfiguration());

        assertEquals("[Evilkaraoke] Unknown Evilkaraoke subcommand. Try /ek help.",
                ChatMessages.plain(messages.unknownCommand()));
        assertEquals("[Evilkaraoke] Install the Evilkaraoke client mod to hear playback.",
                ChatMessages.plain(messages.clientRequired()));
    }
}
