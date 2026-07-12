package org.evilproject.evilkaraoke.server.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;

public final class KaraokeSession {
    public static final String GLOBAL_SESSION_ID = "global";

    private static final int MAX_HISTORY = 10;

    private final Deque<QueuedTrack> requests = new ArrayDeque<>();
    private final Deque<QueuedTrack> randomTracks = new ArrayDeque<>();
    private final Deque<QueuedTrack> history = new ArrayDeque<>();
    private final Set<QueuedTrack> loopExcludedTracks = new HashSet<>();
    private PlaybackState state = PlaybackState.IDLE;
    private QueuedTrack current;
    private Instant startedAt;
    private Duration pausedOffset = Duration.ZERO;
    private float volume = 1.0f;
    private boolean randomEnabled;
    private boolean loopQueueEnabled;
    private boolean currentRetainedInQueue;
    private QueuedTrack singleLoopTrack;

    public synchronized void request(KaraokeTrack track, UUID requester, String requesterName) {
        QueuedTrack queued = new QueuedTrack(track, requester, requesterName, Instant.now());
        if (randomEnabled) {
            insertAtRandomPosition(requests, queued);
        } else {
            requests.add(queued);
        }
    }

    public synchronized int requestAll(List<KaraokeTrack> tracks, UUID requester, String requesterName) {
        for (KaraokeTrack track : tracks) {
            requests.add(new QueuedTrack(track, requester, requesterName, Instant.now()));
        }
        shuffleRequestsIfRandomEnabled();
        return tracks.size();
    }

    public synchronized void addRandom(KaraokeTrack track) {
        QueuedTrack queued = new QueuedTrack(track, null, "Evilkaraoke", Instant.now());
        if (randomEnabled) {
            insertAtRandomPosition(randomTracks, queued);
        } else {
            randomTracks.add(queued);
        }
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
                loopExcludedTracks.remove(history.removeFirst());
            }
            if (loopQueueEnabled && !currentRetainedInQueue && !loopExcludedTracks.contains(previous)) {
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
            if (currentRetainedInQueue) {
                removeQueuedOccurrence(current);
            }
            requests.addFirst(current);
            loopExcludedTracks.remove(current);
        }
        QueuedTrack prev = history.removeLast();
        current = prev;
        currentRetainedInQueue = loopQueueEnabled
                && (requests.contains(prev) || randomTracks.contains(prev));
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
        loopExcludedTracks.clear();
        singleLoopTrack = null;
        currentRetainedInQueue = false;
        startedAt = null;
        pausedOffset = Duration.ZERO;
        state = PlaybackState.IDLE;
    }

    public synchronized boolean stopCurrent() {
        boolean hadPlayback = current != null || state != PlaybackState.IDLE;
        if (sameQueuedTrack(current, singleLoopTrack)) {
            singleLoopTrack = null;
        }
        loopExcludedTracks.remove(current);
        current = null;
        currentRetainedInQueue = false;
        startedAt = null;
        pausedOffset = Duration.ZERO;
        state = PlaybackState.IDLE;
        return hadPlayback;
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
        if (sameQueuedTrack(removed, singleLoopTrack)) {
            singleLoopTrack = null;
        }
        excludeCurrentFromLoopIfRemoved(removed);
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
        if (removed.stream().anyMatch(queued -> sameQueuedTrack(queued, current))) {
            excludeCurrentFromLoopIfRemoved(current);
        }
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
        if (removed.stream().anyMatch(queued -> sameQueuedTrack(queued, current))) {
            excludeCurrentFromLoopIfRemoved(current);
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
        if (!loopQueueEnabled && currentRetainedInQueue && current != null) {
            removeQueuedOccurrence(current);
            currentRetainedInQueue = false;
        }
        loopExcludedTracks.clear();
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
        currentRetainedInQueue = false;
        QueuedTrack next = requests.poll();
        if (next != null) {
            loopExcludedTracks.remove(next);
            if (loopQueueEnabled) {
                // Keep looped tracks visible in the queue: the playing track
                // moves to the tail instead of being removed.
                requests.addLast(next);
                currentRetainedInQueue = true;
            }
            return next;
        }
        next = randomTracks.poll();
        if (next != null) {
            loopExcludedTracks.remove(next);
            if (loopQueueEnabled) {
                randomTracks.addLast(next);
                currentRetainedInQueue = true;
            }
        }
        return next;
    }

    private void excludeCurrentFromLoopIfRemoved(QueuedTrack removed) {
        if (!loopQueueEnabled || !sameQueuedTrack(removed, current)) {
            return;
        }
        currentRetainedInQueue = false;
        loopExcludedTracks.add(current);
    }

    private boolean removeQueuedOccurrence(QueuedTrack track) {
        return requests.removeLastOccurrence(track) || randomTracks.removeLastOccurrence(track);
    }

    private void shuffleRequestsIfRandomEnabled() {
        if (randomEnabled) {
            shuffleQueue(requests);
        }
    }

    private static void insertAtRandomPosition(Deque<QueuedTrack> queue, QueuedTrack track) {
        List<QueuedTrack> tracks = new ArrayList<>(queue);
        tracks.add(ThreadLocalRandom.current().nextInt(tracks.size() + 1), track);
        queue.clear();
        queue.addAll(tracks);
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
