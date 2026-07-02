package org.evilproject.evilkaraoke.paper.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;

public final class KaraokeSession {
    public static final String GLOBAL_SESSION_ID = "global";

    private final Queue<QueuedTrack> requests = new ArrayDeque<>();
    private final Queue<QueuedTrack> randomTracks = new ArrayDeque<>();
    private PlaybackState state = PlaybackState.IDLE;
    private QueuedTrack current;
    private Instant startedAt;
    private Duration pausedOffset = Duration.ZERO;

    public synchronized void request(KaraokeTrack track, UUID requester, String requesterName) {
        requests.add(new QueuedTrack(track, requester, requesterName, Instant.now()));
    }

    public synchronized void addRandom(KaraokeTrack track) {
        randomTracks.add(new QueuedTrack(track, null, "Evilkaraoke", Instant.now()));
    }

    public synchronized Optional<QueuedTrack> next() {
        QueuedTrack next = requests.poll();
        if (next == null) {
            next = randomTracks.poll();
        }
        current = next;
        if (next == null) {
            state = PlaybackState.IDLE;
            startedAt = null;
            pausedOffset = Duration.ZERO;
            return Optional.empty();
        }
        state = PlaybackState.PLAYING;
        startedAt = Instant.now();
        pausedOffset = Duration.ZERO;
        return Optional.of(next);
    }

    public synchronized void pause() {
        if (state == PlaybackState.PLAYING && startedAt != null) {
            pausedOffset = offset();
            state = PlaybackState.PAUSED;
        }
    }

    public synchronized void resume() {
        if (state == PlaybackState.PAUSED) {
            startedAt = Instant.now().minus(pausedOffset);
            state = PlaybackState.PLAYING;
        }
    }

    public synchronized void stop() {
        current = null;
        requests.clear();
        randomTracks.clear();
        startedAt = null;
        pausedOffset = Duration.ZERO;
        state = PlaybackState.IDLE;
    }

    public synchronized PlaybackSnapshot snapshot() {
        return new PlaybackSnapshot(state, current, List.copyOf(requests), List.copyOf(randomTracks), offset());
    }

    public synchronized Duration offset() {
        if (state == PlaybackState.PAUSED) {
            return pausedOffset;
        }
        if (startedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, Instant.now());
    }

    public synchronized Optional<QueuedTrack> current() {
        return Optional.ofNullable(current);
    }

    public synchronized int randomQueueSize() {
        return randomTracks.size();
    }

    public synchronized List<QueuedTrack> queuedTracks() {
        List<QueuedTrack> all = new ArrayList<>(requests);
        all.addAll(randomTracks);
        return all;
    }

    public record QueuedTrack(KaraokeTrack track, UUID requester, String requesterName, Instant queuedAt) {
        public QueuedTrack {
            Objects.requireNonNull(track, "track");
            requesterName = requesterName == null || requesterName.isBlank() ? "Unknown" : requesterName;
            queuedAt = queuedAt == null ? Instant.now() : queuedAt;
        }
    }

    public record PlaybackSnapshot(
            PlaybackState state,
            QueuedTrack current,
            List<QueuedTrack> requests,
            List<QueuedTrack> randomTracks,
            Duration offset
    ) {
    }
}
