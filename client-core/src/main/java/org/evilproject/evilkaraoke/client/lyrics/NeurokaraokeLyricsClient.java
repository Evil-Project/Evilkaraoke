package org.evilproject.evilkaraoke.client.lyrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** Fetches the dedicated timed-lyrics resource for a Neurokaraoke song. */
public final class NeurokaraokeLyricsClient implements LyricsProvider {
    private static final URI SONGS_API = URI.create("https://api.neurokaraoke.com/api/songs/");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_CUES = 4_096;
    private static final int MAX_TEXT_CODE_POINTS = 256;
    private static final Pattern TIMESPAN = Pattern.compile("^(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?$");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final HttpClient httpClient;
    private final URI songsApi;

    public NeurokaraokeLyricsClient() {
        this(HTTP_CLIENT, SONGS_API);
    }

    NeurokaraokeLyricsClient(HttpClient httpClient, URI songsApi) {
        this.httpClient = httpClient;
        this.songsApi = songsApi;
    }

    @Override
    public CompletableFuture<List<TimedLyric>> lyrics(UUID songId) {
        HttpRequest request = HttpRequest.newBuilder(lyricsUri(songsApi, songId))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Evilkaraoke-Client/1.0")
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 404) {
                        return List.of();
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Neurokaraoke lyrics API returned HTTP " + response.statusCode());
                    }
                    return parseLyrics(response.body());
                });
    }

    static URI lyricsUri(URI songsApi, UUID songId) {
        return songsApi.resolve(songId + "/lyrics");
    }

    static List<TimedLyric> parseLyrics(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException ex) {
            throw new IllegalStateException("Neurokaraoke lyrics API returned invalid JSON", ex);
        }
        if (!root.isJsonArray()) {
            throw new IllegalStateException("Neurokaraoke lyrics API returned unsupported data");
        }

        List<TimedLyric> lyrics = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (lyrics.size() >= MAX_CUES) {
                break;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            var object = element.getAsJsonObject();
            if (!object.has("time") || !object.has("text")
                    || !object.get("time").isJsonPrimitive() || !object.get("text").isJsonPrimitive()) {
                continue;
            }
            try {
                Duration offset = parseTimespan(object.get("time").getAsString());
                String text = normalizeText(object.get("text").getAsString());
                if (!text.isBlank()) {
                    lyrics.add(new TimedLyric(offset, text));
                }
            } catch (IllegalArgumentException | ArithmeticException ignored) {
                // One malformed cue must not hide the rest of a song's lyrics.
            }
        }
        lyrics.sort(Comparator.comparing(TimedLyric::offset));
        return List.copyOf(lyrics);
    }

    static Duration parseTimespan(String value) {
        Matcher matcher = TIMESPAN.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid Neurokaraoke lyric timestamp: " + value);
        }
        long hours = Long.parseLong(matcher.group(1));
        int minutes = Integer.parseInt(matcher.group(2));
        int seconds = Integer.parseInt(matcher.group(3));
        if (minutes >= 60 || seconds >= 60) {
            throw new IllegalArgumentException("Invalid Neurokaraoke lyric timestamp: " + value);
        }
        String fraction = matcher.group(4);
        int nanos = fraction == null ? 0 : Integer.parseInt((fraction + "000000000").substring(0, 9));
        return Duration.ofHours(hours)
                .plusMinutes(minutes)
                .plusSeconds(seconds)
                .plusNanos(nanos);
    }

    private static String normalizeText(String value) {
        StringBuilder normalized = new StringBuilder();
        boolean previousWhitespace = true;
        int codePoints = 0;
        for (int offset = 0; offset < value.length() && codePoints < MAX_TEXT_CODE_POINTS;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                if (!previousWhitespace) {
                    normalized.append(' ');
                    previousWhitespace = true;
                }
                continue;
            }
            normalized.appendCodePoint(codePoint);
            previousWhitespace = false;
            codePoints++;
        }
        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') {
            normalized.setLength(length - 1);
        }
        return normalized.toString();
    }
}
