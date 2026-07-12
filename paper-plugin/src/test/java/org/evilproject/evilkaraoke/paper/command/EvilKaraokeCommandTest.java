package org.evilproject.evilkaraoke.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeSetlist;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;
import org.junit.jupiter.api.Test;

class EvilKaraokeCommandTest {
    @Test
    void suggestsLyricsActions() {
        assertEquals(List.of("enable", "disable"), EvilKaraokeCommand.lyricsActionSuggestions(""));
        assertEquals(List.of("enable"), EvilKaraokeCommand.lyricsActionSuggestions("E"));
        assertEquals(List.of("disable"), EvilKaraokeCommand.lyricsActionSuggestions("d"));
    }

    @Test
    void playbackControlsExplainNoOpStates() {
        assertEquals("Nothing is playing and the queue is empty.",
                EvilKaraokeCommand.playbackControlUnavailableMessage("next"));
        assertEquals("No previous track is available.",
                EvilKaraokeCommand.playbackControlUnavailableMessage("previous"));
        assertEquals("Playback is not paused.",
                EvilKaraokeCommand.playbackControlUnavailableMessage("resume"));
        assertEquals("Nothing is currently playing.",
                EvilKaraokeCommand.playbackControlUnavailableMessage("stop"));
    }

    @Test
    void queueShowsCurrentTrackWhenNoUpcomingSongsRemain() {
        KaraokeTrack track = track("brick", "Brick by Boring Brick");
        KaraokeSession.QueuedTrack current = new KaraokeSession.QueuedTrack(track, null, "Player", Instant.now());
        KaraokeSession.PlaybackSnapshot snapshot = new KaraokeSession.PlaybackSnapshot(
                PlaybackState.PLAYING,
                current,
                List.of(),
                List.of(),
                Duration.ofSeconds(83)
        );

        List<Component> messages = EvilKaraokeCommand.queueMessages(snapshot, 1);

        assertEquals(Component.text("Now playing: ", NamedTextColor.GOLD)
                .append(expectedSongLine("Brick by Boring Brick", "Unknown Artist"))
                .append(expectedCurrentDetails("Player", "1:23"))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("[Pause]", NamedTextColor.YELLOW)
                        .hoverEvent(HoverEvent.showText(Component.text("Pause current playback")))
                        .clickEvent(ClickEvent.runCommand("/ek queue pause --refresh=1")))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("[Stop]", NamedTextColor.RED)
                        .hoverEvent(HoverEvent.showText(Component.text("Stop current playback")))
                        .clickEvent(ClickEvent.runCommand("/ek queue stop --refresh=1"))), messages.getFirst());
        assertEquals(Component.text("No upcoming songs queued.", NamedTextColor.GRAY), messages.get(messages.size() - 2));
        assertEquals("Controls: [Previous] [Next] [Random: Off] [Loop: Off]", ChatMessages.plain(messages.getLast()));
        assertFalse(messages.contains(Component.text("The queue is empty.", NamedTextColor.GRAY)));
    }

    @Test
    void queueShowsResumeButtonWhenCurrentTrackIsPaused() {
        KaraokeTrack track = track("paused", "Paused Song");
        KaraokeSession.QueuedTrack current = new KaraokeSession.QueuedTrack(track, null, "Player", Instant.now());
        KaraokeSession.PlaybackSnapshot snapshot = new KaraokeSession.PlaybackSnapshot(
                PlaybackState.PAUSED,
                current,
                List.of(),
                List.of(),
                Duration.ofSeconds(12)
        );

        List<Component> messages = EvilKaraokeCommand.queueMessages(snapshot, 1);

        assertEquals("Now playing: Paused Song - Unknown Artist (by Player) | Elapsed: 0:12 [Resume] [Stop]",
                ChatMessages.plain(messages.getFirst()));
    }

    @Test
    void queueMessagesShowFiveUpcomingSongsPerPage() {
        List<KaraokeSession.QueuedTrack> queued = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new KaraokeSession.QueuedTrack(track("song-" + i, "Song " + i), null, "Player", Instant.now()))
                .toList();
        KaraokeSession.PlaybackSnapshot snapshot = new KaraokeSession.PlaybackSnapshot(
                PlaybackState.IDLE,
                null,
                queued,
                List.of(),
                Duration.ZERO
        );

        List<Component> firstPage = EvilKaraokeCommand.queueMessages(snapshot, 1, "Queue", "ek");
        List<Component> secondPage = EvilKaraokeCommand.queueMessages(snapshot, 2, "Queue", "ek");

        assertEquals(Component.text("Queue (page 1/2)", NamedTextColor.GOLD), firstPage.getFirst());
        assertEquals("Controls: [Previous] [Next] [Random: Off] [Loop: Off]", ChatMessages.plain(firstPage.getLast()));
        assertEquals(8, firstPage.size());
        assertEquals(Component.empty().append(Component.text("⬇️", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("Next page (2)")))
                .clickEvent(ClickEvent.runCommand("/ek queue 2"))), firstPage.get(6));
        assertEquals(Component.text("Queue (page 2/2)", NamedTextColor.GOLD), secondPage.getFirst());
        assertEquals(4, secondPage.size());
        // Queue items now have [Cancel] buttons, so we just check the track is present
        Component secondPageItem = secondPage.get(1);
        String itemText = ChatMessages.plain(secondPageItem);
        assertEquals("6. Song 6 - Unknown Artist (by Player) [Loop 1] [Cancel]", itemText);
        assertEquals(Component.empty().append(Component.text("⬆️ ", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("Previous page (1)")))
                .clickEvent(ClickEvent.runCommand("/ek queue 1"))), secondPage.get(2));
        assertEquals("Controls: [Previous] [Next] [Random: Off] [Loop: Off]", ChatMessages.plain(secondPage.getLast()));
    }

    @Test
    void queueMessagesShowCoveredByOnSongLine() {
        KaraokeSession.QueuedTrack queued = new KaraokeSession.QueuedTrack(
                coveredTrack("song-cover", "Covered Song", "Original Artist", "Neuro & Evil"),
                null,
                "Player",
                Instant.now());
        KaraokeSession.PlaybackSnapshot snapshot = new KaraokeSession.PlaybackSnapshot(
                PlaybackState.IDLE,
                null,
                List.of(queued),
                List.of(),
                Duration.ZERO
        );

        List<Component> messages = EvilKaraokeCommand.queueMessages(snapshot, 1, "Queue", "ek");

        assertEquals(Component.text("1. ", NamedTextColor.GOLD)
                        .append(expectedCoveredSongLine("Covered Song", "Original Artist", "Neuro & Evil"))
                        .append(Component.text(" (by ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Player", NamedTextColor.WHITE))
                        .append(Component.text(") ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("[Loop 1]", NamedTextColor.BLUE)
                                .hoverEvent(HoverEvent.showText(Component.text("Loop this song")))
                                .clickEvent(ClickEvent.runCommand("/ek queue loop 1 --refresh=1")))
                        .append(Component.text(" ", NamedTextColor.GRAY))
                        .append(Component.text("[Cancel]", NamedTextColor.RED)
                                .hoverEvent(HoverEvent.showText(Component.text("Remove this song from the queue")))
                                .clickEvent(ClickEvent.runCommand("/ek queue cancel 1 --refresh=1"))),
                messages.get(1));
    }

    @Test
    void queueMessagesShowEnabledRandomLoopAndSingleLoopButtons() {
        KaraokeSession.QueuedTrack queued = new KaraokeSession.QueuedTrack(
                track("song-loop", "Loop Song"),
                null,
                "Player",
                Instant.now());
        KaraokeSession.PlaybackSnapshot snapshot = new KaraokeSession.PlaybackSnapshot(
                PlaybackState.IDLE,
                null,
                List.of(queued),
                List.of(),
                Duration.ZERO,
                true,
                true,
                queued
        );

        List<Component> messages = EvilKaraokeCommand.queueMessages(snapshot, 1, "Queue", "ek");

        assertEquals("Controls: [Previous] [Next] [Random: On] [Loop: On]", ChatMessages.plain(messages.getLast()));
        assertEquals("1. Loop Song - Unknown Artist (by Player) [Loop 1: On] [Cancel]",
                ChatMessages.plain(messages.get(1)));
    }

    @Test
    void searchMessagesShowFiveResultsPerPage() {
        List<KaraokeTrack> results = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> track("song-" + i, "Song " + i))
                .toList();

        List<Component> messages = EvilKaraokeCommand.searchMessages("hello world", 2, results, "ek");

        assertEquals(expectedSearchHeader("hello world", 2), messages.getFirst());
        assertEquals("6. Song 1 - Unknown Artist [Request]", ChatMessages.plain(messages.get(1)));
        assertEquals(7, messages.size());
        assertEquals(Component.empty()
                .append(Component.text("⬆️ ", NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text("Previous page (1)")))
                        .clickEvent(ClickEvent.runCommand("/ek search hello world 1")))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("⬇️", NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text("Next page (3)")))
                        .clickEvent(ClickEvent.runCommand("/ek search hello world 3"))), messages.getLast());
    }

    @Test
    void searchMessagesShowCoveredByOnSongLine() {
        List<KaraokeTrack> results = List.of(coveredTrack("song-cover", "Covered Song", "Original Artist", "Neuro & Evil"));

        List<Component> messages = EvilKaraokeCommand.searchMessages("cover", 1, results, "ek", false);

        assertEquals("1. Covered Song - Original Artist (covered by Neuro & Evil) [Request]",
                ChatMessages.plain(messages.get(1)));
    }

    @Test
    void searchMessagesCanHideNextPageForFullFinalPage() {
        List<KaraokeTrack> results = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(i -> track("song-" + i, "Song " + i))
                .toList();

        List<Component> messages = EvilKaraokeCommand.searchMessages("never", 3, results, "ek", false);

        assertEquals(expectedSearchHeader("never", 3), messages.getFirst());
        assertEquals(7, messages.size());
        assertEquals(Component.empty().append(Component.text("⬆️ ", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("Previous page (2)")))
                .clickEvent(ClickEvent.runCommand("/ek search never 2"))), messages.getLast());
    }

    @Test
    void searchMessagesDoNotCreateClickableCommandsForUnsafeSongIds() {
        List<KaraokeTrack> results = List.of(track("bad id", "Unsafe"));

        List<Component> messages = EvilKaraokeCommand.searchMessages("unsafe", 1, results, "ek", false);

        assertEquals("1. Unsafe - Unknown Artist (request with /ek request id bad id)", ChatMessages.plain(messages.get(1)));
        assertEquals(Component.text("1. ", NamedTextColor.GOLD)
                .append(expectedSongLine("Unsafe", "Unknown Artist"))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("(request with /ek request id bad id)", NamedTextColor.YELLOW)), messages.get(1));
    }

    @Test
    void setlistMessagesShowFiveResultsPerPage() {
        List<NeurokaraokeSetlist> setlists = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new NeurokaraokeSetlist("setlist-" + i, "Setlist " + i, 18, Duration.ofSeconds(3951)))
                .toList();

        List<Component> messages = EvilKaraokeCommand.setlistMessages(2, setlists, "ek");

        assertEquals(Component.text("Setlists (page 2):", NamedTextColor.GOLD), messages.getFirst());
        assertEquals(7, messages.size());
        assertEquals(expectedCollectionLine(6, "Setlist 1", 18, " | 65:51")
                .append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all songs from Setlist 1")))
                        .clickEvent(ClickEvent.runCommand("/ek setlist add 2 1"))), messages.get(1));
        assertEquals(Component.empty()
                .append(Component.text("⬆️ ", NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text("Previous page (1)")))
                        .clickEvent(ClickEvent.runCommand("/ek setlist 1")))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("⬇️", NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text("Next page (3)")))
                        .clickEvent(ClickEvent.runCommand("/ek setlist 3"))), messages.getLast());
    }

    @Test
    void setlistMessagesShowEmptyMarkerForZeroSongSetlists() {
        List<NeurokaraokeSetlist> setlists = List.of(new NeurokaraokeSetlist("setlist-empty", "Empty Setlist", 0, null));

        List<Component> messages = EvilKaraokeCommand.setlistMessages(1, setlists, "ek");

        assertEquals(expectedCollectionLine(1, "Empty Setlist", 0, "")
                .append(Component.text("[Empty]", NamedTextColor.DARK_GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("This setlist has no songs")))), messages.get(1));
    }

    @Test
    void playlistMessagesUsePlaylistCommandForQueueAndNavigation() {
        List<NeurokaraokeSetlist> playlists = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new NeurokaraokeSetlist("playlist-" + i, "Playlist " + i, 45, null))
                .toList();

        List<Component> messages = EvilKaraokeCommand.playlistMessages(2, playlists, "ek");

        assertEquals(Component.text("Public playlists (page 2):", NamedTextColor.GOLD), messages.getFirst());
        assertEquals(7, messages.size());
        assertEquals(expectedCollectionLine(6, "Playlist 1", 45, "")
                .append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all songs from Playlist 1")))
                        .clickEvent(ClickEvent.runCommand("/ek playlist add 2 1"))), messages.get(1));
        assertEquals(Component.empty()
                .append(Component.text("⬆️ ", NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text("Previous page (1)")))
                        .clickEvent(ClickEvent.runCommand("/ek playlist 1")))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("⬇️", NamedTextColor.AQUA)
                        .hoverEvent(HoverEvent.showText(Component.text("Next page (3)")))
                        .clickEvent(ClickEvent.runCommand("/ek playlist 3"))), messages.getLast());
    }

    @Test
    void playlistMessagesShowEmptyMarkerForZeroSongPlaylists() {
        List<NeurokaraokeSetlist> playlists = List.of(new NeurokaraokeSetlist("playlist-empty", "Empty Playlist", 0, null));

        List<Component> messages = EvilKaraokeCommand.playlistMessages(1, playlists, "ek");

        assertEquals(expectedCollectionLine(1, "Empty Playlist", 0, "")
                .append(Component.text("[Empty]", NamedTextColor.DARK_GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("This playlist has no songs")))), messages.get(1));
    }

    @Test
    void randomSongMessagesOfferQueueAllAndPagedQueueOneActions() {
        List<KaraokeTrack> tracks = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> i == 6
                        ? coveredTrack("random-6", "Sixth Random", "Artist Six", "Neuro")
                        : track("random-" + i, "Random " + i))
                .toList();

        List<Component> messages = EvilKaraokeCommand.randomSongMessages(tracks, 2, "ek");

        assertEquals(Component.text("Random songs (page 2/2): ", NamedTextColor.GOLD)
                .append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all random songs")))
                        .clickEvent(ClickEvent.runCommand("/ek randomsong queue all"))), messages.getFirst());
        assertEquals(Component.text("6. ", NamedTextColor.GOLD)
                .append(expectedCoveredSongLine("Sixth Random", "Artist Six", "Neuro"))
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(Component.text("[Queue]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue Sixth Random")))
                        .clickEvent(ClickEvent.runCommand("/ek randomsong queue 6"))), messages.get(1));
        assertEquals(Component.empty().append(Component.text("⬆️ ", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("Previous page (1)")))
                .clickEvent(ClickEvent.runCommand("/ek randomsong 1"))), messages.getLast());
    }

    private static Component expectedSearchHeader(String query, int page) {
        return Component.text("Results for \"" + query + "\" (page " + page + "): ", NamedTextColor.GOLD)
                .append(Component.text("[Queue All]", NamedTextColor.GREEN)
                        .hoverEvent(HoverEvent.showText(Component.text("Queue all results on this page")))
                        .clickEvent(ClickEvent.runCommand("/ek search queue-all")));
    }

    private static Component expectedSongLine(String title, String artist) {
        return Component.text(title, NamedTextColor.AQUA)
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(artist, NamedTextColor.LIGHT_PURPLE));
    }

    private static Component expectedCoveredSongLine(String title, String artist, String coverArtists) {
        return expectedSongLine(title, artist)
                .append(Component.text(" (covered by ", NamedTextColor.GRAY))
                .append(Component.text(coverArtists, NamedTextColor.YELLOW))
                .append(Component.text(")", NamedTextColor.GRAY));
    }

    private static Component expectedCollectionLine(int number, String name, int songCount, String duration) {
        return Component.text(number + ". ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.AQUA))
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(Integer.toString(songCount), NamedTextColor.YELLOW))
                .append(Component.text(" songs", NamedTextColor.GRAY))
                .append(Component.text(duration + " ", NamedTextColor.GRAY));
    }

    private static Component expectedCurrentDetails(String requester, String elapsed) {
        return Component.text(" (by ", NamedTextColor.DARK_GRAY)
                .append(Component.text(requester, NamedTextColor.WHITE))
                .append(Component.text(") | Elapsed: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(elapsed, NamedTextColor.AQUA));
    }

    private static KaraokeTrack track(String id, String title) {
        return new KaraokeTrack(
                id,
                TrackType.SONG,
                title,
                "Unknown Artist",
                new AudioAsset("https://audio.example/" + id + ".opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(4)
        );
    }

    private static KaraokeTrack coveredTrack(String id, String title, String artist, String coverArtists) {
        return new KaraokeTrack(
                id,
                TrackType.SONG,
                title,
                artist,
                coverArtists,
                new AudioAsset("https://audio.example/" + id + ".opus", AudioFormat.OPUS),
                null,
                Duration.ofMinutes(4)
        );
    }
}
