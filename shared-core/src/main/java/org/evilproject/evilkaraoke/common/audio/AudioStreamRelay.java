package org.evilproject.evilkaraoke.common.audio;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;

public interface AudioStreamRelay {
    CompletableFuture<Void> relay(
            String sessionId,
            String playbackId,
            KaraokeTrack track,
            PlaybackTarget target,
            Duration offset,
            Instant serverTime,
            String reason,
            Consumer<ProtocolPacket> broadcaster,
            BooleanSupplier shouldContinue);
}
