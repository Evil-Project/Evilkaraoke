package org.evilproject.evilkaraoke.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeApiUnavailableException;

final class ChatMessages {
    private ChatMessages() {
    }

    static Component error(String prefix, Throwable error) {
        Throwable cause = ErrorDetails.unwrap(error);
        String label = cause instanceof NeurokaraokeApiUnavailableException
                ? "Neurokaraoke API unavailable"
                : prefix;
        return Component.text(label + ": " + ErrorDetails.safe(cause), NamedTextColor.RED);
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
