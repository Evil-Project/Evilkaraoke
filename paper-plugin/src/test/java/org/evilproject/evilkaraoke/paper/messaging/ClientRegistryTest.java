package org.evilproject.evilkaraoke.paper.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.junit.jupiter.api.Test;

class ClientRegistryTest {
    @Test
    void gatesLyricsCommandsOnAdvertisedCapability() {
        UUID playerId = UUID.randomUUID();
        ClientRegistry registry = new ClientRegistry();

        registry.register(playerId, hello(false));
        assertFalse(registry.supportsLyrics(playerId));

        registry.register(playerId, hello(true));
        assertTrue(registry.supportsLyrics(playerId));
    }

    private static ClientHelloPacket hello(boolean supportsLyrics) {
        return new ClientHelloPacket(
                EvilKaraokeProtocol.VERSION,
                "test",
                "26.2",
                "fabric",
                List.of("opus"),
                true,
                false,
                true,
                supportsLyrics);
    }
}
