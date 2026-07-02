package org.evilproject.evilkaraoke.paper.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.entity.Player;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;

public final class KaraokeSession {
    public static final String GLOBAL_SESSION_ID = "global";

    private static final int MAX_HISTORY = 10;

    private final Queue<QueuedTrack> requests = new ArrayDeque<>();
    private final Queue<QueuedTrack> randomTracks = new ArrayDeque<>();
    /** Tracks that have already played, most-recent last. Bounded to MAX_HISTORY entries. */
    private final Deque<QueuedTrack> history = new ArrayDeque<>();
    private PlaybackState state = PlaybackState.IDLE;
    private QueuedTrack current;
    private Instant startedAt;
    private Duration pausedOffset = Duration.ZERO;
    private float volume = 1.0f;

    public synchronized void request(KaraokeTrack track, UUID requester, String requesterName) {
        requests.add(new QueuedTrack(track, requester, requesterName, Instant.now()));
    }

    public synchronized void addRandom(KaraokeTrack track) {
        randomTracks.add(new QueuedTrack(track, null, "Evilkaraoke", Instant.now()));
    }

    public synchronized Optional<QueuedTrack> next() {
        if (current != null) {
            history.addLast(current);
            if (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
        }
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

    /**
     * Restores the most-recently-played track as {@code current} so the coordinator
     * can re-broadcast it from the start. Returns empty when history is exhausted.
     */
    public synchronized Optional<QueuedTrack> previous() {
        if (history.isEmpty()) {
            return Optional.empty();
        }
        // If something is currently playing, push it back to the front of requests
        // so it isn't lost — the user can still get back to it with /ek next.
        if (current != null) {
            requests.add(current);
        }
        QueuedTrack prev = history.removeLast();
        current = prev;
        state = PlaybackState.PLAYING;
        startedAt = Instant.now();
        pausedOffset = Duration.ZERO;
        return Optional.of(prev);
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
        history.clear();
        startedAt = null;
        pausedOffset = Duration.ZERO;
        state = PlaybackState.IDLE;
    }

    public synchronized boolean skip() {
        if (current != null) {
            current = null;
            startedAt = null;
            pausedOffset = Duration.ZERO;
            state = PlaybackState.IDLE;
            return true;
        }
        return false;
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

    public synchronized int requestQueueSize() {
        return requests.size();
    }

    public synchronized List<QueuedTrack> queuedTracks() {
        List<QueuedTrack> all = new ArrayList<>(requests);
        all.addAll(randomTracks);
        return all;
    }

    public synchronized PlaybackState state() {
        return state;
    }

    public synchronized float getVolume() {
        return volume;
    }

    public synchronized void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(2.0f, volume));
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
