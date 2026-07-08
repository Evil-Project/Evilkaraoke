package org.evilproject.evilkaraoke.common.audio;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;

public final class ServerStreamQualitySelector {
    public static final int HIGH_PING_MILLIS = 250;
    public static final int HIGH_BACKLOG_BYTES = 2 * 1024 * 1024;
    public static final int HIGH_READ_LAG_BYTES = 3 * 1024 * 1024;

    private ServerStreamQualitySelector() {
    }

    public static Selection select(KaraokeTrack track, Collection<ClientHealth> clients) {
        Objects.requireNonNull(track, "track");
        if (!hasDistinctFallback(track)) {
            return new Selection(track, StreamQuality.LOW_TRAFFIC, "single-asset");
        }
        // Clients receive server-decoded PCM, so the wire rate is (nearly) the same
        // whichever asset is decoded. High quality is therefore the default; only an
        // explicit distress signal from a client downgrades the selection.
        Collection<ClientHealth> health = clients == null ? List.of() : clients;
        for (ClientHealth client : health) {
            if (client != null && client.needsLowTraffic()) {
                return new Selection(track, StreamQuality.LOW_TRAFFIC, client.lowTrafficReason());
            }
        }
        return new Selection(highQualityTrack(track), StreamQuality.HIGH_QUALITY, "audience-healthy");
    }

    private static boolean hasDistinctFallback(KaraokeTrack track) {
        AudioAsset fallback = track.fallbackAsset();
        return fallback != null && !fallback.equals(track.primaryAsset());
    }

    private static KaraokeTrack highQualityTrack(KaraokeTrack track) {
        return new KaraokeTrack(
                track.id(),
                track.type(),
                track.title(),
                track.artist(),
                track.coverArtists(),
                track.fallbackAsset(),
                track.primaryAsset(),
                track.duration());
    }

    public enum StreamQuality {
        LOW_TRAFFIC,
        HIGH_QUALITY
    }

    public record Selection(KaraokeTrack track, StreamQuality quality, String reason) {
        public Selection {
            Objects.requireNonNull(track, "track");
            Objects.requireNonNull(quality, "quality");
            reason = reason == null || reason.isBlank() ? "unknown" : reason;
        }
    }

    public record ClientHealth(
            int pingMillis,
            long streamBytesReceived,
            long streamBytesRead,
            int streamQueuedBytes,
            long streamMissingChunks
    ) {
        public ClientHealth {
            streamBytesReceived = Math.max(0L, streamBytesReceived);
            streamBytesRead = Math.max(0L, streamBytesRead);
            streamQueuedBytes = Math.max(0, streamQueuedBytes);
            streamMissingChunks = Math.max(0L, streamMissingChunks);
        }

        public boolean needsLowTraffic() {
            return pingMillis >= HIGH_PING_MILLIS
                    || streamMissingChunks > 0
                    || streamQueuedBytes >= HIGH_BACKLOG_BYTES
                    || unreadBytes() >= HIGH_READ_LAG_BYTES;
        }

        public String lowTrafficReason() {
            if (pingMillis >= HIGH_PING_MILLIS) {
                return "high-ping";
            }
            if (streamMissingChunks > 0) {
                return "missing-stream-chunks";
            }
            if (streamQueuedBytes >= HIGH_BACKLOG_BYTES) {
                return "high-client-backlog";
            }
            if (unreadBytes() >= HIGH_READ_LAG_BYTES) {
                return "high-read-lag";
            }
            return "healthy";
        }

        private long unreadBytes() {
            return Math.max(streamQueuedBytes, streamBytesReceived - streamBytesRead);
        }
    }
}
