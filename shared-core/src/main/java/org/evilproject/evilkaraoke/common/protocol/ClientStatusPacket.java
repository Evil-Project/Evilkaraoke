package org.evilproject.evilkaraoke.common.protocol;

import java.time.Duration;
import java.util.Objects;

public record ClientStatusPacket(
        String playbackId,
        ClientPlaybackState state,
        Duration offset,
        String message,
        long streamBytesReceived,
        long streamBytesRead,
        int streamQueuedBytes,
        long streamMissingChunks
) implements ProtocolPacket {
    public ClientStatusPacket(String playbackId, ClientPlaybackState state, Duration offset, String message) {
        this(playbackId, state, offset, message, 0L, 0L, 0, 0L);
    }

    public ClientStatusPacket {
        Objects.requireNonNull(playbackId, "playbackId");
        Objects.requireNonNull(state, "state");
        offset = offset == null ? Duration.ZERO : offset;
        message = message == null ? "" : message;
        streamBytesReceived = Math.max(0L, streamBytesReceived);
        streamBytesRead = Math.max(0L, streamBytesRead);
        streamQueuedBytes = Math.max(0, streamQueuedBytes);
        streamMissingChunks = Math.max(0L, streamMissingChunks);
    }
}
