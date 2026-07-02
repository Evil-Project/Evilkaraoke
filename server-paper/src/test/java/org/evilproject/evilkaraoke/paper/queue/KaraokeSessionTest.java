package org.evilproject.evilkaraoke.paper.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
}
