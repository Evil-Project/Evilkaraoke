package org.evilproject.evilkaraoke.server.messaging;

import java.time.Instant;
import java.util.UUID;

import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;

public record ClientSession(
        UUID playerId,
        ClientHelloPacket hello,
        Instant connectedAt,
        String lastState,
        String lastMessage
) {
}
