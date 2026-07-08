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
