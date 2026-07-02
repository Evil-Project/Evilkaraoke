package org.evilproject.evilkaraoke.common.protocol;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;

public record AudioCommandPacket(
        AudioCommandType command,
        String sessionId,
        String playbackId,
        KaraokeTrack track,
        PlaybackTarget target,
        Duration offset,
        Instant serverTime,
        String reason,
        Duration fadeOut
) implements ProtocolPacket {
    public AudioCommandPacket {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playbackId, "playbackId");
        offset = offset == null ? Duration.ZERO : offset;
        serverTime = serverTime == null ? Instant.EPOCH : serverTime;
        fadeOut = fadeOut == null ? Duration.ZERO : fadeOut;
        reason = reason == null ? "" : reason;
    }
}
