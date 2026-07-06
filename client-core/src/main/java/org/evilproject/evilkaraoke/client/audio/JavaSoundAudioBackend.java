package org.evilproject.evilkaraoke.client.audio;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.security.AudioUrlValidator;

/**
 * Loader-neutral audio backend. Decodes either a legacy URL asset or a
 * server-relayed packet stream through {@link AudioSystem} (using whatever decode
 * SPIs are bundled) into signed-16 PCM at the source's native sample
 * rate/channels and plays it on a {@link SourceDataLine}. All queue,
 * search, and control logic lives on the server; this class only turns an
 * {@link AudioCommandPacket} into local sound, mirroring how a client reacts to
 * {@code /playsound}.
 */
public final class JavaSoundAudioBackend implements AudioBackend {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");
    private static final int MAX_BUFFERED_ASSET_BYTES = 128 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int AUDIO_SIGNATURE_BYTES = 512;
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final UnaryOperator<URI> uriValidator;
    private final LineFactory lineFactory;
    private final AtomicReference<PlaybackHandle> current = new AtomicReference<>();
    private volatile AudioBackendStatus status = AudioBackendStatus.ready();
    private volatile float serverGain = 1.0f;
    private volatile float gameGain = 1.0f;

    public JavaSoundAudioBackend() {
        this(AudioUrlValidator::validatePublicHttpUrl);
    }

    JavaSoundAudioBackend(UnaryOperator<URI> uriValidator) {
        this(uriValidator, JavaSoundAudioBackend::systemLine);
    }

    JavaSoundAudioBackend(UnaryOperator<URI> uriValidator, LineFactory lineFactory) {
        this.uriValidator = Objects.requireNonNull(uriValidator, "uriValidator");
        this.lineFactory = Objects.requireNonNull(lineFactory, "lineFactory");
    }

    @Override
    public void play(AudioCommandPacket packet) {
        stopCurrent();
        KaraokeTrack track = packet.track();
        if (track == null) {
            status = AudioBackendStatus.error("PLAY packet had no track");
            return;
        }
        serverGain = packet.target() == null ? 1.0f : clamp01(packet.target().volume());
        Duration seekOffset = packet.offset() != null ? packet.offset() : Duration.ZERO;
        PlaybackHandle handle = new PlaybackHandle(packet.playbackId(), seekOffset, packet.serverTime());
        current.set(handle);
        Thread thread = new Thread(() -> runPlayback(handle, track), "evilkaraoke-audio-" + packet.playbackId());
        thread.setDaemon(true);
        handle.thread = thread;
        status = new AudioBackendStatus(ClientPlaybackState.BUFFERING, "Buffering " + track.title());
        thread.start();
    }

    @Override
    public void playStream(AudioCommandPacket packet, InputStream source) {
        stopCurrent();
        KaraokeTrack track = packet.track();
        if (track == null) {
            status = AudioBackendStatus.error("PLAY packet had no track");
            closeQuietly(source);
            return;
        }
        if (source == null) {
            status = AudioBackendStatus.error("PLAY packet had no server audio stream");
            return;
        }
        serverGain = packet.target() == null ? 1.0f : clamp01(packet.target().volume());
        Duration seekOffset = packet.offset() != null ? packet.offset() : Duration.ZERO;
        PlaybackHandle handle = new PlaybackHandle(packet.playbackId(), seekOffset, packet.serverTime());
        current.set(handle);
        Thread thread = new Thread(() -> runPlayback(handle, track, source), "evilkaraoke-audio-" + packet.playbackId());
        thread.setDaemon(true);
        handle.thread = thread;
        status = new AudioBackendStatus(ClientPlaybackState.BUFFERING, "Buffering " + track.title());
        thread.start();
    }

    @Override
    public void pause(AudioCommandPacket packet) {
        PlaybackHandle handle = current.get();
        if (handle != null && !handle.stopped) {
            handle.paused = true;
            SourceDataLine line = handle.line;
            if (line != null) {
                line.stop();
            }
            status = new AudioBackendStatus(ClientPlaybackState.PAUSED, "Paused");
        }
    }

    @Override
    public void resume(AudioCommandPacket packet) {
        PlaybackHandle handle = current.get();
        if (handle != null && !handle.stopped) {
            handle.paused = false;
            SourceDataLine line = handle.line;
            if (line != null && handle.playbackReleased) {
                line.start();
            }
            status = handle.playbackReleased
                    ? new AudioBackendStatus(ClientPlaybackState.PLAYING, "Playing")
                    : new AudioBackendStatus(ClientPlaybackState.BUFFERING, "Buffering");
        }
    }

    @Override
    public void stop(AudioCommandPacket packet) {
        stopCurrent();
        status = new AudioBackendStatus(ClientPlaybackState.STOPPED, "Stopped");
    }

    @Override
    public AudioBackendStatus status() {
        return status;
    }

    /** Applies a new linear gain (0..1), e.g. from a VOLUME packet or listener move. */
    @Override
    public void setVolume(float newGain) {
        this.serverGain = clamp01(newGain);
    }

    @Override
    public void setGameVolume(float newGain) {
        this.gameGain = clamp01(newGain);
    }

    private void runPlayback(PlaybackHandle handle, KaraokeTrack track) {
        try {
            Exception primaryError = tryPlayAsset(handle, track.primaryAsset());
            if (primaryError == null || handle.stopped) {
                return;
            }
            AudioAsset fallback = track.fallbackAsset();
            if (fallback != null) {
                Exception fallbackError = tryPlayAsset(handle, fallback);
                if (fallbackError == null || handle.stopped) {
                    return;
                }
                failPlayback(handle, track, fallbackError);
                return;
            }
            failPlayback(handle, track, primaryError);
        } finally {
            current.compareAndSet(handle, null);
        }
    }

    private void runPlayback(PlaybackHandle handle, KaraokeTrack track, InputStream source) {
        try {
            Exception error = tryPlayStream(handle, track, source);
            if (error != null && !handle.stopped) {
                failPlayback(handle, track, error);
            }
        } finally {
            current.compareAndSet(handle, null);
        }
    }

    private void failPlayback(PlaybackHandle handle, KaraokeTrack track, Exception error) {
        String message = "Could not decode " + track.title() + ": " + error.getMessage();
        handle.stopped = true;
        current.compareAndSet(handle, null);
        status = AudioBackendStatus.error(message);
        LOGGER.log(Level.WARNING, "Evilkaraoke client playback failed for " + track.title(), error);
    }

    private Exception tryPlayAsset(PlaybackHandle handle, AudioAsset asset) {
        if (asset == null) {
            return new IllegalStateException("no asset");
        }
        // AudioSystem discovers fallback SPIs through the thread context classloader.
        // On Fabric/NeoForge the network thread's context loader may not see the
        // bundled decoders, so pin it to this mod's loader while providers resolve.
        Thread worker = Thread.currentThread();
        ClassLoader previousLoader = worker.getContextClassLoader();
        worker.setContextClassLoader(JavaSoundAudioBackend.class.getClassLoader());
        try (InputStream source = openDecodableStream(handle, asset);
             AudioInputStream encoded = openEncodedAudioInputStream(source);
             AudioInputStream pcm = openPcmAudioInputStream(encoded)) {
            SourceDataLine line = openLine(pcm.getFormat());
            handle.line = line;
            try {
                if (asset.format() != org.evilproject.evilkaraoke.common.model.AudioFormat.STREAM) {
                    seekPcm(handle, pcm, pcm.getFormat());
                }
                if (handle.stopped) {
                    return null;
                }
                waitForScheduledStart(handle);
                if (handle.stopped) {
                    return null;
                }
                handle.playbackReleased = true;
                if (!handle.paused) {
                    line.start();
                }
                // A PAUSE can arrive while we were still buffering/seeking (before the
                // line existed). Reflect that in the status so the server does not see
                // PLAYING for a track that is actually sitting paused and silent.
                status = handle.paused
                        ? new AudioBackendStatus(ClientPlaybackState.PAUSED, "Paused")
                        : new AudioBackendStatus(ClientPlaybackState.PLAYING, "Playing");
                pump(handle, pcm, line);
                if (!handle.stopped) {
                    line.drain();
                    status = new AudioBackendStatus(ClientPlaybackState.STOPPED, "Finished");
                }
                return null;
            } catch (Exception ex) {
                return ex;
            } finally {
                // Always release the line so the pool isn't exhausted by rapid skips.
                // stopCurrent() may have already closed it — SourceDataLine.close() is
                // idempotent, so calling it twice is safe.
                handle.line = null;
                line.stop();
                line.close();
            }
        } catch (Exception ex) {
            return ex;
        } finally {
            worker.setContextClassLoader(previousLoader);
        }
    }

    private Exception tryPlayStream(PlaybackHandle handle, KaraokeTrack track, InputStream source) {
        Thread worker = Thread.currentThread();
        ClassLoader previousLoader = worker.getContextClassLoader();
        worker.setContextClassLoader(JavaSoundAudioBackend.class.getClassLoader());
        try (InputStream serverSource = trackedBody(handle, source);
             AudioInputStream encoded = openEncodedAudioInputStream(serverSource);
             AudioInputStream pcm = openPcmAudioInputStream(encoded)) {
            SourceDataLine line = openLine(pcm.getFormat());
            handle.line = line;
            try {
                if (track.primaryAsset() == null || track.primaryAsset().format() != org.evilproject.evilkaraoke.common.model.AudioFormat.STREAM) {
                    seekPcm(handle, pcm, pcm.getFormat());
                }
                if (handle.stopped) {
                    return null;
                }
                waitForScheduledStart(handle);
                if (handle.stopped) {
                    return null;
                }
                handle.playbackReleased = true;
                if (!handle.paused) {
                    line.start();
                }
                status = handle.paused
                        ? new AudioBackendStatus(ClientPlaybackState.PAUSED, "Paused")
                        : new AudioBackendStatus(ClientPlaybackState.PLAYING, "Playing");
                pump(handle, pcm, line);
                if (!handle.stopped) {
                    line.drain();
                    status = new AudioBackendStatus(ClientPlaybackState.STOPPED, "Finished");
                }
                return null;
            } catch (Exception ex) {
                return ex;
            } finally {
                handle.line = null;
                line.stop();
                line.close();
            }
        } catch (Exception ex) {
            return ex;
        } finally {
            worker.setContextClassLoader(previousLoader);
        }
    }

    static AudioInputStream openEncodedAudioInputStream(InputStream source) throws Exception {
        InputStream markable = source.markSupported() ? source : new BufferedInputStream(source, 1 << 16);
        AudioProbe probe = probeAudioKind(markable);
        if (probe.kind() != EncodedAudioKind.UNKNOWN) {
            if (probe.kind() == EncodedAudioKind.MP3 && probe.skipBytes() > 0) {
                skipFully(markable, probe.skipBytes());
            }
            return readerFor(probe.kind()).getAudioInputStream(markable);
        }
        return AudioSystem.getAudioInputStream(markable);
    }

    private static AudioInputStream openPcmAudioInputStream(AudioInputStream encoded) {
        AudioFormat target = pcmFormatFor(encoded.getFormat());
        for (FormatConversionProvider provider : conversionProviders()) {
            if (provider.isConversionSupported(target, encoded.getFormat())) {
                return provider.getAudioInputStream(target, encoded);
            }
        }
        return AudioSystem.getAudioInputStream(target, encoded);
    }

    private static void waitForScheduledStart(PlaybackHandle handle) throws InterruptedException {
        Instant scheduledStart = handle.scheduledStart;
        if (scheduledStart == null || scheduledStart.equals(Instant.EPOCH)) {
            return;
        }
        synchronized (handle) {
            while (!handle.stopped) {
                long waitMillis = Duration.between(Instant.now(), scheduledStart).toMillis();
                if (waitMillis <= 0L) {
                    return;
                }
                handle.wait(Math.min(waitMillis, 50L));
            }
        }
    }

    static EncodedAudioKind detectAudioKind(InputStream source) throws java.io.IOException {
        return probeAudioKind(source).kind();
    }

    private static AudioProbe probeAudioKind(InputStream source) throws java.io.IOException {
        source.mark(AUDIO_SIGNATURE_BYTES);
        byte[] header = source.readNBytes(AUDIO_SIGNATURE_BYTES);
        source.reset();
        if (startsWith(header, 'O', 'g', 'g', 'S')) {
            if (contains(header, "OpusHead")) {
                return new AudioProbe(EncodedAudioKind.OPUS, 0);
            }
            if (contains(header, "\u0001vorbis")) {
                return new AudioProbe(EncodedAudioKind.VORBIS, 0);
            }
        }
        if (startsWith(header, 'I', 'D', '3')) {
            return new AudioProbe(EncodedAudioKind.MP3, 0);
        }
        int frameOffset = mpegFrameOffset(header);
        if (frameOffset >= 0) {
            return new AudioProbe(EncodedAudioKind.MP3, frameOffset);
        }
        return new AudioProbe(EncodedAudioKind.UNKNOWN, 0);
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int mpegFrameOffset(byte[] bytes) {
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (looksLikeMpegFrame(bytes, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean looksLikeMpegFrame(byte[] bytes, int offset) {
        if (offset + 3 >= bytes.length) {
            return false;
        }
        int b0 = bytes[offset] & 0xFF;
        int b1 = bytes[offset + 1] & 0xFF;
        int b2 = bytes[offset + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
            return false;
        }
        int version = (b1 >>> 3) & 0x03;
        int layer = (b1 >>> 1) & 0x03;
        int bitrate = (b2 >>> 4) & 0x0F;
        int sampleRate = (b2 >>> 2) & 0x03;
        return version != 0x01
                && layer != 0x00
                && bitrate != 0x00
                && bitrate != 0x0F
                && sampleRate != 0x03;
    }

    private static boolean contains(byte[] bytes, String needle) {
        byte[] target = needle.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i <= bytes.length - target.length; i++) {
            boolean matched = true;
            for (int j = 0; j < target.length; j++) {
                if (bytes[i + j] != target[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private static AudioFileReader readerFor(EncodedAudioKind kind) {
        return switch (kind) {
            case OPUS -> new io.github.jseproject.OpusAudioFileReader();
            case VORBIS -> new io.github.jseproject.VorbisAudioFileReader();
            case MP3 -> new javazoom.spi.mpeg.sampled.file.MpegAudioFileReader();
            case UNKNOWN -> throw new IllegalArgumentException("Unknown encoded audio kind");
        };
    }

    private static FormatConversionProvider[] conversionProviders() {
        return new FormatConversionProvider[] {
                new io.github.jseproject.OpusFormatConversionProvider(),
                new io.github.jseproject.VorbisFormatConversionProvider(),
                new javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider()
        };
    }

    /**
     * Derives a signed-16 PCM target that keeps the source sample rate and channel
     * count. Forcing a fixed 48 kHz stereo target makes the bundled SPI decoders
     * refuse the conversion (none of them both decode and resample or remix), which
     * left 44.1 kHz or mono assets producing no sound.
     */
    private static AudioFormat pcmFormatFor(AudioFormat source) {
        float sampleRate = source.getSampleRate() > 0 ? source.getSampleRate() : PcmFormat.SAMPLE_RATE;
        int channels = source.getChannels() > 0 ? source.getChannels() : PcmFormat.CHANNELS;
        int frameSize = channels * (PcmFormat.BITS_PER_SAMPLE / 8);
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                PcmFormat.BITS_PER_SAMPLE,
                channels,
                frameSize,
                sampleRate,
                false);
    }

    /**
     * Skips forward in the PCM stream by the handle's seek offset. Never called
     * for {@link org.evilproject.evilkaraoke.common.model.AudioFormat#STREAM} assets
     * (live radio) — the caller guards that before invoking this method, since a live
     * stream cannot be reliably seeked and consuming its bytes produces silence.
     * A skip failure is silently ignored — the track just plays from the start.
     */
    private void seekPcm(PlaybackHandle handle, AudioInputStream pcm, AudioFormat format) {
        long offsetSeconds = handle.seekOffset.getSeconds();
        if (offsetSeconds <= 0) {
            return;
        }
        long frameSize = format.getFrameSize();
        float frameRate = format.getFrameRate();
        if (frameSize <= 0 || frameRate <= 0 || Float.isInfinite(frameRate)) {
            return;
        }
        long framesToSkip = (long) (frameRate * offsetSeconds);
        long bytesToSkip = framesToSkip * frameSize;
        try {
            long remaining = bytesToSkip;
            byte[] discard = new byte[8_192];
            while (remaining > 0 && !handle.stopped) {
                int n = pcm.read(discard, 0, (int) Math.min(discard.length, remaining));
                if (n == -1) {
                    break;
                }
                remaining -= n;
            }
        } catch (Exception ignored) {
            // If seek fails, play from the beginning — not ideal but not fatal.
        }
    }

    private void pump(PlaybackHandle handle, AudioInputStream pcm, SourceDataLine line) throws InterruptedException, java.io.IOException {
        byte[] buffer = new byte[8_192];
        while (!handle.stopped) {
            int read = pcm.read(buffer);
            if (read == -1) {
                break;
            }
            while (handle.paused && !handle.stopped) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    // Restore and let the outer stopped-check handle it.
                    break;
                }
            }
            if (handle.stopped) {
                return;
            }
            applySoftwareGain(buffer, read, effectiveGain());
            int offset = 0;
            while (offset < read && !handle.stopped) {
                int written = line.write(buffer, offset, read - offset);
                if (written == 0) {
                    // Line buffer is full — yield briefly to avoid a busy-spin that
                    // starves the audio thread's own output scheduler.
                    Thread.sleep(1);
                }
                offset += written;
            }
        }
    }

    /**
     * Provides a stream the SPI decoders can safely rewind. {@link AudioSystem}
     * probes readers with mark/reset while detecting the format; doing that over a
     * raw non-seekable HTTP body loses bytes and the Ogg/Opus parser then dies with
     * "data ended mid-page ... hit EoF", producing silence. Finite song assets are
     * therefore fully buffered into memory (perfect mark/reset). Neurokaraoke's
     * CDN may answer a normal finite GET with 206 Partial Content, so finite assets
     * also follow Content-Range until the whole file is assembled. Endless radio
     * streams keep streaming with a large rewind window for header detection.
     */
    InputStream openDecodableStream(AudioAsset asset) throws Exception {
        return openDecodableStream(null, asset);
    }

    private InputStream openDecodableStream(PlaybackHandle handle, AudioAsset asset) throws Exception {
        ensureNotStopped(handle);
        if (asset.format() == org.evilproject.evilkaraoke.common.model.AudioFormat.STREAM) {
            return new BufferedInputStream(openStream(handle, asset.url()), 1 << 16);
        }
        return new ByteArrayInputStream(downloadFiniteAsset(handle, asset.url()));
    }

    private InputStream openStream(PlaybackHandle handle, String url) throws Exception {
        HttpResponse<InputStream> response = sendGet(handle, url, null);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("HTTP " + response.statusCode() + " for audio URL");
        }
        return trackedBody(handle, stripIcyMetadata(response));
    }

    private byte[] downloadFiniteAsset(PlaybackHandle handle, String url) throws Exception {
        HttpResponse<InputStream> response = sendGet(handle, url, null);
        try (InputStream body = trackedBody(handle, response)) {
            if (response.statusCode() == 200) {
                return readBounded(handle, body);
            }
            if (response.statusCode() != 206) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for audio URL");
            }
            ByteRange range = byteRange(response);
            if (range.start() != 0) {
                throw new IllegalStateException("Unexpected initial byte range " + range.start());
            }
            byte[] firstChunk = readBounded(handle, body);
            validateChunkLength(firstChunk.length, range);
            if (!range.hasKnownTotal() || range.isComplete()) {
                return firstChunk;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(range.total(), MAX_BUFFERED_ASSET_BYTES));
            out.write(firstChunk);
            long nextStart = range.endInclusive() + 1;
            int total = range.total();
            while (nextStart < total) {
                ensureBufferedSize(nextStart, "audio asset");
                ByteRange nextRange;
                byte[] chunk;
                HttpResponse<InputStream> nextResponse = sendGet(handle, url, "bytes=" + nextStart + "-");
                try (InputStream nextBody = trackedBody(handle, nextResponse)) {
                    if (nextResponse.statusCode() != 206) {
                        throw new IllegalStateException("HTTP " + nextResponse.statusCode() + " while requesting audio bytes from " + nextStart);
                    }
                    nextRange = byteRange(nextResponse);
                    if (nextRange.start() != nextStart) {
                        throw new IllegalStateException("Unexpected byte range " + nextRange.start() + "; expected " + nextStart);
                    }
                    if (!nextRange.hasKnownTotal() || nextRange.total() != total) {
                        throw new IllegalStateException("Inconsistent Content-Range total for audio URL");
                    }
                    chunk = readBounded(handle, nextBody);
                }
                validateChunkLength(chunk.length, nextRange);
                out.write(chunk);
                nextStart = nextRange.endInclusive() + 1;
            }
            return out.toByteArray();
        }
    }

    private HttpResponse<InputStream> sendGet(PlaybackHandle handle, String url, String range) throws Exception {
        return sendGet(handle, validatedUri(url), range, 0);
    }

    private HttpResponse<InputStream> sendGet(PlaybackHandle handle, URI uri, String range, int redirects) throws Exception {
        ensureNotStopped(handle);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(HTTP_REQUEST_TIMEOUT)
                .header("User-Agent", "Evilkaraoke-Client")
                .header("Icy-MetaData", "0")
                .GET();
        if (range != null) {
            builder.header("Range", range);
        }
        HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try {
            ensureNotStopped(handle);
        } catch (RuntimeException ex) {
            response.body().close();
            throw ex;
        }
        if (isRedirect(response.statusCode())) {
            response.body().close();
            if (redirects >= MAX_REDIRECTS) {
                throw new IllegalStateException("Too many redirects while requesting audio URL");
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Redirect response did not include Location"));
            URI redirectUri = uriValidator.apply(uri.resolve(location));
            return sendGet(handle, redirectUri, range, redirects + 1);
        }
        return response;
    }

    private URI validatedUri(String url) {
        try {
            return uriValidator.apply(audioUri(url));
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage();
            if (message != null && message.startsWith("Audio URL")) {
                throw ex;
            }
            throw new IllegalArgumentException("Audio URL is not valid");
        }
    }

    private static URI audioUri(String url) {
        if (url == null) {
            throw new IllegalArgumentException("Audio URL is required");
        }
        return URI.create(url.replace(" ", "%20"));
    }

    private static InputStream trackedBody(PlaybackHandle handle, HttpResponse<InputStream> response) throws java.io.IOException {
        return trackedBody(handle, response.body());
    }

    private static InputStream trackedBody(PlaybackHandle handle, InputStream body) throws java.io.IOException {
        if (handle == null) {
            return body;
        }
        TrackedInputStream tracked = new TrackedInputStream(handle, body);
        activateStream(handle, tracked);
        return tracked;
    }

    private static InputStream stripIcyMetadata(HttpResponse<InputStream> response) {
        int metadataInterval = icyMetadataInterval(response);
        if (metadataInterval <= 0) {
            return response.body();
        }
        return new IcyMetadataInputStream(response.body(), metadataInterval);
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

    private byte[] readBounded(PlaybackHandle handle, InputStream body) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = body.read(buffer)) != -1) {
            ensureNotStopped(handle);
            ensureBufferedSize((long) out.size() + read, "audio asset");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void ensureNotStopped(PlaybackHandle handle) {
        if (handle != null && handle.stopped) {
            throw new IllegalStateException("playback stopped");
        }
    }

    private static void activateStream(PlaybackHandle handle, InputStream stream) throws java.io.IOException {
        if (handle.stopped) {
            stream.close();
            throw new IllegalStateException("playback stopped");
        }
        handle.activeStream = stream;
        if (handle.stopped) {
            clearActiveStream(handle, stream);
            stream.close();
            throw new IllegalStateException("playback stopped");
        }
    }

    private static void clearActiveStream(PlaybackHandle handle, InputStream stream) {
        if (handle.activeStream == stream) {
            handle.activeStream = null;
        }
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
        int total = matcher.group(3).equals("*") ? -1 : Math.toIntExact(Long.parseLong(matcher.group(3)));
        if (endInclusive < start || total > MAX_BUFFERED_ASSET_BYTES) {
            throw new IllegalStateException("Unsupported Content-Range for audio URL");
        }
        return new ByteRange(start, endInclusive, total);
    }

    private static void validateChunkLength(int actualLength, ByteRange range) {
        long expectedLength = range.endInclusive() - range.start() + 1;
        if (actualLength != expectedLength) {
            throw new IllegalStateException("Expected " + expectedLength + " bytes for range " + range.start() + "-" + range.endInclusive() + " but got " + actualLength);
        }
    }

    private static void ensureBufferedSize(long size, String context) {
        if (size > MAX_BUFFERED_ASSET_BYTES) {
            throw new IllegalStateException("Audio asset is too large to buffer safely: " + context);
        }
    }

    private static void skipFully(InputStream stream, int bytes) throws java.io.IOException {
        int remaining = bytes;
        while (remaining > 0) {
            long skipped = stream.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (stream.read() == -1) {
                throw new java.io.EOFException("Unexpected end of audio stream while seeking MPEG frame");
            }
            remaining--;
        }
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static void closeQuietly(InputStream source) {
        if (source == null) {
            return;
        }
        try {
            source.close();
        } catch (java.io.IOException ignored) {
            // Nothing useful to report; playback is not starting.
        }
    }

    private record AudioProbe(EncodedAudioKind kind, int skipBytes) {
    }

    private record ByteRange(long start, long endInclusive, int total) {
        boolean hasKnownTotal() {
            return total >= 0;
        }

        boolean isComplete() {
            return hasKnownTotal() && endInclusive + 1 >= total;
        }
    }

    private SourceDataLine openLine(AudioFormat format) throws Exception {
        return lineFactory.open(format);
    }

    private static SourceDataLine systemLine(AudioFormat format) throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        return line;
    }

    private void stopCurrent() {
        PlaybackHandle handle = current.getAndSet(null);
        if (handle != null) {
            handle.stopped = true;
            SourceDataLine line = handle.line;
            if (line != null) {
                line.stop();
                line.close();
            }
            InputStream activeStream = handle.activeStream;
            if (activeStream != null) {
                try {
                    activeStream.close();
                } catch (java.io.IOException ignored) {
                    // Best effort: stopping playback must not fail because a remote
                    // server already closed or reset its response body.
                }
            }
            if (handle.thread != null) {
                handle.thread.interrupt();
            }
            synchronized (handle) {
                handle.notifyAll();
            }
        }
    }

    private float effectiveGain() {
        return clamp01(serverGain * gameGain);
    }

    static void applySoftwareGain(byte[] buffer, int length, float linearGain) {
        float clamped = clamp01(linearGain);
        if (clamped >= 0.9999f) {
            return;
        }
        if (clamped <= 0.0001f) {
            Arrays.fill(buffer, 0, length, (byte) 0);
            return;
        }
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
            int scaled = Math.round(sample * clamped);
            buffer[i] = (byte) (scaled & 0xFF);
            buffer[i + 1] = (byte) ((scaled >>> 8) & 0xFF);
        }
    }

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        return Math.min(value, 1.0f);
    }

    private static final class TrackedInputStream extends FilterInputStream {
        private final PlaybackHandle handle;
        private boolean closed;

        private TrackedInputStream(PlaybackHandle handle, InputStream delegate) {
            super(delegate);
            this.handle = handle;
        }

        @Override
        public void close() throws java.io.IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                super.close();
            } finally {
                clearActiveStream(handle, this);
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

    private static final class PlaybackHandle {
        private final String playbackId;
        private final Duration seekOffset;
        private final Instant scheduledStart;
        private volatile Thread thread;
        private volatile SourceDataLine line;
        private volatile InputStream activeStream;
        private volatile boolean playbackReleased;
        private volatile boolean paused;
        private volatile boolean stopped;

        private PlaybackHandle(String playbackId, Duration seekOffset, Instant scheduledStart) {
            this.playbackId = playbackId;
            this.seekOffset = seekOffset != null ? seekOffset : Duration.ZERO;
            this.scheduledStart = scheduledStart == null ? Instant.EPOCH : scheduledStart;
        }
    }

    enum EncodedAudioKind {
        OPUS,
        VORBIS,
        MP3,
        UNKNOWN
    }

    @FunctionalInterface
    interface LineFactory {
        SourceDataLine open(AudioFormat format) throws Exception;
    }
}
