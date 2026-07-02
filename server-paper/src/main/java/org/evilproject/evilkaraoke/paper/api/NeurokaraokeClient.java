package org.evilproject.evilkaraoke.paper.api;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.TrackType;

public final class NeurokaraokeClient {
    private static final int SEARCH_PAGE_SIZE = 5;

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
        return search(query, 0, SEARCH_PAGE_SIZE);
    }

    public CompletableFuture<java.util.List<KaraokeTrack>> search(String query, int page, int pageSize) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("search", query);
        requestBody.addProperty("page", Math.max(0, page));
        requestBody.addProperty("pageSize", Math.max(1, pageSize));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.searchUrl()))
                .timeout(Duration.ofMillis(endpoints.timeoutMillis()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Evilkaraoke/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build();
        return send(request).thenApply(this::tracksFromSearchJson);
    }

    public CompletableFuture<java.util.List<NeurokaraokeSetlist>> setlists(int startIndex, int pageSize) {
        String url = playlistsUrl()
                + "?startIndex=" + Math.max(0, startIndex)
                + "&pageSize=" + Math.max(1, pageSize)
                + "&search=&sortBy=&sortDescending=False&isSetlist=True&year=0";
        return getJson(url).thenApply(this::setlistsFromJson);
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
        return sendAttempt(request, 0);
    }

    /**
     * Sends {@code request} and retries on transient network errors
     * ({@link HttpTimeoutException}, {@link ConnectException}, other {@link IOException})
     * up to {@code endpoints.retries()} additional attempts, waiting
     * {@code endpoints.retryDelayMillis()} between each. HTTP 4xx responses are
     * never retried (client-side error). HTTP 5xx responses are retried like
     * network failures.
     */
    private CompletableFuture<String> sendAttempt(HttpRequest request, int attempt) {
        int maxAttempts = Math.max(1, endpoints.retries() + 1);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<CompletableFuture<String>>handle((response, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex instanceof CompletionException && ex.getCause() != null
                                ? ex.getCause() : ex;
                        if (isTransient(cause) && attempt + 1 < maxAttempts) {
                            logger.warning("Neurokaraoke API unreachable (attempt " + (attempt + 1)
                                    + "/" + maxAttempts + "), retrying in " + endpoints.retryDelayMillis()
                                    + " ms: " + cause.getClass().getSimpleName());
                            return scheduleRetry(request, attempt + 1);
                        }
                        return CompletableFuture.failedFuture(cause);
                    }
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return CompletableFuture.completedFuture(response.body());
                    }
                    logger.warning("Neurokaraoke API request failed: " + response.statusCode()
                            + " " + request.uri());
                    // 4xx = client error; never retry.
                    if (response.statusCode() >= 400 && response.statusCode() < 500) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("Neurokaraoke API returned " + response.statusCode()));
                    }
                    // 5xx = server error; retry if attempts remain.
                    if (attempt + 1 < maxAttempts) {
                        return scheduleRetry(request, attempt + 1);
                    }
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Neurokaraoke API returned " + response.statusCode()
                                    + " (all " + maxAttempts + " attempts exhausted)"));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<String> scheduleRetry(HttpRequest request, int nextAttempt) {
        Executor delayed = CompletableFuture.delayedExecutor(
                endpoints.retryDelayMillis(), TimeUnit.MILLISECONDS);
        return CompletableFuture.supplyAsync(() -> null, delayed)
                .thenCompose(ignored -> sendAttempt(request, nextAttempt));
    }

    private static boolean isTransient(Throwable ex) {
        return ex instanceof HttpTimeoutException
                || ex instanceof ConnectException
                || ex instanceof IOException;
    }

    KaraokeTrack trackFromJson(String json) {
        return trackFromElement(firstTrackElement(json));
    }

    java.util.List<KaraokeTrack> tracksFromSearchJson(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException ex) {
            throw new IllegalStateException("Neurokaraoke API returned invalid JSON", ex);
        }
        JsonArray results = searchResultsArray(parsed);
        java.util.List<KaraokeTrack> tracks = new java.util.ArrayList<>();
        if (results != null) {
            for (var element : results) {
                tracks.add(trackFromElement(element));
            }
        }
        return tracks;
    }

    java.util.List<NeurokaraokeSetlist> setlistsFromJson(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException ex) {
            throw new IllegalStateException("Neurokaraoke API returned invalid JSON", ex);
        }
        JsonArray results = playlistsArray(parsed);
        java.util.List<NeurokaraokeSetlist> setlists = new java.util.ArrayList<>();
        if (results != null) {
            for (var element : results) {
                setlists.add(setlistFromElement(element));
            }
        }
        return setlists;
    }

    private JsonArray searchResultsArray(JsonElement parsed) {
        if (parsed == null || parsed.isJsonNull()) {
            return null;
        }
        if (parsed.isJsonArray()) {
            return parsed.getAsJsonArray();
        }
        if (parsed.isJsonObject()) {
            JsonObject object = parsed.getAsJsonObject();
            for (String key : java.util.List.of("items", "results", "songs")) {
                if (object.has(key) && object.get(key).isJsonArray()) {
                    return object.getAsJsonArray(key);
                }
            }
        }
        throw new IllegalStateException("Neurokaraoke API returned unsupported search data");
    }

    private JsonArray playlistsArray(JsonElement parsed) {
        if (parsed == null || parsed.isJsonNull()) {
            return null;
        }
        if (parsed.isJsonArray()) {
            return parsed.getAsJsonArray();
        }
        if (parsed.isJsonObject()) {
            JsonObject object = parsed.getAsJsonObject();
            for (String key : java.util.List.of("items", "results", "playlists", "setlists")) {
                if (object.has(key) && object.get(key).isJsonArray()) {
                    return object.getAsJsonArray(key);
                }
            }
        }
        throw new IllegalStateException("Neurokaraoke API returned unsupported setlist data");
    }

    private JsonElement firstTrackElement(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException ex) {
            throw new IllegalStateException("Neurokaraoke API returned invalid JSON", ex);
        }
        if (parsed == null || parsed.isJsonNull()) {
            throw new IllegalStateException("Neurokaraoke API returned no song data");
        }
        if (parsed.isJsonArray()) {
            JsonArray array = parsed.getAsJsonArray();
            if (array.isEmpty()) {
                throw new IllegalStateException("Neurokaraoke API returned no songs");
            }
            return array.get(0);
        }
        if (parsed.isJsonObject()) {
            JsonObject object = parsed.getAsJsonObject();
            for (String key : java.util.List.of("song", "track", "result")) {
                if (object.has(key) && object.get(key).isJsonObject()) {
                    return object.get(key);
                }
            }
            for (String key : java.util.List.of("results", "songs")) {
                if (object.has(key) && object.get(key).isJsonArray()) {
                    JsonArray array = object.getAsJsonArray(key);
                    if (array.isEmpty()) {
                        throw new IllegalStateException("Neurokaraoke API returned no songs");
                    }
                    return array.get(0);
                }
            }
            return object;
        }
        throw new IllegalStateException("Neurokaraoke API returned unsupported song data");
    }

    private KaraokeTrack trackFromElement(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException("Neurokaraoke API returned malformed song data");
        }
        JsonObject object = element.getAsJsonObject();
        String id = firstString(object, "id", "_id", "songId");
        String title = firstString(object, "title", "name", "songName");
        String artist = firstString(object, "artist", "artistName", "uploader");
        if (artist == null) {
            artist = firstStringArray(object, "originalArtists", "coverArtists");
        }
        String opus = firstString(object, "opus", "opusPath");
        String absolute = firstString(object, "absolutePath", "path", "audio");
        AudioAsset primary = asset(opus, AudioFormat.OPUS);
        AudioAsset fallback = absolute == null ? null : asset(absolute, AudioFormat.UNKNOWN);
        return new KaraokeTrack(id == null ? title : id, TrackType.SONG, title == null ? "Unknown Song" : title, artist, primary == null ? fallback : primary, fallback, duration(object));
    }

    private NeurokaraokeSetlist setlistFromElement(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException("Neurokaraoke API returned malformed setlist data");
        }
        JsonObject object = element.getAsJsonObject();
        String id = firstString(object, "id", "_id", "playlistId");
        String name = firstString(object, "name", "title");
        int songCount = firstInt(object, "songCount", "songsCount", "trackCount");
        Duration totalDuration = object.has("totalDuration") && !object.get("totalDuration").isJsonNull()
                ? Duration.ofSeconds(object.get("totalDuration").getAsLong())
                : null;
        java.util.List<KaraokeTrack> songs = setlistSongs(object);
        return new NeurokaraokeSetlist(id, name, songCount, totalDuration, songs);
    }

    private java.util.List<KaraokeTrack> setlistSongs(JsonObject object) {
        JsonArray songs = null;
        for (String key : java.util.List.of("songListDTOs", "songs", "tracks")) {
            if (object.has(key) && object.get(key).isJsonArray()) {
                songs = object.getAsJsonArray(key);
                break;
            }
        }
        if (songs == null || songs.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<JsonElement> orderedSongs = new java.util.ArrayList<>();
        for (JsonElement song : songs) {
            if (song != null && song.isJsonObject()) {
                orderedSongs.add(song);
            }
        }
        orderedSongs.sort(java.util.Comparator.comparingInt(this::songOrder));
        java.util.List<KaraokeTrack> tracks = new java.util.ArrayList<>();
        for (JsonElement song : orderedSongs) {
            tracks.add(trackFromElement(song));
        }
        return tracks;
    }

    private int songOrder(JsonElement element) {
        JsonObject object = element.getAsJsonObject();
        if (object.has("order") && !object.get("order").isJsonNull()) {
            return object.get("order").getAsInt();
        }
        return Integer.MAX_VALUE;
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
                String value = stringValue(object.get(key));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String firstStringArray(JsonObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key) || !object.get(key).isJsonArray()) {
                continue;
            }
            java.util.List<String> values = new java.util.ArrayList<>();
            for (JsonElement element : object.getAsJsonArray(key)) {
                String value = stringValue(element);
                if (value != null) {
                    values.add(value);
                }
            }
            if (!values.isEmpty()) {
                return String.join(", ", values);
            }
        }
        return null;
    }

    private int firstInt(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsInt();
            }
        }
        return 0;
    }

    private String stringValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            return value.isBlank() ? null : value;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : java.util.List.of("name", "title", "artistName", "displayName", "username")) {
                if (object.has(key)) {
                    String value = stringValue(object.get(key));
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private Duration duration(JsonObject object) {
        if (!object.has("duration") || object.get("duration").isJsonNull()) {
            return null;
        }
        return Duration.ofSeconds(object.get("duration").getAsLong());
    }

    private String playlistsUrl() {
        String configured = endpoints.playlistUrl();
        if (configured == null || configured.isBlank()) {
            return "https://api.neurokaraoke.com/api/playlists";
        }
        String base = configured;
        int queryIndex = base.indexOf('?');
        if (queryIndex >= 0) {
            base = base.substring(0, queryIndex);
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/playlist")) {
            return base.substring(0, base.length() - "/playlist".length()) + "/playlists";
        }
        return base;
    }
}
