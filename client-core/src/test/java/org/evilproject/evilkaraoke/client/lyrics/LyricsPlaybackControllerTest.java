package org.evilproject.evilkaraoke.client.lyrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.junit.jupiter.api.Test;

class LyricsPlaybackControllerTest {
    private static final UUID SONG_ID = UUID.fromString("23b51bed-6827-4556-a427-de75940bfcdf");
    private static final List<TimedLyric> CUES = List.of(
            new TimedLyric(Duration.ofSeconds(10), "First"),
            new TimedLyric(Duration.ofSeconds(12), "Second"),
            new TimedLyric(Duration.ofSeconds(14), "Third"));

    @Test
    void lateJoinShowsOnlyTheCurrentCueThenContinues() {
        List<String> rendered = new ArrayList<>();
        LyricsPlaybackController controller = controller(id -> CompletableFuture.completedFuture(CUES), rendered);

        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ofSeconds(11)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(13)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(15)));

        assertEquals(List.of("Second", "Third"), rendered);
    }

    @Test
    void disabledAndPausedSubtitlesDoNotConsumeCues() {
        List<String> rendered = new ArrayList<>();
        LyricsPlaybackController controller = controller(id -> CompletableFuture.completedFuture(CUES), rendered);
        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ZERO));

        controller.tick(false, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(11)));
        controller.tick(true, ClientPlaybackState.PAUSED, Optional.of(Duration.ofSeconds(11)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(11)));

        assertEquals(List.of("First"), rendered);
    }

    @Test
    void staleAsyncResponseIsIgnoredAfterStop() {
        CompletableFuture<List<TimedLyric>> response = new CompletableFuture<>();
        List<String> rendered = new ArrayList<>();
        LyricsPlaybackController controller = controller(id -> response, rendered);

        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ZERO));
        controller.handleCommand(stop("playback-1"));
        response.complete(CUES);
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(20)));

        assertEquals(List.of(), rendered);
    }

    @Test
    void nonApiTrackIdsNeverTriggerARequest() {
        AtomicInteger requests = new AtomicInteger();
        List<String> rendered = new ArrayList<>();
        LyricsPlaybackController controller = controller(id -> {
            requests.incrementAndGet();
            return CompletableFuture.completedFuture(CUES);
        }, rendered);

        controller.handleCommand(play("playback-1", "url:https://example.test/song.ogg", Duration.ZERO));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(20)));

        assertEquals(0, requests.get());
        assertEquals(List.of(), rendered);
    }

    @Test
    void movingBackwardRepositionsTheCueCursor() {
        List<String> rendered = new ArrayList<>();
        LyricsPlaybackController controller = controller(id -> CompletableFuture.completedFuture(CUES), rendered);
        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ZERO));

        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(15)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(10)));

        assertEquals(List.of("Third", "First"), rendered);
    }

    @Test
    void lyricRemainsActiveUntilTheNextCueBoundary() {
        List<String> events = new ArrayList<>();
        LyricsPlaybackController controller = eventController(
                id -> CompletableFuture.completedFuture(List.of(
                        new TimedLyric(Duration.ofSeconds(1), "Long line"),
                        new TimedLyric(Duration.ofSeconds(10), "Next line"))),
                events);
        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ZERO));

        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(1)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(9)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(10)));

        assertEquals(List.of("show:Long line", "show:Next line"), events);
    }

    @Test
    void instrumentalCueClearsUntilTheNextLyric() {
        List<String> events = new ArrayList<>();
        LyricsPlaybackController controller = eventController(
                id -> CompletableFuture.completedFuture(List.of(
                        new TimedLyric(Duration.ofSeconds(1), "First"),
                        new TimedLyric(Duration.ofSeconds(5), "(InStRuMeNtAl)"),
                        new TimedLyric(Duration.ofSeconds(12), "Second"))),
                events);
        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ZERO));

        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(2)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(6)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(8)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(13)));

        assertEquals(List.of("show:First", "clear", "show:Second"), events);
    }

    @Test
    void movingBackwardBeforeTheFirstCueClearsOnce() {
        List<String> events = new ArrayList<>();
        LyricsPlaybackController controller = eventController(
                id -> CompletableFuture.completedFuture(CUES), events);
        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ZERO));

        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(15)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(5)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(6)));

        assertEquals(List.of("show:Third", "clear"), events);
    }

    @Test
    void duplicateTimestampsUseTheLastCue() {
        List<String> rendered = new ArrayList<>();
        LyricsPlaybackController controller = controller(
                id -> CompletableFuture.completedFuture(List.of(
                        new TimedLyric(Duration.ofSeconds(4), "Discarded"),
                        new TimedLyric(Duration.ofSeconds(4), "Current"))),
                rendered);
        controller.handleCommand(play("playback-1", SONG_ID.toString(), Duration.ZERO));

        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(4)));

        assertEquals(List.of("Current"), rendered);
    }

    @Test
    void finiteTrackDurationClearsTheFinalCue() {
        List<String> events = new ArrayList<>();
        LyricsPlaybackController controller = eventController(
                id -> CompletableFuture.completedFuture(List.of(
                        new TimedLyric(Duration.ofSeconds(10), "Final line"))),
                events);
        controller.handleCommand(play(
                "playback-1", SONG_ID.toString(), Duration.ZERO, Duration.ofSeconds(20)));

        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(10)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(20)));
        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(21)));

        assertEquals(List.of("show:Final line", "clear"), events);
    }

    @Test
    void zeroTrackDurationIsTreatedAsUnknown() {
        List<String> rendered = new ArrayList<>();
        LyricsPlaybackController controller = controller(
                id -> CompletableFuture.completedFuture(List.of(
                        new TimedLyric(Duration.ofSeconds(10), "Current line"))),
                rendered);
        controller.handleCommand(play(
                "playback-1", SONG_ID.toString(), Duration.ZERO, Duration.ZERO));

        controller.tick(true, ClientPlaybackState.PLAYING, Optional.of(Duration.ofSeconds(12)));

        assertEquals(List.of("Current line"), rendered);
    }

    private static LyricsPlaybackController controller(LyricsProvider provider, List<String> rendered) {
        LyricsPlaybackController controller = new LyricsPlaybackController(Logger.getLogger("test"), provider);
        controller.setRenderer(rendered::add);
        return controller;
    }

    private static LyricsPlaybackController eventController(LyricsProvider provider, List<String> events) {
        LyricsPlaybackController controller = new LyricsPlaybackController(Logger.getLogger("test"), provider);
        controller.setRenderer(text -> events.add("show:" + text));
        controller.setClearer(() -> events.add("clear"));
        return controller;
    }

    private static AudioCommandPacket play(String playbackId, String trackId, Duration offset) {
        return play(playbackId, trackId, offset, Duration.ofMinutes(3));
    }

    private static AudioCommandPacket play(String playbackId, String trackId, Duration offset, Duration duration) {
        KaraokeTrack track = new KaraokeTrack(
                trackId,
                TrackType.SONG,
                "Title",
                "Artist",
                new AudioAsset("https://audio.example.test/song.opus", AudioFormat.OPUS),
                null,
                duration);
        return new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                playbackId,
                track,
                PlaybackTarget.allPlayers(),
                offset,
                Instant.EPOCH,
                "",
                Duration.ZERO);
    }

    private static AudioCommandPacket stop(String playbackId) {
        return new AudioCommandPacket(
                AudioCommandType.STOP,
                "global",
                playbackId,
                null,
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "",
                Duration.ZERO);
    }
}
