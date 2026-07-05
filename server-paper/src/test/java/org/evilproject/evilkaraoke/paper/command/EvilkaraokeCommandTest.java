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

class EvilkaraokeCommandTest {
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

        List<Component> messages = EvilkaraokeCommand.queueMessages(snapshot, 1);

        assertEquals(Component.text("Now playing: Brick by Boring Brick - Unknown Artist", NamedTextColor.GOLD), messages.getFirst());
        assertEquals(Component.text("No upcoming songs queued.", NamedTextColor.GRAY), messages.getLast());
        assertFalse(messages.contains(Component.text("The queue is empty.", NamedTextColor.GRAY)));
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

        List<Component> firstPage = EvilkaraokeCommand.queueMessages(snapshot, 1, "Queue", "ek");
        List<Component> secondPage = EvilkaraokeCommand.queueMessages(snapshot, 2, "Queue", "ek");

        assertEquals(Component.text("Queue (page 1/2)", NamedTextColor.GOLD), firstPage.getFirst());
        assertEquals(7, firstPage.size());
        assertEquals(Component.empty().append(Component.text("⬇️", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("Next page (2)")))
                .clickEvent(ClickEvent.runCommand("/ek queue 2"))), firstPage.getLast());
        assertEquals(Component.text("Queue (page 2/2)", NamedTextColor.GOLD), secondPage.getFirst());
        assertEquals(3, secondPage.size());
        // Queue items now have [Cancel] buttons, so we just check the track is present
        Component secondPageItem = secondPage.get(1);
        String itemText = ChatMessages.plain(secondPageItem);
        assertEquals("6. Song 6 - Unknown Artist (by Player) [Cancel]", itemText);
        assertEquals(Component.empty().append(Component.text("⬆️ ", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("Previous page (1)")))
                .clickEvent(ClickEvent.runCommand("/ek queue 1"))), secondPage.getLast());
    }

    @Test
    void searchMessagesShowFiveResultsPerPage() {
        List<KaraokeTrack> results = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> track("song-" + i, "Song " + i))
                .toList();

        List<Component> messages = EvilkaraokeCommand.searchMessages("hello world", 2, results, "ek");

        assertEquals(Component.text("Results for \"hello world\" (page 2):", NamedTextColor.GOLD), messages.getFirst());
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
    void searchMessagesCanHideNextPageForFullFinalPage() {
        List<KaraokeTrack> results = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(i -> track("song-" + i, "Song " + i))
                .toList();

        List<Component> messages = EvilkaraokeCommand.searchMessages("never", 3, results, "ek", false);

        assertEquals(Component.text("Results for \"never\" (page 3):", NamedTextColor.GOLD), messages.getFirst());
        assertEquals(7, messages.size());
        assertEquals(Component.empty().append(Component.text("⬆️ ", NamedTextColor.AQUA)
                .hoverEvent(HoverEvent.showText(Component.text("Previous page (2)")))
                .clickEvent(ClickEvent.runCommand("/ek search never 2"))), messages.getLast());
    }

    @Test
    void searchMessagesDoNotCreateClickableCommandsForUnsafeSongIds() {
        List<KaraokeTrack> results = List.of(track("bad id", "Unsafe"));

        List<Component> messages = EvilkaraokeCommand.searchMessages("unsafe", 1, results, "ek", false);

        assertEquals("- Unsafe - Unknown Artist (request with /ek request id bad id)", ChatMessages.plain(messages.get(1)));
        assertEquals(Component.text("- Unsafe - Unknown Artist ", NamedTextColor.GRAY)
                .append(Component.text("(request with /ek request id bad id)", NamedTextColor.YELLOW)), messages.get(1));
    }

    @Test
    void setlistMessagesShowFiveResultsPerPage() {
        List<NeurokaraokeSetlist> setlists = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new NeurokaraokeSetlist("setlist-" + i, "Setlist " + i, 18, Duration.ofSeconds(3951)))
                .toList();

        List<Component> messages = EvilkaraokeCommand.setlistMessages(2, setlists, "ek");

        assertEquals(Component.text("Setlists (page 2):", NamedTextColor.GOLD), messages.getFirst());
        assertEquals(7, messages.size());
        assertEquals(Component.text("6. Setlist 1 - 18 songs | 65:51 ", NamedTextColor.GRAY)
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

        List<Component> messages = EvilkaraokeCommand.setlistMessages(1, setlists, "ek");

        assertEquals(Component.text("1. Empty Setlist - 0 songs ", NamedTextColor.GRAY)
                .append(Component.text("[Empty]", NamedTextColor.DARK_GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("This setlist has no songs")))), messages.get(1));
    }

    @Test
    void playlistMessagesUsePlaylistCommandForQueueAndNavigation() {
        List<NeurokaraokeSetlist> playlists = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(i -> new NeurokaraokeSetlist("playlist-" + i, "Playlist " + i, 45, null))
                .toList();

        List<Component> messages = EvilkaraokeCommand.playlistMessages(2, playlists, "ek");

        assertEquals(Component.text("Public playlists (page 2):", NamedTextColor.GOLD), messages.getFirst());
        assertEquals(7, messages.size());
        assertEquals(Component.text("6. Playlist 1 - 45 songs ", NamedTextColor.GRAY)
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

        List<Component> messages = EvilkaraokeCommand.playlistMessages(1, playlists, "ek");

        assertEquals(Component.text("1. Empty Playlist - 0 songs ", NamedTextColor.GRAY)
                .append(Component.text("[Empty]", NamedTextColor.DARK_GRAY)
                        .hoverEvent(HoverEvent.showText(Component.text("This playlist has no songs")))), messages.get(1));
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
}
