package org.evilproject.evilkaraoke.common.audio;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.common.security.AudioUrlValidator;

public final class ServerAudioStreamRelay implements AudioStreamRelay {
    public static final int CHUNK_BYTES = 16 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STREAM_PREBUFFER = Duration.ofSeconds(8);
    private static final long MIN_PREBUFFER_BYTES = 256L * 1024L;
    private static final long UNKNOWN_DURATION_BYTES_PER_SECOND = 512L * 1024L;
    private static final long MAX_PACE_SLEEP_MILLIS = 250L;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final UnaryOperator<URI> uriValidator;

    public ServerAudioStreamRelay() {
        this(AudioUrlValidator::validatePublicHttpUrl);
    }

    ServerAudioStreamRelay(UnaryOperator<URI> uriValidator) {
        this.uriValidator = Objects.requireNonNull(uriValidator, "uriValidator");
    }

    @Override
    public CompletableFuture<Void> relay(
            String sessionId,
            String playbackId,
            KaraokeTrack track,
            PlaybackTarget target,
            Duration offset,
            Instant serverTime,
            String reason,
            Consumer<ProtocolPacket> broadcaster,
            BooleanSupplier shouldContinue) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(playbackId, "playbackId");
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(broadcaster, "broadcaster");
        Objects.requireNonNull(shouldContinue, "shouldContinue");
        return CompletableFuture.runAsync(() -> relayBlocking(sessionId, playbackId, track, offset, serverTime, broadcaster, shouldContinue));
    }

    private void relayBlocking(String sessionId,
                               String playbackId,
                               KaraokeTrack track,
                               Duration offset,
                               Instant serverTime,
                               Consumer<ProtocolPacket> broadcaster,
                               BooleanSupplier shouldContinue) {
        int sequence = 0;
        StreamProgress progress = new StreamProgress();
        Consumer<ProtocolPacket> trackingBroadcaster = packet -> {
            if (packet instanceof AudioStreamChunkPacket chunk && !chunk.end() && chunk.error().isBlank()) {
                progress.bytesSent = true;
                progress.nextSequence = Math.max(progress.nextSequence, chunk.sequence() + 1);
            }
            broadcaster.accept(packet);
        };
        StreamResult result = null;
        Exception primaryError = null;
        try {
            result = relayAsset(sessionId, playbackId, track, track.primaryAsset(), sequence, offset, serverTime, trackingBroadcaster, shouldContinue);
            sequence = result.nextSequence();
            progress.nextSequence = Math.max(progress.nextSequence, sequence);
        } catch (PlaybackCancelledException ignored) {
            return;
        } catch (Exception ex) {
            primaryError = ex;
        }

        if (primaryError != null && !sentBytes(result, progress) && track.fallbackAsset() != null && shouldContinue.getAsBoolean()) {
            try {
                result = relayAsset(sessionId, playbackId, track, track.fallbackAsset(), sequence, offset, serverTime, trackingBroadcaster, shouldContinue);
                sequence = result.nextSequence();
                progress.nextSequence = Math.max(progress.nextSequence, sequence);
                primaryError = null;
            } catch (PlaybackCancelledException ignored) {
                return;
            } catch (Exception ex) {
                primaryError = ex;
            }
        }

        if (!shouldContinue.getAsBoolean()) {
            return;
        }
        if (primaryError != null) {
            broadcaster.accept(AudioStreamChunkPacket.error(sessionId, playbackId, Math.max(sequence, progress.nextSequence), safeError(primaryError)));
            return;
        }
        broadcaster.accept(AudioStreamChunkPacket.end(sessionId, playbackId, sequence));
    }

    private StreamResult relayAsset(String sessionId,
                                    String playbackId,
                                    KaraokeTrack track,
                                    AudioAsset asset,
                                    int sequence,
                                    Duration offset,
                                    Instant serverTime,
                                    Consumer<ProtocolPacket> broadcaster,
                                    BooleanSupplier shouldContinue) throws Exception {
        if (asset == null) {
            throw new IllegalStateException("track has no audio asset");
        }
        ensureContinuing(shouldContinue);
        if (asset.format() == AudioFormat.STREAM) {
            HttpResponse<InputStream> response = sendGet(asset.url(), null, 0);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalStateException("HTTP " + response.statusCode() + " for audio URL");
            }
            try (InputStream body = stripIcyMetadata(response.body(), response)) {
                return relayBody(sessionId, playbackId, sequence, body, RelayPacer.unpaced(), broadcaster, shouldContinue);
            }
        }

        HttpResponse<InputStream> response = sendGet(asset.url(), null, 0);
        try (InputStream body = response.body()) {
            if (response.statusCode() == 200) {
                RelayPacer pacer = RelayPacer.forFiniteTrack(track, offset, serverTime, contentLength(response));
                return relayBody(sessionId, playbackId, sequence, body, pacer, broadcaster, shouldContinue);
            }
            if (response.statusCode() != 206) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for audio URL");
            }
            ByteRange range = byteRange(response);
            if (range.start() != 0L) {
                throw new IllegalStateException("Unexpected initial byte range " + range.start());
            }
            RelayPacer pacer = RelayPacer.forFiniteTrack(track, offset, serverTime, range.total());
            StreamResult result = relayBody(sessionId, playbackId, sequence, body, pacer, broadcaster, shouldContinue);
            if (!range.hasKnownTotal() || range.isComplete()) {
                return result;
            }
            long nextStart = range.endInclusive() + 1;
            int nextSequence = result.nextSequence();
            boolean bytesSent = result.bytesSent();
            while (nextStart < range.total()) {
                ensureContinuing(shouldContinue);
                HttpResponse<InputStream> nextResponse = sendGet(asset.url(), "bytes=" + nextStart + "-", 0);
                try (InputStream nextBody = nextResponse.body()) {
                    if (nextResponse.statusCode() != 206) {
                        throw new IllegalStateException("HTTP " + nextResponse.statusCode() + " while requesting audio bytes from " + nextStart);
                    }
                    ByteRange nextRange = byteRange(nextResponse);
                    if (nextRange.start() != nextStart || !nextRange.hasKnownTotal() || nextRange.total() != range.total()) {
                        throw new IllegalStateException("Inconsistent Content-Range for audio URL");
                    }
                    StreamResult nextResult = relayBody(sessionId, playbackId, nextSequence, nextBody, pacer, broadcaster, shouldContinue);
                    nextSequence = nextResult.nextSequence();
                    bytesSent = bytesSent || nextResult.bytesSent();
                    nextStart = nextRange.endInclusive() + 1;
                }
            }
            return new StreamResult(nextSequence, bytesSent);
        }
    }

    private StreamResult relayBody(String sessionId,
                                   String playbackId,
                                   int sequence,
                                   InputStream body,
                                   RelayPacer pacer,
                                   Consumer<ProtocolPacket> broadcaster,
                                   BooleanSupplier shouldContinue) throws Exception {
        byte[] buffer = new byte[CHUNK_BYTES];
        boolean bytesSent = false;
        int nextSequence = sequence;
        int read;
        while ((read = body.read(buffer)) != -1) {
            ensureContinuing(shouldContinue);
            if (read == 0) {
                continue;
            }
            broadcaster.accept(AudioStreamChunkPacket.chunk(sessionId, playbackId, nextSequence++, buffer, read));
            pacer.afterBytesSent(read, shouldContinue);
            bytesSent = true;
        }
        return new StreamResult(nextSequence, bytesSent);
    }

    private HttpResponse<InputStream> sendGet(String url, String range, int redirects) throws Exception {
        URI uri = uriValidator.apply(audioUri(url));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(HTTP_REQUEST_TIMEOUT)
                .header("User-Agent", "Evilkaraoke-Server")
                .header("Icy-MetaData", "0")
                .GET();
        if (range != null) {
            builder.header("Range", range);
        }
        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (isRedirect(response.statusCode())) {
            response.body().close();
            if (redirects >= MAX_REDIRECTS) {
                throw new IllegalStateException("Too many redirects while requesting audio URL");
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Redirect response did not include Location"));
            URI redirectUri = uriValidator.apply(uri.resolve(location));
            return sendGet(redirectUri.toString(), range, redirects + 1);
        }
        return response;
    }

    private static URI audioUri(String url) {
        if (url == null) {
            throw new IllegalArgumentException("Audio URL is required");
        }
        return URI.create(url.replace(" ", "%20"));
    }

    private static InputStream stripIcyMetadata(InputStream body, HttpResponse<?> response) {
        int metadataInterval = icyMetadataInterval(response);
        return metadataInterval <= 0 ? body : new IcyMetadataInputStream(body, metadataInterval);
    }

    private static int icyMetadataInterval(HttpResponse<?> response) {
        return response.headers().firstValue("icy-metaint")
                .map(String::trim)
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException ex) {
                        return -1;
                    }
                })
                .filter(value -> value > 0)
                .orElse(-1);
    }

    private static ByteRange byteRange(HttpResponse<?> response) {
        String value = response.headers().firstValue("Content-Range")
                .orElseThrow(() -> new IllegalStateException("Partial response without Content-Range for audio URL"));
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalStateException("Unsupported Content-Range for audio URL");
        }
        long start = Long.parseLong(matcher.group(1));
        long endInclusive = Long.parseLong(matcher.group(2));
        long total = matcher.group(3).equals("*") ? -1L : Long.parseLong(matcher.group(3));
        if (endInclusive < start) {
            throw new IllegalStateException("Unsupported Content-Range for audio URL");
        }
        return new ByteRange(start, endInclusive, total);
    }

    private static long contentLength(HttpResponse<?> response) {
        return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    }

    private static void ensureContinuing(BooleanSupplier shouldContinue) {
        if (!shouldContinue.getAsBoolean()) {
            throw new PlaybackCancelledException();
        }
    }

    private static boolean sentBytes(StreamResult result, StreamProgress progress) {
        return progress.bytesSent || (result != null && result.bytesSent());
    }

    private static String safeError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private record StreamResult(int nextSequence, boolean bytesSent) {
    }

    private static final class StreamProgress {
        private int nextSequence;
        private boolean bytesSent;
    }

    private record ByteRange(long start, long endInclusive, long total) {
        boolean hasKnownTotal() {
            return total >= 0;
        }

        boolean isComplete() {
            return hasKnownTotal() && endInclusive + 1 >= total;
        }
    }

    private static final class PlaybackCancelledException extends RuntimeException {
    }

    private static final class RelayPacer {
        private final long bytesPerSecond;
        private final long scheduledStartNanos;
        private final long offsetNanos;
        private final long prebufferBytes;
        private long bytesSent;

        private RelayPacer(long bytesPerSecond, Instant serverTime, Duration offset) {
            this.bytesPerSecond = bytesPerSecond;
            this.scheduledStartNanos = scheduledStartNanos(serverTime);
            this.offsetNanos = Math.max(0L, safeNanos(offset));
            this.prebufferBytes = Math.max(MIN_PREBUFFER_BYTES, multiplyRate(bytesPerSecond, STREAM_PREBUFFER.toNanos()));
        }

        private static RelayPacer unpaced() {
            return new RelayPacer(0L, Instant.EPOCH, Duration.ZERO);
        }

        private static RelayPacer forFiniteTrack(KaraokeTrack track, Duration offset, Instant serverTime, long totalBytes) {
            long bytesPerSecond = bytesPerSecond(track, totalBytes);
            return bytesPerSecond <= 0L ? unpaced() : new RelayPacer(bytesPerSecond, serverTime, offset);
        }

        private void afterBytesSent(int bytes, BooleanSupplier shouldContinue) {
            if (bytesPerSecond <= 0L || bytes <= 0) {
                return;
            }
            bytesSent += bytes;
            while (shouldContinue.getAsBoolean()) {
                long allowedBytes = allowedBytes(System.nanoTime());
                if (bytesSent <= allowedBytes) {
                    return;
                }
                long excessBytes = bytesSent - allowedBytes;
                long sleepNanos = Math.max(1L, (excessBytes * 1_000_000_000L) / bytesPerSecond);
                sleepQuietly(Math.min(Duration.ofMillis(MAX_PACE_SLEEP_MILLIS).toNanos(), sleepNanos));
            }
            throw new PlaybackCancelledException();
        }

        private long allowedBytes(long nowNanos) {
            long playbackNanos = Math.max(0L, nowNanos - scheduledStartNanos) + offsetNanos;
            return prebufferBytes + multiplyRate(bytesPerSecond, playbackNanos);
        }

        private static long bytesPerSecond(KaraokeTrack track, long totalBytes) {
            if (track.duration() != null && !track.duration().isZero() && !track.duration().isNegative() && totalBytes > 0L) {
                long durationMillis = Math.max(1L, track.duration().toMillis());
                return Math.max(1L, (totalBytes * 1000L + durationMillis - 1L) / durationMillis);
            }
            return UNKNOWN_DURATION_BYTES_PER_SECOND;
        }

        private static long scheduledStartNanos(Instant serverTime) {
            if (serverTime == null || serverTime.equals(Instant.EPOCH)) {
                return System.nanoTime();
            }
            long delayNanos = safeNanos(Duration.between(Instant.now(), serverTime));
            return System.nanoTime() + Math.max(0L, delayNanos);
        }

        private static long safeNanos(Duration duration) {
            if (duration == null) {
                return 0L;
            }
            try {
                return duration.toNanos();
            } catch (ArithmeticException ex) {
                return duration.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
        }

        private static long multiplyRate(long bytesPerSecond, long nanos) {
            if (bytesPerSecond <= 0L || nanos <= 0L) {
                return 0L;
            }
            double bytes = (bytesPerSecond * (double) nanos) / 1_000_000_000D;
            return bytes >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) bytes;
        }

        private static void sleepQuietly(long nanos) {
            try {
                long millis = nanos / 1_000_000L;
                int extraNanos = (int) (nanos % 1_000_000L);
                Thread.sleep(millis, extraNanos);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new PlaybackCancelledException();
            }
        }
    }

    private static final class IcyMetadataInputStream extends FilterInputStream {
        private final int metadataInterval;
        private int audioBytesUntilMetadata;

        private IcyMetadataInputStream(InputStream delegate, int metadataInterval) {
            super(delegate);
            this.metadataInterval = metadataInterval;
            this.audioBytesUntilMetadata = metadataInterval;
        }

        @Override
        public int read() throws java.io.IOException {
            skipMetadataIfNeeded();
            int value = super.read();
            if (value != -1) {
                audioBytesUntilMetadata--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            skipMetadataIfNeeded();
            int read = super.read(buffer, offset, Math.min(length, audioBytesUntilMetadata));
            if (read > 0) {
                audioBytesUntilMetadata -= read;
            }
            return read;
        }

        private void skipMetadataIfNeeded() throws java.io.IOException {
            if (audioBytesUntilMetadata != 0) {
                return;
            }
            int lengthByte = super.read();
            if (lengthByte != -1) {
                skipFully((lengthByte & 0xFF) * 16);
            }
            audioBytesUntilMetadata = metadataInterval;
        }

        private void skipFully(int bytes) throws java.io.IOException {
            int remaining = bytes;
            while (remaining > 0) {
                long skipped = super.skip(remaining);
                if (skipped > 0) {
                    remaining -= skipped;
                    continue;
                }
                if (super.read() == -1) {
                    return;
                }
                remaining--;
            }
        }
    }
}
