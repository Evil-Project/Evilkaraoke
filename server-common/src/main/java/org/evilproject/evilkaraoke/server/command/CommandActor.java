package org.evilproject.evilkaraoke.server.command;

import java.util.UUID;

public interface CommandActor {
    boolean isPlayer();

    UUID playerId();

    String name();

    boolean hasPermission(String permission);

    default String group() {
        return "default";
    }

    void sendMessage(String message);

    default void sendMessage(CommandMessage message) {
        sendMessage(message.plainText());
    }
}
