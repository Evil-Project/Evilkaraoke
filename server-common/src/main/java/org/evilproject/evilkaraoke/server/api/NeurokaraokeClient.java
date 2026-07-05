package org.evilproject.evilkaraoke.server.api;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
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
    private static final String NO_SONGS_FOUND_IN_PLAYLIST = "No songs found in playlist";
    private static final HttpClient.Version API_HTTP_VERSION = HttpClient.Version.HTTP_1_1;

    private final Logger logger;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final NeurokaraokeEndpoints endpoints;

    public NeurokaraokeClient(Logger logger, NeurokaraokeEndpoints endpoints) {
        this.logger = logger;
        this.endpoints = endpoints;
        this.httpClient = HttpClient.newBuilder()
                .version(API_HTTP_VERSION)
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
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.equals("radio21")) {
            return CompletableFuture.completedFuture(new KaraokeTrack(
                    "radio21",
                    TrackType.RADIO,
                    "Radio21",
                    "Neurokaraoke Radio",
                    new AudioAsset("https://radio.twinskaraoke.com/radio/8000/radio.ogg", AudioFormat.STREAM),
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
                .version(API_HTTP_VERSION)
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

    public CompletableFuture<java.util.List<NeurokaraokeSetlist>> publicPlaylists(int startIndex, int pageSize) {
        String url = publicPlaylistsUrl()
                + "?startIndex=" + Math.max(0, startIndex)
                + "&pageSize=" + Math.max(1, pageSize)
                + "&search=&sortBy=UpdatedAt&sortDescending=True";
        return getJson(url).thenApply(this::setlistsFromJson);
    }

    public CompletableFuture<NeurokaraokeSetlist> publicPlaylist(String id) {
        return getJson(publicPlaylistDetailUrl(id)).handle((json, ex) -> {
            if (ex == null) {
                return publicPlaylistFromJson(id, json);
            }
            Throwable cause = unwrapCompletion(ex);
            if (isEmptyPublicPlaylist(cause)) {
                return new NeurokaraokeSetlist(id, id, 0, null);
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CompletionException(cause);
        });
    }

    private CompletableFuture<String> getJson(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .version(API_HTTP_VERSION)
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

    private CompletableFuture<String> sendAttempt(HttpRequest request, int attempt) {
        int maxAttempts = Math.max(1, endpoints.retries() + 1);
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<CompletableFuture<String>>handle((response, ex) -> {
                    if (ex != null) {
                        Throwable cause = unwrapCompletion(ex);
                        if (isTransient(cause)) {
                            if (attempt + 1 >= maxAttempts) {
                                logger.warning("Neurokaraoke API unreachable after " + maxAttempts
                                        + " attempts: " + exceptionSummary(cause));
                                return CompletableFuture.failedFuture(new NeurokaraokeApiUnavailableException(
                                        request.uri(), maxAttempts, cause));
                            }
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
                    logger.warning("Neurokaraoke API request failed: " + response.statusCode() + " " + request.uri());
                    if (response.statusCode() >= 400 && response.statusCode() < 500) {
                        return CompletableFuture.failedFuture(new NeurokaraokeApiException(response.statusCode(), response.body()));
                    }
                    if (attempt + 1 < maxAttempts) {
                        return scheduleRetry(request, attempt + 1);
                    }
                    return CompletableFuture.failedFuture(new IllegalStateException("Neurokaraoke API returned "
                            + response.statusCode() + " (all " + maxAttempts + " attempts exhausted)"));
                })
                .thenCompose(f -> f);
    }

    private CompletableFuture<String> scheduleRetry(HttpRequest request, int nextAttempt) {
        Executor delayed = CompletableFuture.delayedExecutor(endpoints.retryDelayMillis(), TimeUnit.MILLISECONDS);
        return CompletableFuture.supplyAsync(() -> null, delayed).thenCompose(ignored -> sendAttempt(request, nextAttempt));
    }

    static boolean isTransient(Throwable ex) {
        return ex instanceof HttpTimeoutException || ex instanceof ConnectException || ex instanceof IOException;
    }

    private static String exceptionSummary(Throwable ex) {
        String name = ex.getClass().getSimpleName();
        String message = ex.getMessage();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    private static Throwable unwrapCompletion(Throwable ex) {
        Throwable cause = ex;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static boolean isEmptyPublicPlaylist(Throwable ex) {
        return ex instanceof NeurokaraokeApiException apiException
                && apiException.statusCode() == 400
                && apiException.body().contains(NO_SONGS_FOUND_IN_PLAYLIST);
    }

    KaraokeTrack trackFromJson(String json) {
        return trackFromElement(firstTrackElement(json));
    }

    java.util.List<KaraokeTrack> tracksFromSearchJson(String json) {
        JsonElement parsed = parse(json);
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
        JsonElement parsed = parse(json);
        JsonArray results = playlistsArray(parsed);
        java.util.List<NeurokaraokeSetlist> setlists = new java.util.ArrayList<>();
        if (results != null) {
            for (var element : results) {
                setlists.add(setlistFromElement(element));
            }
        }
        return setlists;
    }

    NeurokaraokeSetlist publicPlaylistFromJson(String id, String json) {
        JsonElement parsed = parse(json);
        if (parsed == null || parsed.isJsonNull()) {
            throw new IllegalStateException("Neurokaraoke API returned no playlist data");
        }
        if (parsed.isJsonArray()) {
            JsonArray array = parsed.getAsJsonArray();
            if (array.isEmpty()) {
                throw new IllegalStateException("Neurokaraoke API returned no playlists");
            }
            return setlistFromElement(array.get(0), id);
        }
        return setlistFromElement(parsed, id);
    }

    private static JsonElement parse(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (JsonParseException ex) {
            throw new IllegalStateException("Neurokaraoke API returned invalid JSON", ex);
        }
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
        JsonElement parsed = parse(json);
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
        String absolute = firstString(object, "absolutePath", "path", "audio", "audioUrl", "oss");
        AudioAsset primary = asset(opus, AudioFormat.OPUS);
        AudioAsset fallback = absolute == null ? null : asset(absolute, AudioFormat.UNKNOWN);
        AudioAsset resolved = primary != null ? primary : fallback;
        if (resolved == null) {
            throw new IllegalStateException("Neurokaraoke API returned a track with no audio URL (id=" + id + ")");
        }
        return new KaraokeTrack(id == null ? title : id, TrackType.SONG, title == null ? "Unknown Song" : title, artist, resolved, fallback, duration(object));
    }

    private NeurokaraokeSetlist setlistFromElement(JsonElement element) {
        return setlistFromElement(element, null);
    }

    private NeurokaraokeSetlist setlistFromElement(JsonElement element, String fallbackId) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalStateException("Neurokaraoke API returned malformed setlist data");
        }
        JsonObject object = element.getAsJsonObject();
        String id = firstString(object, "id", "_id", "playlistId");
        if (id == null || id.isBlank()) {
            id = fallbackId;
        }
        String name = firstString(object, "name", "title");
        int songCount = firstInt(object, "songCount", "songsCount", "trackCount");
        Duration totalDuration = object.has("totalDuration") && !object.get("totalDuration").isJsonNull()
                ? Duration.ofSeconds(object.get("totalDuration").getAsLong())
                : null;
        java.util.List<KaraokeTrack> songs = setlistSongs(object);
        if (songCount == 0 && !songs.isEmpty()) {
            songCount = songs.size();
        }
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
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return new AudioAsset(value, format);
        }
        String base = endpoints.audioBaseUrl() == null ? "" : endpoints.audioBaseUrl();
        if (!base.endsWith("/") && !value.startsWith("/")) {
            base += "/";
        }
        return new AudioAsset(base + value, format);
    }

    private String playlistsUrl() {
        String configured = endpoints.playlistUrl();
        if (configured == null || configured.isBlank()) {
            return "https://api.neurokaraoke.com/api/playlists";
        }
        String base = withoutQuery(configured);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/playlist")) {
            return base.substring(0, base.length() - "/playlist".length()) + "/playlists";
        }
        return base;
    }

    private String publicPlaylistsUrl() {
        String configured = endpoints.publicPlaylistUrl();
        if (configured == null || configured.isBlank()) {
            return "https://api.neurokaraoke.com/api/playlist/public";
        }
        String base = withoutQuery(configured);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String publicPlaylistDetailUrl(String id) {
        String configured = endpoints.publicPlaylistDetailUrl();
        String base = configured == null || configured.isBlank()
                ? "https://idk.neurokaraoke.com/public/playlist/"
                : withoutQuery(configured);
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + id;
    }

    private static String withoutQuery(String url) {
        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                String value = object.get(key).getAsString();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static int firstInt(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return object.get(key).getAsInt();
            }
        }
        return 0;
    }

    private static String firstStringArray(JsonObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                continue;
            }
            JsonElement element = object.get(key);
            if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (!value.isBlank()) {
                    return value;
                }
            }
            if (element.isJsonArray()) {
                java.util.List<String> values = new java.util.ArrayList<>();
                for (JsonElement arrayElement : element.getAsJsonArray()) {
                    String value = stringValue(arrayElement);
                    if (value != null) {
                        values.add(value);
                    }
                }
                if (!values.isEmpty()) {
                    return String.join(", ", values);
                }
            }
        }
        return null;
    }

    private static String stringValue(JsonElement element) {
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

    private static Duration duration(JsonObject object) {
        for (String key : java.util.List.of("duration", "lengthSeconds", "durationSeconds")) {
            if (object.has(key) && !object.get(key).isJsonNull()) {
                return Duration.ofSeconds(object.get(key).getAsLong());
            }
        }
        return null;
    }

    private static final class NeurokaraokeApiException extends IllegalStateException {
        private final int statusCode;
        private final String body;

        private NeurokaraokeApiException(int statusCode, String body) {
            super(apiErrorMessage(statusCode, body));
            this.statusCode = statusCode;
            this.body = body == null ? "" : body.strip();
        }

        private int statusCode() {
            return statusCode;
        }

        private String body() {
            return body;
        }
    }

    private static String apiErrorMessage(int statusCode, String body) {
        String detail = errorDetail(body);
        if (detail == null) {
            return "Neurokaraoke API returned " + statusCode;
        }
        return "Neurokaraoke API returned " + statusCode + ": " + detail;
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String detail = body.strip().replaceAll("\\s+", " ");
        return detail.length() <= 160 ? detail : detail.substring(0, 157) + "...";
    }
}
