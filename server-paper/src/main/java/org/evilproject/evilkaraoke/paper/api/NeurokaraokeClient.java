package org.evilproject.evilkaraoke.paper.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.TrackType;

public final class NeurokaraokeClient {
    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final NeurokaraokeEndpoints endpoints;

    public NeurokaraokeClient(Logger logger, NeurokaraokeEndpoints endpoints) {
        this.logger = logger;
        this.endpoints = endpoints;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(endpoints.timeoutMillis()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CompletableFuture<KaraokeTrack> randomSong() {
        return getJson(endpoints.randomUrl()).thenApply(this::trackFromJson);
    }

    public CompletableFuture<KaraokeTrack> song(String id) {
        return getJson(endpoints.songUrl() + id).thenApply(this::trackFromJson);
    }

    public CompletableFuture<KaraokeTrack> radio(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("radio21")) {
            return CompletableFuture.completedFuture(new KaraokeTrack(
                    "radio21",
                    TrackType.RADIO,
                    "Radio21",
                    "Neurokaraoke Radio",
                    new AudioAsset("https://radio.twinskaraoke.com/radio/8000/radio.opus", AudioFormat.STREAM),
                    null,
                    null));
        }
        if (normalized.equals("swarmfm")) {
            return CompletableFuture.completedFuture(new KaraokeTrack(
                    "swarmfm",
                    TrackType.RADIO,
                    "SwarmFM",
                    "SwarmFM",
                    new AudioAsset("https://cast.sw.arm.fm/stream", AudioFormat.STREAM),
                    null,
                    null));
        }
        return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown radio station: " + name));
    }

    public CompletableFuture<java.util.List<KaraokeTrack>> search(String query) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("search", query);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.searchUrl()))
                .timeout(Duration.ofMillis(endpoints.timeoutMillis()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Evilkaraoke/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build();
        return send(request).thenApply(body -> {
            JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
            JsonArray results = parsed.has("results") ? parsed.getAsJsonArray("results") : parsed.getAsJsonArray("songs");
            java.util.List<KaraokeTrack> tracks = new java.util.ArrayList<>();
            if (results != null) {
                for (var element : results) {
                    tracks.add(trackFromJson(element.toString()));
                }
            }
            return tracks;
        });
    }

    private CompletableFuture<String> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(endpoints.timeoutMillis()))
                .header("Accept", "application/json")
                .header("User-Agent", "Evilkaraoke/0.1")
                .GET()
                .build();
        return send(request);
    }

    private CompletableFuture<String> send(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return CompletableFuture.completedFuture(response.body());
                    }
                    logger.warning("Neurokaraoke API request failed: " + response.statusCode() + " " + request.uri());
                    return CompletableFuture.failedFuture(new IllegalStateException("Neurokaraoke API returned " + response.statusCode()));
                });
    }

    private KaraokeTrack trackFromJson(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        String id = firstString(object, "id", "_id", "songId");
        String title = firstString(object, "title", "name", "songName");
        String artist = firstString(object, "artist", "artistName", "uploader");
        String opus = firstString(object, "opus", "opusPath");
        String absolute = firstString(object, "absolutePath", "path", "audio");
        AudioAsset primary = asset(opus, AudioFormat.OPUS);
        AudioAsset fallback = absolute == null ? null : asset(absolute, AudioFormat.UNKNOWN);
        return new KaraokeTrack(id == null ? title : id, TrackType.SONG, title == null ? "Unknown Song" : title, artist, primary == null ? fallback : primary, fallback, null);
    }

    private AudioAsset asset(String value, AudioFormat format) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String url = value.startsWith("http://") || value.startsWith("https://") ? value : endpoints.audioBaseUrl() + value;
        return new AudioAsset(url, format);
    }

    private String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsString();
            }
        }
        return null;
    }
}
