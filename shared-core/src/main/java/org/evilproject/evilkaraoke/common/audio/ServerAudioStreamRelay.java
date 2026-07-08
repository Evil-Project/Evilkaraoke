package org.evilproject.evilkaraoke.common.audio;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioInputStream;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.evilproject.evilkaraoke.common.security.AudioUrlValidator;

public final class ServerAudioStreamRelay implements AudioStreamRelay {
    public static final int CHUNK_BYTES = 16 * 1024;
    private static final int MAX_BUFFERED_ASSET_BYTES = 128 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STREAM_PREBUFFER = Duration.ofSeconds(8);
    private static final long MIN_PREBUFFER_BYTES = 256L * 1024L;
    private static final long MAX_PACE_SLEEP_MILLIS = 250L;
    private static final Duration READ_STALL_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    // Relays block a thread for the whole track (paced sends) and each finite
    // asset adds a download task, so they must not run on the ForkJoinPool
    // common pool: on 1-2 core hosts its parallelism is 1 and a second relay
    // (e.g. a rejoin sync) would queue behind the active song forever.
    private static final AtomicInteger RELAY_THREAD_ID = new AtomicInteger();
    private static final ExecutorService RELAY_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "evilkaraoke-relay-" + RELAY_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

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
        return CompletableFuture.runAsync(() -> relayBlocking(sessionId, playbackId, track, offset, serverTime, broadcaster, shouldContinue), RELAY_EXECUTOR);
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
                return relayDecodedBody(sessionId, playbackId, sequence, body, Duration.ZERO, false,
                        serverTime, broadcaster, shouldContinue);
            }
        }

        HttpResponse<InputStream> response = sendGet(asset.url(), null, 0);
        try (InputStream source = openFiniteAssetStream(asset.url(), response, shouldContinue)) {
            return relayDecodedBody(sessionId, playbackId, sequence, source, offset, true, serverTime, broadcaster, shouldContinue);
        }
    }

    private StreamResult relayDecodedBody(String sessionId,
                                          String playbackId,
                                          int sequence,
                                          InputStream body,
                                          Duration offset,
                                          boolean seekable,
                                          Instant serverTime,
                                          Consumer<ProtocolPacket> broadcaster,
                                          BooleanSupplier shouldContinue) throws Exception {
        try (AudioInputStream pcm = JavaSoundPcmDecoder.openPcmStream(body)) {
            javax.sound.sampled.AudioFormat format = pcm.getFormat();
            if (seekable) {
                seekPcm(pcm, format, offset, shouldContinue);
            }
            // Since decoding moved server-side we pace the decoded PCM by its own
            // byte-rate (known from the format), not the encoded size. Finite tracks
            // are paced so the client buffers only a small prebuffer; endless live
            // streams stay unpaced and are throttled by the upstream network instead.
            RelayPacer pacer = seekable ? RelayPacer.forPcm(format, offset, serverTime) : RelayPacer.unpaced();
            return relayPcmBody(sessionId, playbackId, sequence, pcm, format, pacer, broadcaster, shouldContinue);
        }
    }

    private StreamResult relayPcmBody(String sessionId,
                                      String playbackId,
                                      int sequence,
                                      InputStream body,
                                      javax.sound.sampled.AudioFormat format,
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
            broadcaster.accept(AudioStreamChunkPacket.chunk(sessionId, playbackId, nextSequence++, buffer, read,
                    format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits()));
            pacer.afterBytesSent(read, shouldContinue);
            bytesSent = true;
        }
        return new StreamResult(nextSequence, bytesSent);
    }

    /**
     * Opens a stream over a finite asset that decodes while the download is still
     * in flight. The initial response status and range are validated synchronously
     * so an early failure (bad status, bad range) still happens before any bytes
     * reach clients and the track's fallback asset can be tried. The body — plus
     * any follow-up Range requests the CDN forces by answering a plain GET with a
     * truncated 206 — then downloads at full network speed on a background task
     * while the paced relay consumes the bytes as they arrive.
     */
    private InputStream openFiniteAssetStream(String url, HttpResponse<InputStream> response, BooleanSupplier shouldContinue) throws Exception {
        ByteRange initialRange;
        try {
            if (response.statusCode() == 200) {
                initialRange = null;
            } else if (response.statusCode() == 206) {
                initialRange = byteRange(response);
                if (initialRange.start() != 0L) {
                    throw new IllegalStateException("Unexpected initial byte range " + initialRange.start());
                }
            } else {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for audio URL");
            }
        } catch (Exception ex) {
            response.body().close();
            throw ex;
        }
        AsyncTransferBuffer buffer = new AsyncTransferBuffer();
        ByteRange firstRange = initialRange;
        RELAY_EXECUTOR.execute(() -> downloadFiniteAsset(url, response, firstRange, buffer, shouldContinue));
        return buffer;
    }

    private void downloadFiniteAsset(String url,
                                     HttpResponse<InputStream> response,
                                     ByteRange initialRange,
                                     AsyncTransferBuffer buffer,
                                     BooleanSupplier shouldContinue) {
        try {
            long transferred;
            try (InputStream body = response.body()) {
                transferred = transferAll(body, buffer, shouldContinue);
            }
            if (initialRange != null) {
                validateChunkLength(transferred, initialRange);
                long total = initialRange.total();
                long nextStart = initialRange.endInclusive() + 1;
                while (initialRange.hasKnownTotal() && nextStart < total) {
                    ensureContinuing(shouldContinue);
                    HttpResponse<InputStream> nextResponse = sendGet(url, "bytes=" + nextStart + "-", 0);
                    ByteRange nextRange;
                    try (InputStream nextBody = nextResponse.body()) {
                        if (nextResponse.statusCode() != 206) {
                            throw new IllegalStateException("HTTP " + nextResponse.statusCode() + " while requesting audio bytes from " + nextStart);
                        }
                        nextRange = byteRange(nextResponse);
                        if (nextRange.start() != nextStart || !nextRange.hasKnownTotal() || nextRange.total() != total) {
                            throw new IllegalStateException("Inconsistent Content-Range for audio URL");
                        }
                        transferred = transferAll(nextBody, buffer, shouldContinue);
                    }
                    validateChunkLength(transferred, nextRange);
                    nextStart = nextRange.endInclusive() + 1;
                }
            }
            buffer.finish();
        } catch (Exception ex) {
            buffer.fail(ex);
        }
    }

    private static long transferAll(InputStream body, AsyncTransferBuffer buffer, BooleanSupplier shouldContinue) throws Exception {
        byte[] chunk = new byte[64 * 1024];
        long transferred = 0L;
        int read;
        while ((read = body.read(chunk)) != -1) {
            ensureContinuing(shouldContinue);
            buffer.append(chunk, read);
            transferred += read;
        }
        return transferred;
    }

    private static void seekPcm(AudioInputStream pcm, javax.sound.sampled.AudioFormat format, Duration offset, BooleanSupplier shouldContinue) {
        long offsetSeconds = offset == null ? 0L : offset.getSeconds();
        if (offsetSeconds <= 0L) {
            return;
        }
        long frameSize = format.getFrameSize();
        float frameRate = format.getFrameRate();
        if (frameSize <= 0 || frameRate <= 0 || Float.isInfinite(frameRate)) {
            return;
        }
        long bytesToSkip = (long) (frameRate * offsetSeconds) * frameSize;
        byte[] discard = new byte[8_192];
        try {
            long remaining = bytesToSkip;
            while (remaining > 0L) {
                ensureContinuing(shouldContinue);
                int read = pcm.read(discard, 0, (int) Math.min(discard.length, remaining));
                if (read == -1) {
                    return;
                }
                remaining -= read;
            }
        } catch (Exception ignored) {
            // If seek fails, stream from the current position.
        }
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

    private static void validateChunkLength(long actualLength, ByteRange range) {
        long expectedLength = range.endInclusive() - range.start() + 1L;
        if (actualLength != expectedLength) {
            throw new IllegalStateException("Expected " + expectedLength + " bytes for range "
                    + range.start() + "-" + range.endInclusive() + " but got " + actualLength);
        }
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

    /**
     * Hands bytes from the background download task to the decoding relay. Bytes
     * are buffered in memory (bounded by {@link #MAX_BUFFERED_ASSET_BYTES}, the
     * same cap as the fully-buffered download this replaces) so the origin
     * download runs at full network speed while the paced relay reads at PCM
     * rate. A read that sees no data for {@link #READ_STALL_TIMEOUT} fails the
     * stream so a stalled origin surfaces as an error packet instead of leaving
     * clients buffering forever.
     */
    private static final class AsyncTransferBuffer extends InputStream {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int chunkOffset;
        private long totalBuffered;
        private boolean finished;
        private boolean closed;
        private java.io.IOException failure;

        synchronized void append(byte[] data, int length) throws java.io.IOException {
            if (closed) {
                throw new java.io.IOException("Relay consumer closed the stream");
            }
            if (length <= 0) {
                return;
            }
            if (totalBuffered + length > MAX_BUFFERED_ASSET_BYTES) {
                java.io.IOException tooLarge = new java.io.IOException("Audio asset is too large to buffer safely");
                failure = tooLarge;
                finished = true;
                notifyAll();
                throw tooLarge;
            }
            chunks.add(Arrays.copyOfRange(data, 0, length));
            totalBuffered += length;
            notifyAll();
        }

        synchronized void finish() {
            finished = true;
            notifyAll();
        }

        synchronized void fail(Throwable error) {
            if (failure == null) {
                failure = error instanceof java.io.IOException io ? io : new java.io.IOException(safeError(error), error);
            }
            finished = true;
            notifyAll();
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read == -1 ? -1 : single[0] & 0xFF;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            long deadlineNanos = System.nanoTime() + READ_STALL_TIMEOUT.toNanos();
            while (chunks.isEmpty() && !finished && !closed) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    fail(new java.io.IOException("Timed out waiting for audio data from the origin"));
                    break;
                }
                try {
                    wait(Math.max(1L, remainingNanos / 1_000_000L));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Interrupted while waiting for audio data", ex);
                }
            }
            if (closed) {
                throw new java.io.IOException("Stream closed");
            }
            byte[] chunk = chunks.peek();
            if (chunk == null) {
                if (failure != null) {
                    throw failure;
                }
                return -1;
            }
            int copied = Math.min(length, chunk.length - chunkOffset);
            System.arraycopy(chunk, chunkOffset, buffer, offset, copied);
            chunkOffset += copied;
            if (chunkOffset >= chunk.length) {
                chunks.remove();
                chunkOffset = 0;
            }
            return copied;
        }

        @Override
        public synchronized void close() {
            closed = true;
            chunks.clear();
            chunkOffset = 0;
            notifyAll();
        }
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

        private static RelayPacer forPcm(javax.sound.sampled.AudioFormat format, Duration offset, Instant serverTime) {
            long bytesPerSecond = pcmBytesPerSecond(format);
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

        private static long pcmBytesPerSecond(javax.sound.sampled.AudioFormat format) {
            float frameRate = format.getFrameRate();
            int frameSize = format.getFrameSize();
            if (frameSize <= 0 || frameRate <= 0.0f || Float.isNaN(frameRate) || Float.isInfinite(frameRate)) {
                return 0L;
            }
            return Math.max(1L, (long) Math.ceil((double) frameRate * frameSize));
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
