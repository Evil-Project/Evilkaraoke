package org.evilproject.evilkaraoke.common.protocol;

import java.util.List;
import java.util.Objects;

public record ClientHelloPacket(
        int protocolVersion,
        String modVersion,
        String minecraftVersion,
        String loader,
        List<String> supportedCodecs,
        boolean supportsPositionalAudio,
        boolean supportsPitch,
        boolean supportsRadioStreams,
        boolean supportsLyrics
) implements ProtocolPacket {
    public ClientHelloPacket {
        Objects.requireNonNull(modVersion, "modVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(loader, "loader");
        supportedCodecs = List.copyOf(Objects.requireNonNull(supportedCodecs, "supportedCodecs"));
    }
}
