package org.evilproject.evilkaraoke.paper.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;

public final class KaraokeSession {
    public static final String GLOBAL_SESSION_ID = "global";

    private static final int MAX_HISTORY = 10;

    private final Deque<QueuedTrack> requests = new ArrayDeque<>();
    private final Deque<QueuedTrack> randomTracks = new ArrayDeque<>();
    /** Tracks that have already played, most-recent last. Bounded to MAX_HISTORY entries. */
    private final Deque<QueuedTrack> history = new ArrayDeque<>();
    private PlaybackState state = PlaybackState.IDLE;
    private QueuedTrack current;
    private Instant startedAt;
    private Duration pausedOffset = Duration.ZERO;
    private float volume = 1.0f;
    private boolean randomEnabled;
    private boolean loopQueueEnabled;
    private QueuedTrack singleLoopTrack;

    public synchronized void request(KaraokeTrack track, UUID requester, String requesterName) {
        requests.add(new QueuedTrack(track, requester, requesterName, Instant.now()));
    }

    public synchronized void addRandom(KaraokeTrack track) {
        randomTracks.add(new QueuedTrack(track, null, "Evilkaraoke", Instant.now()));
    }

    public synchronized Optional<QueuedTrack> next() {
        if (current != null && sameQueuedTrack(current, singleLoopTrack)) {
            state = PlaybackState.PLAYING;
            startedAt = null;
            pausedOffset = Duration.ZERO;
            return Optional.of(current);
        }
        QueuedTrack previous = current;
        if (previous != null) {
            history.addLast(previous);
            if (history.size() > MAX_HISTORY) {
                history.removeFirst();
            }
            if (loopQueueEnabled) {
                requests.addLast(previous);
            }
        }
        QueuedTrack next = pollNext();
        current = next;
        if (next == null) {
            state = PlaybackState.IDLE;
            startedAt = null;
            pausedOffset = Duration.ZERO;
            return Optional.empty();
        }
        state = PlaybackState.PLAYING;
        // Don't start the timer yet - wait for client confirmation via startTimer()
        startedAt = null;
        pausedOffset = Duration.ZERO;
        return Optional.of(next);
    }

    /**
     * Starts the playback timer. Called by the coordinator when the first client
     * reports PLAYING status, ensuring the timer reflects actual playback time
     * rather than including the client's buffering delay.
     */
    public synchronized void startTimer() {
        if (state == PlaybackState.PLAYING && startedAt == null) {
            startedAt = Instant.now();
        }
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
            requests.addFirst(current);
        }
        QueuedTrack prev = history.removeLast();
        current = prev;
        state = PlaybackState.PLAYING;
        // Don't start the timer yet - wait for client confirmation via startTimer()
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
                // Timer hasn't started yet (client still buffering) - just change state
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
        singleLoopTrack = null;
        startedAt = null;
        pausedOffset = Duration.ZERO;
        state = PlaybackState.IDLE;
    }

    public synchronized boolean stopCurrent() {
        boolean hadPlayback = current != null || state != PlaybackState.IDLE;
        if (sameQueuedTrack(current, singleLoopTrack)) {
            singleLoopTrack = null;
        }
        current = null;
        startedAt = null;
        pausedOffset = Duration.ZERO;
        state = PlaybackState.IDLE;
        return hadPlayback;
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
        return new PlaybackSnapshot(
                state,
                current,
                List.copyOf(requests),
                List.copyOf(randomTracks),
                offset(),
                randomEnabled,
                loopQueueEnabled,
                singleLoopTrack);
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

    /**
     * Removes a track from the queue at the given position (0-indexed).
     * Returns the removed track if successful, empty otherwise.
     */
    public synchronized Optional<QueuedTrack> removeAt(int position) {
        List<QueuedTrack> all = new ArrayList<>(requests);
        all.addAll(randomTracks);

        if (position < 0 || position >= all.size()) {
            return Optional.empty();
        }

        QueuedTrack removed = all.get(position);
        if (sameQueuedTrack(removed, singleLoopTrack)) {
            singleLoopTrack = null;
        }

        // Remove from the appropriate queue
        if (position < requests.size()) {
            // It's in the requests queue
            List<QueuedTrack> requestsList = new ArrayList<>(requests);
            requestsList.remove(position);
            requests.clear();
            requests.addAll(requestsList);
        } else {
            // It's in the random tracks queue
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
        singleLoopTrack = null;
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
        if (removed.stream().anyMatch(queued -> sameQueuedTrack(queued, singleLoopTrack))) {
            singleLoopTrack = null;
        }
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

    public synchronized boolean toggleRandom() {
        randomEnabled = !randomEnabled;
        if (randomEnabled) {
            shuffleQueue(requests);
            shuffleQueue(randomTracks);
        }
        return randomEnabled;
    }

    public synchronized boolean toggleQueueLoop() {
        loopQueueEnabled = !loopQueueEnabled;
        return loopQueueEnabled;
    }

    public synchronized SingleLoopChange toggleSingleLoopAt(int position) {
        List<QueuedTrack> all = queuedTracks();
        if (position < 0 || position >= all.size()) {
            return null;
        }
        QueuedTrack selected = all.get(position);
        if (sameQueuedTrack(selected, singleLoopTrack)) {
            singleLoopTrack = null;
            return new SingleLoopChange(selected, false);
        }
        singleLoopTrack = selected;
        return new SingleLoopChange(selected, true);
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

    private QueuedTrack pollNext() {
        QueuedTrack next = requests.poll();
        return next == null ? pollNext(randomTracks) : next;
    }

    private QueuedTrack pollNext(Deque<QueuedTrack> queue) {
        return queue.poll();
    }

    private static void shuffleQueue(Deque<QueuedTrack> queue) {
        if (queue.size() <= 1) {
            return;
        }
        List<QueuedTrack> original = new ArrayList<>(queue);
        List<QueuedTrack> shuffled = new ArrayList<>(queue);
        Collections.shuffle(shuffled);
        if (shuffled.equals(original)) {
            Collections.rotate(shuffled, 1);
        }
        queue.clear();
        queue.addAll(shuffled);
    }

    private static boolean sameQueuedTrack(QueuedTrack first, QueuedTrack second) {
        return first != null && first.equals(second);
    }

    public record QueuedTrack(KaraokeTrack track, UUID requester, String requesterName, Instant queuedAt) {
        public QueuedTrack {
            Objects.requireNonNull(track, "track");
            requesterName = requesterName == null || requesterName.isBlank() ? "Unknown" : requesterName;
            queuedAt = queuedAt == null ? Instant.now() : queuedAt;
        }
    }

    public record SingleLoopChange(QueuedTrack track, boolean enabled) {
    }

    public record PlaybackSnapshot(
            PlaybackState state,
            QueuedTrack current,
            List<QueuedTrack> requests,
            List<QueuedTrack> randomTracks,
            Duration offset,
            boolean randomEnabled,
            boolean loopQueueEnabled,
            QueuedTrack singleLoopTrack
    ) {
        public PlaybackSnapshot(
                PlaybackState state,
                QueuedTrack current,
                List<QueuedTrack> requests,
                List<QueuedTrack> randomTracks,
                Duration offset) {
            this(state, current, requests, randomTracks, offset, false, false, null);
        }
    }
}
