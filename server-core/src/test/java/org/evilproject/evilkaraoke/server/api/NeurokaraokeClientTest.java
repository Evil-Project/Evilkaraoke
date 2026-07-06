package org.evilproject.evilkaraoke.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.junit.jupiter.api.Test;

class NeurokaraokeClientTest {
    private final NeurokaraokeClient client = new NeurokaraokeClient(
            Logger.getLogger("test"),
            NeurokaraokeEndpoints.defaults());

    @Test
    void radio21UsesLiveOggStreamEndpoint() {
        var track = client.radio("radio21").join();

        assertEquals("radio21", track.id());
        assertEquals("https://radio.twinskaraoke.com/radio/8000/radio.ogg", track.primaryAsset().url());
        assertEquals(AudioFormat.STREAM, track.primaryAsset().format());
    }

    @Test
    void closedChannelErrorsAreTransientApiFailures() {
        assertTrue(NeurokaraokeClient.isTransient(new ClosedChannelException()));
    }

    @Test
    void exhaustedTransientFailureUsesApiUnavailableException() {
        NeurokaraokeClient localClient = new NeurokaraokeClient(
                Logger.getLogger("test"),
                unreachableEndpoints());

        CompletionException error = assertThrows(CompletionException.class, () -> localClient.search("never").join());

        assertInstanceOf(NeurokaraokeApiUnavailableException.class, error.getCause());
    }

    @Test
    void publicPlaylistDetailUsesFallbackIdAndAudioUrlSongs() {
        NeurokaraokeSetlist playlist = client.publicPlaylistFromJson("playlist-1", """
                {
                  "name": "Public Mix",
                  "songs": [{
                    "title": "The Disease Called Love",
                    "originalArtists": "Neru",
                    "coverArtists": "Neuro & Evil",
                    "audioUrl": "https://storage.neurokaraoke.com/audio/disease.mp3"
                  }]
                }
                """);

        assertEquals("playlist-1", playlist.id());
        assertEquals("Public Mix", playlist.name());
        assertEquals(1, playlist.songCount());
        assertEquals("The Disease Called Love", playlist.songs().getFirst().title());
        assertEquals("Neru", playlist.songs().getFirst().artist());
        assertEquals("Neuro & Evil", playlist.songs().getFirst().coveredBy().orElseThrow());
        assertEquals("https://storage.neurokaraoke.com/audio/disease.mp3", playlist.songs().getFirst().primaryAsset().url());
        assertEquals(AudioFormat.UNKNOWN, playlist.songs().getFirst().primaryAsset().format());
    }

    @Test
    void playlistSummariesCanHaveNullSongList() {
        var playlists = client.setlistsFromJson("""
                [{
                  "id": "playlist-1",
                  "name": "Public Mix",
                  "songCount": 45,
                  "songListDTOs": null,
                  "isPublic": true
                }]
                """);

        assertEquals(1, playlists.size());
        assertEquals("playlist-1", playlists.getFirst().id());
        assertEquals("Public Mix", playlists.getFirst().name());
        assertEquals(45, playlists.getFirst().songCount());
        assertEquals(0, playlists.getFirst().songs().size());
    }

    @Test
    void publicPlaylistTreatsNoSongsResponseAsEmptyPlaylist() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/public/playlist/empty-playlist", exchange -> {
            byte[] response = "No songs found in playlist".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            NeurokaraokeClient localClient = new NeurokaraokeClient(
                    Logger.getLogger("test"),
                    new NeurokaraokeEndpoints(
                            1000,
                            0,
                            0,
                            "http://localhost:" + server.getAddress().getPort() + "/random",
                            "http://localhost:" + server.getAddress().getPort() + "/songs",
                            "http://localhost:" + server.getAddress().getPort() + "/songs/",
                            "http://localhost:" + server.getAddress().getPort() + "/api/playlist/",
                            "http://localhost:" + server.getAddress().getPort() + "/api/playlist/public",
                            "http://localhost:" + server.getAddress().getPort() + "/public/playlist/",
                            "http://localhost:" + server.getAddress().getPort() + "/artists/",
                            "https://audio.example/",
                            "https://images.example/"));

            NeurokaraokeSetlist playlist = localClient.publicPlaylist("empty-playlist").join();

            assertEquals("empty-playlist", playlist.id());
            assertEquals(0, playlist.songCount());
            assertTrue(playlist.songs().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private static NeurokaraokeEndpoints unreachableEndpoints() {
        return new NeurokaraokeEndpoints(
                200,
                0,
                0,
                "http://127.0.0.1:1/random",
                "http://127.0.0.1:1/songs",
                "http://127.0.0.1:1/songs/",
                "http://127.0.0.1:1/api/playlist/",
                "http://127.0.0.1:1/api/playlist/public",
                "http://127.0.0.1:1/public/playlist/",
                "http://127.0.0.1:1/artists/",
                "https://audio.example/",
                "https://images.example/");
    }
}
