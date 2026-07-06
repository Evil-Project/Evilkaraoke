package org.evilproject.evilkaraoke.paper.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.junit.jupiter.api.Test;

class KaraokeSessionTest {
    private KaraokeTrack track(String id) {
        return new KaraokeTrack(id, TrackType.SONG, "Song " + id, "Artist", new AudioAsset("https://audio/" + id + ".opus", AudioFormat.OPUS), null, Duration.ofMinutes(3));
    }

    @Test
    void requestsTakePriorityOverRandom() {
        KaraokeSession session = new KaraokeSession();
        session.addRandom(track("random"));
        session.request(track("requested"), UUID.randomUUID(), "Steve");

        KaraokeSession.QueuedTrack next = session.next().orElseThrow();

        assertEquals("requested", next.track().id());
        assertEquals("Steve", next.requesterName());
        assertEquals(PlaybackState.PLAYING, session.snapshot().state());
    }

    @Test
    void fallsBackToRandomWhenNoRequests() {
        KaraokeSession session = new KaraokeSession();
        session.addRandom(track("random"));

        assertEquals("random", session.next().orElseThrow().track().id());
        assertTrue(session.next().isEmpty());
        assertEquals(PlaybackState.IDLE, session.snapshot().state());
    }

    @Test
    void pauseRetainsOffsetAndResumeContinues() throws InterruptedException {
        KaraokeSession session = new KaraokeSession();
        session.request(track("requested"), UUID.randomUUID(), "Steve");
        session.next();
        session.startTimer(); // Start timer to simulate client beginning playback
        Thread.sleep(20);
        session.pause();
        Duration paused = session.offset();
        assertEquals(PlaybackState.PAUSED, session.snapshot().state());
        Thread.sleep(20);
        assertEquals(paused, session.offset(), "offset should not advance while paused");
        session.resume();
        assertEquals(PlaybackState.PLAYING, session.snapshot().state());
    }

    @Test
    void stopClearsQueue() {
        KaraokeSession session = new KaraokeSession();
        session.request(track("a"), UUID.randomUUID(), "Steve");
        session.addRandom(track("b"));
        session.next();
        session.stop();

        assertEquals(PlaybackState.IDLE, session.snapshot().state());
        assertTrue(session.queuedTracks().isEmpty());
        assertTrue(session.current().isEmpty());
    }

    @Test
    void stopCurrentKeepsUpcomingQueue() {
        KaraokeSession session = new KaraokeSession();
        session.request(track("a"), UUID.randomUUID(), "Steve");
        session.request(track("b"), UUID.randomUUID(), "Steve");
        session.next();

        assertTrue(session.stopCurrent());

        assertEquals(PlaybackState.IDLE, session.snapshot().state());
        assertTrue(session.current().isEmpty());
        assertEquals(List.of("b"), session.queuedTracks().stream()
                .map(queued -> queued.track().id())
                .toList());
    }

    @Test
    void removeAtRemovesFromRequestQueue() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");
        session.request(track("c"), steveId, "Steve");

        assertEquals(3, session.requestQueueSize());

        // Remove position 1 (0-indexed, so track "b")
        KaraokeSession.QueuedTrack removed = session.removeAt(1).orElseThrow();
        assertEquals("b", removed.track().id());
        assertEquals(2, session.requestQueueSize());

        // Verify remaining tracks are correct
        assertEquals("a", session.queuedTracks().get(0).track().id());
        assertEquals("c", session.queuedTracks().get(1).track().id());
    }

    @Test
    void removeAtRemovesFromRandomQueue() {
        KaraokeSession session = new KaraokeSession();
        session.addRandom(track("r1"));
        session.addRandom(track("r2"));
        session.addRandom(track("r3"));

        assertEquals(3, session.randomQueueSize());

        // Remove position 1 (0-indexed, so track "r2")
        KaraokeSession.QueuedTrack removed = session.removeAt(1).orElseThrow();
        assertEquals("r2", removed.track().id());
        assertEquals(2, session.randomQueueSize());

        // Verify remaining tracks
        assertEquals("r1", session.queuedTracks().get(0).track().id());
        assertEquals("r3", session.queuedTracks().get(1).track().id());
    }

    @Test
    void removeAtHandlesMixedQueue() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("req1"), steveId, "Steve");
        session.request(track("req2"), steveId, "Steve");
        session.addRandom(track("rand1"));
        session.addRandom(track("rand2"));

        // Combined queue: [req1, req2, rand1, rand2]
        assertEquals(4, session.queuedTracks().size());

        // Remove position 2 (0-indexed, so "rand1")
        KaraokeSession.QueuedTrack removed = session.removeAt(2).orElseThrow();
        assertEquals("rand1", removed.track().id());

        // Verify queue state
        assertEquals(2, session.requestQueueSize());
        assertEquals(1, session.randomQueueSize());
        assertEquals(3, session.queuedTracks().size());
    }

    @Test
    void removeAtReturnsEmptyForInvalidPosition() {
        KaraokeSession session = new KaraokeSession();
        session.request(track("a"), UUID.randomUUID(), "Steve");

        assertTrue(session.removeAt(-1).isEmpty());
        assertTrue(session.removeAt(5).isEmpty());
    }

    @Test
    void removeRequestsByRequesterRemovesOnlyThatPlayersRequests() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        UUID alexId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), alexId, "Alex");
        session.request(track("c"), steveId, "Steve");

        List<KaraokeSession.QueuedTrack> removed = session.removeRequestsByRequester(steveId);

        assertEquals(List.of("a", "c"), removed.stream().map(queued -> queued.track().id()).toList());
        assertEquals(List.of("b"), session.queuedTracks().stream().map(queued -> queued.track().id()).toList());
    }

    @Test
    void removeAllQueuedClearsRequestsAndRandomTracks() {
        KaraokeSession session = new KaraokeSession();
        session.request(track("a"), UUID.randomUUID(), "Steve");
        session.addRandom(track("b"));

        List<KaraokeSession.QueuedTrack> removed = session.removeAllQueued();

        assertEquals(List.of("a", "b"), removed.stream().map(queued -> queued.track().id()).toList());
        assertTrue(session.queuedTracks().isEmpty());
    }

    @Test
    void moveRequestReordersRequestedQueue() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");
        session.request(track("c"), steveId, "Steve");

        KaraokeSession.QueuedTrack moved = session.moveRequest(2, 0).orElseThrow();

        assertEquals("c", moved.track().id());
        assertEquals(List.of("c", "a", "b"), session.queuedTracks().stream().map(queued -> queued.track().id()).toList());
    }

    @Test
    void moveRequestRejectsInvalidPositions() {
        KaraokeSession session = new KaraokeSession();
        session.request(track("a"), UUID.randomUUID(), "Steve");

        assertTrue(session.moveRequest(-1, 0).isEmpty());
        assertTrue(session.moveRequest(0, 2).isEmpty());
    }

    @Test
    void queueLoopRequeuesCompletedSongs() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");

        assertEquals("a", session.next().orElseThrow().track().id());
        assertTrue(session.toggleQueueLoop());

        assertEquals("b", session.next().orElseThrow().track().id());
        assertEquals(List.of("a"), session.queuedTracks().stream().map(queued -> queued.track().id()).toList());
    }

    @Test
    void singleLoopRepeatsSelectedQueuedSong() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");

        KaraokeSession.SingleLoopChange change = session.toggleSingleLoopAt(1);

        assertTrue(change.enabled());
        assertEquals("b", change.track().track().id());
        assertEquals("a", session.next().orElseThrow().track().id());
        assertEquals("b", session.next().orElseThrow().track().id());
        assertEquals("b", session.next().orElseThrow().track().id());
        assertTrue(session.queuedTracks().isEmpty());
    }

    @Test
    void randomToggleIsCapturedInSnapshot() {
        KaraokeSession session = new KaraokeSession();

        assertEquals(false, session.snapshot().randomEnabled());
        assertTrue(session.toggleRandom());
        assertEquals(true, session.snapshot().randomEnabled());
    }
}
