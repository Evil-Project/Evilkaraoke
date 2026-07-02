package org.evilproject.evilkaraoke.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class ChatMessages {
    private ChatMessages() {
    }

    static Component error(String prefix, Throwable error) {
        return Component.text(prefix + ": " + ErrorDetails.safe(error), NamedTextColor.RED);
    }
}
