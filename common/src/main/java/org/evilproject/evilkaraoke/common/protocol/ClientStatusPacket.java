package org.evilproject.evilkaraoke.common.protocol;

import java.time.Duration;
import java.util.Objects;

public record ClientStatusPacket(
        String playbackId,
        ClientPlaybackState state,
        Duration offset,
        String message
) implements ProtocolPacket {
    public ClientStatusPacket {
        Objects.requireNonNull(playbackId, "playbackId");
        Objects.requireNonNull(state, "state");
        offset = offset == null ? Duration.ZERO : offset;
        message = message == null ? "" : message;
    }
}
