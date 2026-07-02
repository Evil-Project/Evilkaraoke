package org.evilproject.evilkaraoke.client.audio;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;

/**
 * Loader-neutral audio backend. Decodes the server-provided asset URL through
 * {@link AudioSystem} (using whatever decode SPIs are bundled) into signed-16 PCM
 * at the source's native sample rate/channels and plays it on a
 * {@link SourceDataLine}. All queue,
 * search, and control logic lives on the server; this class only turns an
 * {@link AudioCommandPacket} into local sound, mirroring how a client reacts to
 * {@code /playsound}.
 */
public final class JavaSoundAudioBackend implements AudioBackend {
    private static final int MAX_BUFFERED_ASSET_BYTES = 128 * 1024 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AtomicReference<PlaybackHandle> current = new AtomicReference<>();
    private volatile AudioBackendStatus status = AudioBackendStatus.ready();
    private volatile float gain = 1.0f;

    @Override
    public void play(AudioCommandPacket packet) {
        stopCurrent();
        KaraokeTrack track = packet.track();
        if (track == null) {
            status = AudioBackendStatus.error("PLAY packet had no track");
            return;
        }
        gain = packet.target() == null ? 1.0f : clamp01(packet.target().volume());
        Duration seekOffset = packet.offset() != null ? packet.offset() : Duration.ZERO;
        PlaybackHandle handle = new PlaybackHandle(packet.playbackId(), seekOffset);
        current.set(handle);
        Thread thread = new Thread(() -> runPlayback(handle, track), "evilkaraoke-audio-" + packet.playbackId());
        thread.setDaemon(true);
        handle.thread = thread;
        status = new AudioBackendStatus(ClientPlaybackState.BUFFERING, "Buffering " + track.title());
        thread.start();
    }

    @Override
    public void pause(AudioCommandPacket packet) {
        PlaybackHandle handle = current.get();
        if (handle != null) {
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
        if (handle != null) {
            handle.paused = false;
            SourceDataLine line = handle.line;
            if (line != null) {
                line.start();
            }
            status = new AudioBackendStatus(ClientPlaybackState.PLAYING, "Playing");
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
        this.gain = clamp01(newGain);
        PlaybackHandle handle = current.get();
        if (handle != null) {
            applyGain(handle.line, this.gain);
        }
    }

    private void runPlayback(PlaybackHandle handle, KaraokeTrack track) {
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
            status = AudioBackendStatus.error("Could not decode " + track.title() + ": " + fallbackError.getMessage());
            return;
        }
        status = AudioBackendStatus.error("Could not decode " + track.title() + ": " + primaryError.getMessage());
    }

    private Exception tryPlayAsset(PlaybackHandle handle, AudioAsset asset) {
        if (asset == null) {
            return new IllegalStateException("no asset");
        }
        // AudioSystem discovers decode/convert SPIs through the thread context
        // classloader. On Fabric/NeoForge the network thread's context loader does
        // not see the bundled decoders, so pin it to this mod's loader while we
        // resolve providers, otherwise no provider is found and playback is silent.
        Thread worker = Thread.currentThread();
        ClassLoader previousLoader = worker.getContextClassLoader();
        worker.setContextClassLoader(JavaSoundAudioBackend.class.getClassLoader());
        try (InputStream source = openDecodableStream(asset);
             AudioInputStream encoded = AudioSystem.getAudioInputStream(source);
             AudioInputStream pcm = AudioSystem.getAudioInputStream(pcmFormatFor(encoded.getFormat()), encoded)) {
            SourceDataLine line = openLine(pcm.getFormat());
            handle.line = line;
            try {
                applyGain(line, gain);
                line.start();
                if (asset.format() != org.evilproject.evilkaraoke.common.model.AudioFormat.STREAM) {
                    seekPcm(handle, pcm, pcm.getFormat());
                }
                if (handle.stopped) {
                    return null;
                }
                status = new AudioBackendStatus(ClientPlaybackState.PLAYING, "Playing");
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

    private void pump(PlaybackHandle handle, AudioInputStream pcm, SourceDataLine line) throws InterruptedException {
        byte[] buffer = new byte[8_192];
        while (!handle.stopped) {
            int read;
            try {
                read = pcm.read(buffer);
            } catch (java.io.IOException ex) {
                // Mid-stream decoder/network error — treat as end-of-stream so the
                // track finishes cleanly rather than stopping with an error status.
                break;
            }
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

    private void drainAndClose(PlaybackHandle handle, SourceDataLine line) {
        try {
            if (!handle.stopped) {
                line.drain();
            }
        } finally {
            line.stop();
            line.close();
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
        if (asset.format() == org.evilproject.evilkaraoke.common.model.AudioFormat.STREAM) {
            return new BufferedInputStream(openStream(asset.url()), 1 << 16);
        }
        return new ByteArrayInputStream(downloadFiniteAsset(asset.url()));
    }

    private InputStream openStream(String url) throws Exception {
        HttpResponse<InputStream> response = sendGet(url, null);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    private byte[] downloadFiniteAsset(String url) throws Exception {
        HttpResponse<InputStream> response = sendGet(url, null);
        try (InputStream body = response.body()) {
            if (response.statusCode() == 200) {
                return readBounded(body);
            }
            if (response.statusCode() != 206) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
            }
            ByteRange range = byteRange(response, url);
            if (range.start() != 0) {
                throw new IllegalStateException("Unexpected initial byte range " + range.start() + " for " + url);
            }
            byte[] firstChunk = readBounded(body);
            validateChunkLength(firstChunk.length, range, url);
            if (!range.hasKnownTotal() || range.isComplete()) {
                return firstChunk;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(range.total(), MAX_BUFFERED_ASSET_BYTES));
            out.write(firstChunk);
            long nextStart = range.endInclusive() + 1;
            int total = range.total();
            while (nextStart < total) {
                ensureBufferedSize(nextStart, url);
                ByteRange nextRange;
                byte[] chunk;
                HttpResponse<InputStream> nextResponse = sendGet(url, "bytes=" + nextStart + "-");
                try (InputStream nextBody = nextResponse.body()) {
                    if (nextResponse.statusCode() != 206) {
                        throw new IllegalStateException("HTTP " + nextResponse.statusCode() + " while requesting " + url + " from byte " + nextStart);
                    }
                    nextRange = byteRange(nextResponse, url);
                    if (nextRange.start() != nextStart) {
                        throw new IllegalStateException("Unexpected byte range " + nextRange.start() + " for " + url + "; expected " + nextStart);
                    }
                    if (!nextRange.hasKnownTotal() || nextRange.total() != total) {
                        throw new IllegalStateException("Inconsistent Content-Range total for " + url);
                    }
                    chunk = readBounded(nextBody);
                }
                validateChunkLength(chunk.length, nextRange, url);
                out.write(chunk);
                nextStart = nextRange.endInclusive() + 1;
            }
            return out.toByteArray();
        }
    }

    private HttpResponse<InputStream> sendGet(String url, String range) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Evilkaraoke-Client")
                .GET();
        if (range != null) {
            builder.header("Range", range);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private byte[] readBounded(InputStream body) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = body.read(buffer)) != -1) {
            ensureBufferedSize((long) out.size() + read, "audio asset");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static ByteRange byteRange(HttpResponse<?> response, String url) {
        String value = response.headers().firstValue("Content-Range")
                .orElseThrow(() -> new IllegalStateException("Partial response without Content-Range for " + url));
        Matcher matcher = CONTENT_RANGE.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalStateException("Unsupported Content-Range \"" + value + "\" for " + url);
        }
        long start = Long.parseLong(matcher.group(1));
        long endInclusive = Long.parseLong(matcher.group(2));
        int total = matcher.group(3).equals("*") ? -1 : Math.toIntExact(Long.parseLong(matcher.group(3)));
        if (endInclusive < start || total > MAX_BUFFERED_ASSET_BYTES) {
            throw new IllegalStateException("Unsupported Content-Range \"" + value + "\" for " + url);
        }
        return new ByteRange(start, endInclusive, total);
    }

    private static void validateChunkLength(int actualLength, ByteRange range, String url) {
        long expectedLength = range.endInclusive() - range.start() + 1;
        if (actualLength != expectedLength) {
            throw new IllegalStateException("Expected " + expectedLength + " bytes for " + url + " range " + range.start() + "-" + range.endInclusive() + " but got " + actualLength);
        }
    }

    private static void ensureBufferedSize(long size, String url) {
        if (size > MAX_BUFFERED_ASSET_BYTES) {
            throw new IllegalStateException("Audio asset is too large to buffer safely: " + url);
        }
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
            if (handle.thread != null) {
                handle.thread.interrupt();
            }
        }
    }

    private static void applyGain(SourceDataLine line, float linearGain) {
        if (line == null) {
            return;
        }
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl control = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float clamped = clamp01(linearGain);
            float decibels = clamped <= 0.0001f ? control.getMinimum() : (float) (20.0 * Math.log10(clamped));
            control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), decibels)));
        }
    }

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        return Math.min(value, 1.0f);
    }

    private static final class PlaybackHandle {
        private final String playbackId;
        private final Duration seekOffset;
        private volatile Thread thread;
        private volatile SourceDataLine line;
        private volatile boolean paused;
        private volatile boolean stopped;

        private PlaybackHandle(String playbackId, Duration seekOffset) {
            this.playbackId = playbackId;
            this.seekOffset = seekOffset != null ? seekOffset : Duration.ZERO;
        }
    }
}
