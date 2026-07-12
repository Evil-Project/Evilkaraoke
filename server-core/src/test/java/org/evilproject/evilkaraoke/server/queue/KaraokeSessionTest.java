package org.evilproject.evilkaraoke.server.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.junit.jupiter.api.Test;

class KaraokeSessionTest {
    @Test
    void randomToggleShufflesQueueAndPlaybackFollowsShownOrder() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");
        session.request(track("c"), steveId, "Steve");

        List<String> originalOrder = session.queuedTracks().stream()
                .map(queued -> queued.track().id())
                .toList();

        assertTrue(session.toggleRandom());

        List<String> shuffledOrder = session.queuedTracks().stream()
                .map(queued -> queued.track().id())
                .toList();
        assertNotEquals(originalOrder, shuffledOrder);
        assertEquals(shuffledOrder.get(0), session.next().orElseThrow().track().id());
        assertEquals(shuffledOrder.get(1), session.next().orElseThrow().track().id());
    }

    @Test
    void queueLoopReplaysEverySongAcrossPasses() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");
        session.request(track("c"), steveId, "Steve");
        assertTrue(session.toggleQueueLoop());

        List<String> played = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            played.add(session.next().orElseThrow().track().id());
            assertEquals(3, session.queuedTracks().size());
        }

        assertEquals(List.of("a", "b", "c", "a", "b", "c"), played);
    }

    @Test
    void queueLoopKeepsPlayingTrackInQueue() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");
        session.request(track("c"), steveId, "Steve");
        assertTrue(session.toggleQueueLoop());

        assertEquals("a", session.next().orElseThrow().track().id());
        assertEquals(List.of("b", "c", "a"),
                session.queuedTracks().stream().map(queued -> queued.track().id()).toList());

        // Turning the loop off removes the playing track from the queue again.
        assertEquals(false, session.toggleQueueLoop());
        assertEquals(List.of("b", "c"),
                session.queuedTracks().stream().map(queued -> queued.track().id()).toList());
    }

    @Test
    void queueLoopPreviousThenNextReturnsTheInterruptedTrack() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");
        session.request(track("c"), steveId, "Steve");
        assertTrue(session.toggleQueueLoop());

        assertEquals("a", session.next().orElseThrow().track().id());
        assertEquals("b", session.next().orElseThrow().track().id());
        assertEquals("a", session.previous().orElseThrow().track().id());

        assertEquals("b", session.next().orElseThrow().track().id());
    }

    @Test
    void clearingLoopQueueDoesNotReinsertThePlayingTrack() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");
        assertTrue(session.toggleQueueLoop());
        assertEquals("a", session.next().orElseThrow().track().id());

        assertEquals(List.of("b", "a"), session.removeAllQueued().stream()
                .map(queued -> queued.track().id()).toList());

        assertTrue(session.next().isEmpty());
        assertTrue(session.queuedTracks().isEmpty());
    }

    @Test
    void newRequestInsertsAtRandomPositionWithoutReshuffling() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");

        assertTrue(session.toggleRandom());
        List<String> previousRandomOrder = session.queuedTracks().stream()
                .map(queued -> queued.track().id())
                .toList();

        session.request(track("c"), steveId, "Steve");

        List<String> newRandomOrder = session.queuedTracks().stream()
                .map(queued -> queued.track().id())
                .toList();
        assertEquals(3, newRandomOrder.size());
        assertTrue(newRandomOrder.contains("c"));
        List<String> withoutNewTrack = newRandomOrder.stream()
                .filter(id -> !id.equals("c"))
                .toList();
        assertEquals(previousRandomOrder, withoutNewTrack);
    }

    @Test
    void requestAllShufflesAgainWhenRandomIsEnabled() {
        KaraokeSession session = new KaraokeSession();
        UUID steveId = UUID.randomUUID();
        session.request(track("a"), steveId, "Steve");
        session.request(track("b"), steveId, "Steve");

        assertTrue(session.toggleRandom());
        List<String> previousRandomOrder = session.queuedTracks().stream()
                .map(queued -> queued.track().id())
                .toList();

        assertEquals(2, session.requestAll(List.of(track("c"), track("d")), steveId, "Steve"));

        List<String> newRandomOrder = session.queuedTracks().stream()
                .map(queued -> queued.track().id())
                .toList();
        assertNotEquals(List.of(previousRandomOrder.get(0), previousRandomOrder.get(1), "c", "d"), newRandomOrder);
        assertTrue(newRandomOrder.containsAll(List.of("a", "b", "c", "d")));
    }

    private static KaraokeTrack track(String id) {
        return new KaraokeTrack(
                id,
                TrackType.SONG,
                "Song " + id,
                "Artist",
                new AudioAsset("https://audio/" + id + ".opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(3));
    }
}
