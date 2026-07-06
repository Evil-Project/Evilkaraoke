package org.evilproject.evilkaraoke.paper.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.junit.jupiter.api.Test;

class NeurokaraokeClientTest {
    private final NeurokaraokeClient client = new NeurokaraokeClient(
            Logger.getLogger("test"),
            new NeurokaraokeEndpoints(
                    1000,
                    0,
                    0,
                    "https://api.example/random",
                    "https://api.example/search",
                    "https://api.example/songs/",
                    "https://api.example/playlists/",
                    "https://api.example/playlist/public",
                    "https://idk.example/public/playlist/",
                    "https://api.example/artists/",
                    "https://audio.example/",
                    "https://images.example/"));

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
    void trackFromJsonAcceptsArrayResponse() {
        var track = client.trackFromJson("""
                [{
                  "id": "song-1",
                  "title": "Never",
                  "originalArtists": ["Tester", "Example"],
                  "opus": "songs/never.opus",
                  "duration": 123
                }]
                """);

        assertEquals("song-1", track.id());
        assertEquals("Never", track.title());
        assertEquals("Tester, Example", track.artist());
        assertEquals("https://audio.example/songs/never.opus", track.primaryAsset().url());
        assertEquals(AudioFormat.OPUS, track.primaryAsset().format());
        assertEquals(Duration.ofSeconds(123), track.duration());
    }

    @Test
    void trackFromJsonRejectsEmptyArrayWithShortMessage() {
        var error = assertThrows(IllegalStateException.class, () -> client.trackFromJson("[]"));

        assertEquals("Neurokaraoke API returned no songs", error.getMessage());
    }

    @Test
    void trackFromJsonAcceptsArtistObjectArray() {
        var track = client.trackFromJson("""
                {
                  "id": "song-2",
                  "title": "Object Artists",
                  "originalArtists": [
                    { "id": "artist-1", "name": "First Artist" },
                    { "id": "artist-2", "title": "Second Artist" }
                  ],
                  "opus": "songs/object-artists.opus"
                }
                """);

        assertEquals("song-2", track.id());
        assertEquals("Object Artists", track.title());
        assertEquals("First Artist, Second Artist", track.artist());
    }

    @Test
    void searchPostsPaginationAndParsesItemsEnvelope() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/songs", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {
                      "items": [{
                        "id": "song-hello",
                        "title": "Hello",
                        "originalArtists": ["Adele"],
                        "opus": "song-opus/hello.ogg",
                        "absolutePath": "audio/Adele%20-%20Hello.mp3",
                        "duration": 297
                      }],
                      "totalCount": 1,
                      "page": 0,
                      "pageSize": 5
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
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
                            "http://localhost:" + server.getAddress().getPort() + "/playlists/",
                            "http://localhost:" + server.getAddress().getPort() + "/playlist/public",
                            "http://localhost:" + server.getAddress().getPort() + "/public/playlist/",
                            "http://localhost:" + server.getAddress().getPort() + "/artists/",
                            "https://audio.example/",
                            "https://images.example/"));

            var tracks = localClient.search("hello").join();

            assertTrue(requestBody.get().contains("\"search\":\"hello\""));
            assertTrue(requestBody.get().contains("\"page\":0"));
            assertTrue(requestBody.get().contains("\"pageSize\":5"));
            assertEquals(1, tracks.size());
            assertEquals("song-hello", tracks.getFirst().id());
            assertEquals("Hello", tracks.getFirst().title());
            assertEquals("Adele", tracks.getFirst().artist());
            assertEquals("https://audio.example/song-opus/hello.ogg", tracks.getFirst().primaryAsset().url());
            assertEquals(Duration.ofSeconds(297), tracks.getFirst().duration());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void setlistsUsesPlaylistCollectionEndpointAndParsesSummaries() throws IOException {
        AtomicReference<String> requestTarget = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/playlists", exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            byte[] response = """
                    [{
                      "id": "setlist-1",
                      "name": "Neuro Karaoke - June 24, 2026 Setlist",
                      "songCount": 18,
                      "totalDuration": 3951,
                      "songListDTOs": [
                        {
                          "id": "song-2",
                          "title": "Second",
                          "originalArtists": ["Two"],
                          "absolutePath": "audio/second.mp3",
                          "duration": 202,
                          "order": 2
                        },
                        {
                          "id": "song-1",
                          "title": "First",
                          "originalArtists": ["One"],
                          "opus": "song-opus/first.ogg",
                          "duration": 101,
                          "order": 1
                        }
                      ]
                    }]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
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

            var setlists = localClient.setlists(5, 5).join();

            assertTrue(requestTarget.get().contains("/api/playlists?"));
            assertTrue(requestTarget.get().contains("startIndex=5"));
            assertTrue(requestTarget.get().contains("pageSize=5"));
            assertTrue(requestTarget.get().contains("isSetlist=True"));
            assertEquals(1, setlists.size());
            assertEquals("setlist-1", setlists.getFirst().id());
            assertEquals("Neuro Karaoke - June 24, 2026 Setlist", setlists.getFirst().name());
            assertEquals(18, setlists.getFirst().songCount());
            assertEquals(Duration.ofSeconds(3951), setlists.getFirst().totalDuration());
            assertEquals(2, setlists.getFirst().songs().size());
            assertEquals("song-1", setlists.getFirst().songs().getFirst().id());
            assertEquals("https://audio.example/song-opus/first.ogg", setlists.getFirst().songs().getFirst().primaryAsset().url());
            assertEquals("song-2", setlists.getFirst().songs().get(1).id());
            assertEquals("https://audio.example/audio/second.mp3", setlists.getFirst().songs().get(1).primaryAsset().url());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publicPlaylistsUsesPublicEndpointAndParsesSummaries() throws IOException {
        AtomicReference<String> requestTarget = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/playlist/public", exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            byte[] response = """
                    [{
                      "id": "playlist-1",
                      "name": "Public Mix",
                      "songCount": 45,
                      "playCount": 23,
                      "favoriteCount": 1,
                      "songListDTOs": null,
                      "isPublic": true,
                      "isSetList": false
                    }]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
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

            var playlists = localClient.publicPlaylists(5, 5).join();

            assertTrue(requestTarget.get().contains("/api/playlist/public?"));
            assertTrue(requestTarget.get().contains("startIndex=5"));
            assertTrue(requestTarget.get().contains("pageSize=5"));
            assertTrue(requestTarget.get().contains("sortBy=UpdatedAt"));
            assertTrue(requestTarget.get().contains("sortDescending=True"));
            assertEquals(1, playlists.size());
            assertEquals("playlist-1", playlists.getFirst().id());
            assertEquals("Public Mix", playlists.getFirst().name());
            assertEquals(45, playlists.getFirst().songCount());
            assertTrue(playlists.getFirst().songs().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publicPlaylistLoadsAnonymousDetailSongs() throws IOException {
        AtomicReference<String> requestTarget = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/public/playlist/playlist-1", exchange -> {
            requestTarget.set(exchange.getRequestURI().toString());
            byte[] response = """
                    {
                      "name": "Public Mix",
                      "songs": [{
                        "title": "The Disease Called Love",
                        "originalArtists": "Neru",
                        "coverArtists": "Neuro & Evil",
                        "audioUrl": "https://storage.neurokaraoke.com/audio/disease.mp3"
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
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

            var playlist = localClient.publicPlaylist("playlist-1").join();

            assertEquals("/public/playlist/playlist-1", requestTarget.get());
            assertEquals("playlist-1", playlist.id());
            assertEquals("Public Mix", playlist.name());
            assertEquals(1, playlist.songCount());
            assertEquals(1, playlist.songs().size());
            assertEquals("The Disease Called Love", playlist.songs().getFirst().title());
            assertEquals("Neru", playlist.songs().getFirst().artist());
            assertEquals("Neuro & Evil", playlist.songs().getFirst().coveredBy().orElseThrow());
            assertEquals("https://storage.neurokaraoke.com/audio/disease.mp3", playlist.songs().getFirst().primaryAsset().url());
            assertEquals(AudioFormat.UNKNOWN, playlist.songs().getFirst().primaryAsset().format());
        } finally {
            server.stop(0);
        }
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
