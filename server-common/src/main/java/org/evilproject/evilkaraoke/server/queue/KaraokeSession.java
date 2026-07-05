package org.evilproject.evilkaraoke.server.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;

public final class KaraokeSession {
    public static final String GLOBAL_SESSION_ID = "global";

    private static final int MAX_HISTORY = 10;

    private final Deque<QueuedTrack> requests = new ArrayDeque<>();
    private final Deque<QueuedTrack> randomTracks = new ArrayDeque<>();
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
        startedAt = null;
        pausedOffset = Duration.ZERO;
        return Optional.of(next);
    }

    public synchronized void startTimer() {
        if (state == PlaybackState.PLAYING && startedAt == null) {
            startedAt = Instant.now();
        }
    }

    public synchronized Optional<QueuedTrack> previous() {
        if (history.isEmpty()) {
            return Optional.empty();
        }
        if (current != null) {
            requests.addFirst(current);
        }
        QueuedTrack prev = history.removeLast();
        current = prev;
        state = PlaybackState.PLAYING;
        startedAt = null;
        pausedOffset = Duration.ZERO;
        return Optional.of(prev);
    }

    public synchronized void pause() {
        if (state == PlaybackState.PLAYING) {
            pausedOffset = offset();
            state = PlaybackState.PAUSED;
        }
    }

    public synchronized void resume() {
        if (state == PlaybackState.PAUSED) {
            if (startedAt == null) {
                state = PlaybackState.PLAYING;
            } else {
                startedAt = Instant.now().minus(pausedOffset);
                state = PlaybackState.PLAYING;
            }
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

    public synchronized List<QueuedTrack> queuedTracks() {
        List<QueuedTrack> all = new ArrayList<>(requests);
        all.addAll(randomTracks);
        return all;
    }

    public synchronized Optional<QueuedTrack> removeAt(int position) {
        List<QueuedTrack> all = queuedTracks();
        if (position < 0 || position >= all.size()) {
            return Optional.empty();
        }
        QueuedTrack removed = all.get(position);
        if (position < requests.size()) {
            List<QueuedTrack> requestList = new ArrayList<>(requests);
            requestList.remove(position);
            requests.clear();
            requests.addAll(requestList);
        } else {
            int randomIndex = position - requests.size();
            List<QueuedTrack> randomList = new ArrayList<>(randomTracks);
            randomList.remove(randomIndex);
            randomTracks.clear();
            randomTracks.addAll(randomList);
        }
        return Optional.of(removed);
    }

    public synchronized List<QueuedTrack> removeAllQueued() {
        List<QueuedTrack> removed = queuedTracks();
        requests.clear();
        randomTracks.clear();
        return removed;
    }

    public synchronized List<QueuedTrack> removeRequestsByRequester(UUID requester) {
        if (requester == null) {
            return List.of();
        }
        List<QueuedTrack> removed = new ArrayList<>();
        List<QueuedTrack> remaining = new ArrayList<>();
        for (QueuedTrack queued : requests) {
            if (requester.equals(queued.requester())) {
                removed.add(queued);
            } else {
                remaining.add(queued);
            }
        }
        requests.clear();
        requests.addAll(remaining);
        return removed;
    }

    public synchronized Optional<QueuedTrack> moveRequest(int fromPosition, int toPosition) {
        List<QueuedTrack> requestList = new ArrayList<>(requests);
        if (fromPosition < 0 || fromPosition >= requestList.size() || toPosition < 0 || toPosition >= requestList.size()) {
            return Optional.empty();
        }
        QueuedTrack moved = requestList.remove(fromPosition);
        requestList.add(toPosition, moved);
        requests.clear();
        requests.addAll(requestList);
        return Optional.of(moved);
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
