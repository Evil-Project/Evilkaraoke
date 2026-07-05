package org.evilproject.evilkaraoke.common.model;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import org.evilproject.evilkaraoke.common.security.AudioUrlValidator;

public final class UserAudioTracks {
    private static final int MAX_TITLE_LENGTH = 80;
    private static final String ARTIST = "User URL";

    private UserAudioTracks() {
    }

    public static KaraokeTrack fromUrl(String rawUrl) {
        return fromUrl(rawUrl, null);
    }

    public static KaraokeTrack fromUrl(String rawUrl, String requestedTitle) {
        return fromUrl(rawUrl, requestedTitle, java.net.InetAddress::getAllByName);
    }

    static KaraokeTrack fromUrl(String rawUrl, String requestedTitle, AudioUrlValidator.AddressResolver resolver) {
        URI uri = AudioUrlValidator.validatePublicHttpUrl(rawUrl, resolver);
        String normalizedUrl = uri.toASCIIString();
        String title = cleanTitle(requestedTitle);
        if (title == null) {
            title = titleFromUrl(uri);
        }
        return new KaraokeTrack(
                "url:" + stableSuffix(normalizedUrl),
                TrackType.SONG,
                title,
                ARTIST,
                new AudioAsset(normalizedUrl, formatFromPath(uri.getPath())),
                null,
                (Duration) null);
    }

    private static String titleFromUrl(URI uri) {
        String path = uri.getPath();
        if (path != null && !path.isBlank()) {
            int slash = path.lastIndexOf('/');
            String filename = slash >= 0 ? path.substring(slash + 1) : path;
            String withoutExtension = removeExtension(filename);
            String title = cleanTitle(withoutExtension.replace('-', ' ').replace('_', ' '));
            if (title != null) {
                return title;
            }
        }
        String hostTitle = cleanTitle(uri.getHost());
        return hostTitle == null ? "User URL" : hostTitle;
    }

    private static String removeExtension(String value) {
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot == value.length() - 1 || value.length() - dot > 6) {
            return value;
        }
        return value.substring(0, dot);
    }

    private static AudioFormat formatFromPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".opus")) {
            return AudioFormat.OPUS;
        }
        if (lower.endsWith(".ogg") || lower.endsWith(".oga")) {
            return AudioFormat.OGG;
        }
        if (lower.endsWith(".mp3")) {
            return AudioFormat.MP3;
        }
        return AudioFormat.UNKNOWN;
    }

    private static String cleanTitle(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder();
        boolean previousWhitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (!previousWhitespace && !cleaned.isEmpty()) {
                    cleaned.append(' ');
                }
                previousWhitespace = true;
            } else if (Character.isISOControl(ch)) {
                continue;
            } else {
                cleaned.append(ch);
                previousWhitespace = false;
            }
        }
        String title = cleaned.toString().trim();
        if (title.isBlank()) {
            return null;
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            return title.substring(0, MAX_TITLE_LENGTH).trim();
        }
        return title;
    }

    private static String stableSuffix(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
