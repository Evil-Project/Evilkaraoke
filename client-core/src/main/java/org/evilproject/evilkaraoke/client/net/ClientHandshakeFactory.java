package org.evilproject.evilkaraoke.client.net;

import java.util.List;

import org.evilproject.evilkaraoke.common.protocol.ClientHelloPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;

public final class ClientHandshakeFactory {
    private ClientHandshakeFactory() {
    }

    public static ClientHelloPacket create(String modVersion, String minecraftVersion, String loader) {
        return new ClientHelloPacket(
                EvilKaraokeProtocol.VERSION,
                modVersion,
                minecraftVersion,
                loader,
                List.of("opus", "ogg", "mp3", "stream"),
                true,
                false,
                true
        );
    }
}
