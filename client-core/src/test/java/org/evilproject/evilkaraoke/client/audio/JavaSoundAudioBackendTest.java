package org.evilproject.evilkaraoke.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;
import javax.sound.sampled.Control;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import org.evilproject.evilkaraoke.client.net.ClientAudioController;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.AudioDeliveryMode;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.junit.jupiter.api.Test;

class JavaSoundAudioBackendTest {
    @Test
    void playDecodesFiniteWavAndWritesPcmToLine() throws Exception {
        byte[] pcm = pcm16(1200, -1200, 2400, -2400);
        byte[] wav = wav(pcm, 44_100, 1);
        RecordingLine line = new RecordingLine();
        HttpServer server = bytesServer("/song.wav", wav);
        server.start();
        try {
            JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri, format -> line);
            String url = "http://localhost:" + server.getAddress().getPort() + "/song.wav";

            backend.play(playPacket("wav-play", new AudioAsset(url, AudioFormat.UNKNOWN), Duration.ofMillis(1)));

            waitUntil(() -> backend.status().state() == ClientPlaybackState.STOPPED, Duration.ofSeconds(3));
            assertEquals(ClientPlaybackState.STOPPED, backend.status().state());
            assertArrayEquals(pcm, line.writtenBytes());
            assertTrue(line.started, "audio line should have been started");
            assertTrue(line.drained, "audio line should drain on normal completion");
            assertTrue(line.closed, "audio line should be closed after playback");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void playStreamDecodesServerStreamAndWritesPcmToLine() throws Exception {
        byte[] pcm = pcm16(3000, -3000, 4000, -4000);
        byte[] wav = wav(pcm, 44_100, 1);
        RecordingLine line = new RecordingLine();
        JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri, format -> line);
        PipedInputStream input = new PipedInputStream(64 * 1024);

        try (PipedOutputStream output = new PipedOutputStream(input)) {
            backend.playStream(playPacket("stream-play", new AudioAsset("https://example.invalid/song.wav", AudioFormat.UNKNOWN), Duration.ofMillis(1)), input);
            output.write(wav);
        }

        waitUntil(() -> backend.status().state() == ClientPlaybackState.STOPPED, Duration.ofSeconds(3));
        assertEquals(ClientPlaybackState.STOPPED, backend.status().state());
        assertArrayEquals(pcm, line.writtenBytes());
        assertTrue(line.started, "audio line should have been started");
        assertTrue(line.drained, "audio line should drain on normal completion");
        assertTrue(line.closed, "audio line should be closed after stream playback");
    }

    @Test
    void serverStreamPacketsDecodeThroughControllerAndWritePcmToLine() throws Exception {
        byte[] pcm = pcm16(5000, -5000, 6000, -6000);
        RecordingLine line = new RecordingLine();
        JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri, format -> line);
        ClientAudioController controller = new ClientAudioController(Logger.getLogger("test"), backend, "0.1.0", "26.2", "test");
        JsonPacketCodec codec = new JsonPacketCodec();
        String playbackId = "controller-stream";
        AudioCommandPacket play = new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                playbackId,
                new KaraokeTrack(playbackId, TrackType.SONG, "Streamed", "Artist",
                        new AudioAsset("https://example.invalid/song.wav", AudioFormat.UNKNOWN), null, Duration.ofMillis(1)),
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "",
                Duration.ZERO,
                AudioDeliveryMode.SERVER_STREAM);

        controller.handleAudioPayload(codec.encode(play));
        int sequence = 0;
        for (int offset = 0; offset < pcm.length; offset += 3) {
            byte[] slice = java.util.Arrays.copyOfRange(pcm, offset, Math.min(pcm.length, offset + 3));
            controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.chunk("global", playbackId, sequence++,
                    slice, slice.length, 44_100.0f, 1, 16)));
        }
        controller.handleAudioPayload(codec.encode(AudioStreamChunkPacket.end("global", playbackId, sequence)));

        waitUntil(() -> backend.status().state() == ClientPlaybackState.STOPPED, Duration.ofSeconds(3));
        assertArrayEquals(pcm, line.writtenBytes());
        assertTrue(line.started, "audio line should have been started");
        assertTrue(line.drained, "audio line should drain on normal completion");
        assertTrue(line.closed, "audio line should close after packet stream playback");
    }

    @Test
    void streamReadFailureReportsErrorInsteadOfFinished() throws Exception {
        byte[] pcmPrefix = new byte[1024];
        byte[] wavPrefix = wav(pcmPrefix, 44_100, 1, 4096);
        RecordingLine line = new RecordingLine();
        JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri, format -> line);

        backend.playStream(
                playPacket("broken-stream", new AudioAsset("https://example.invalid/song.wav", AudioFormat.UNKNOWN), Duration.ofSeconds(1)),
                new FailingInputStream(wavPrefix));

        waitUntil(() -> backend.status().state() == ClientPlaybackState.ERROR, Duration.ofSeconds(3));
        assertEquals(ClientPlaybackState.ERROR, backend.status().state());
        assertTrue(line.closed, "audio line should still close after a stream read failure");
    }

    @Test
    void softwareGainScalesSignedLittleEndianPcm() {
        byte[] pcm = pcm16(10_000, -10_000, 2_000, -2_000);

        JavaSoundAudioBackend.applySoftwareGain(pcm, pcm.length, 0.5f);

        assertArrayEquals(pcm16(5_000, -5_000, 1_000, -1_000), pcm);
    }

    @Test
    void softwareGainCanMutePcm() {
        byte[] pcm = pcm16(10_000, -10_000);

        JavaSoundAudioBackend.applySoftwareGain(pcm, pcm.length, 0.0f);

        assertArrayEquals(pcm16(0, 0), pcm);
    }

    @Test
    void detectsOpusOggWithoutConsumingStream() throws Exception {
        byte[] header = oggHeader("OpusHead");
        BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(header), 8);

        JavaSoundAudioBackend.EncodedAudioKind kind = JavaSoundAudioBackend.detectAudioKind(stream);

        assertEquals(JavaSoundAudioBackend.EncodedAudioKind.OPUS, kind);
        assertEquals('O', stream.read());
    }

    @Test
    void detectsVorbisOggWithoutConsumingStream() throws Exception {
        byte[] header = oggHeader("\u0001vorbis");
        BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(header), 8);

        JavaSoundAudioBackend.EncodedAudioKind kind = JavaSoundAudioBackend.detectAudioKind(stream);

        assertEquals(JavaSoundAudioBackend.EncodedAudioKind.VORBIS, kind);
        assertEquals('O', stream.read());
    }

    @Test
    void detectsMp3HeadersWithoutConsumingStream() throws Exception {
        byte[] header = "ID3\u0003\u0000\u0000".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(header), 4);

        JavaSoundAudioBackend.EncodedAudioKind kind = JavaSoundAudioBackend.detectAudioKind(stream);

        assertEquals(JavaSoundAudioBackend.EncodedAudioKind.MP3, kind);
        assertEquals('I', stream.read());
    }

    @Test
    void detectsMp3FrameWithoutConsumingStream() throws Exception {
        byte[] header = mp3FrameHeader();
        BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(header), 4);

        JavaSoundAudioBackend.EncodedAudioKind kind = JavaSoundAudioBackend.detectAudioKind(stream);

        assertEquals(JavaSoundAudioBackend.EncodedAudioKind.MP3, kind);
        assertEquals(0xFF, stream.read());
    }

    @Test
    void detectsMp3StreamThatStartsMidFrameWithoutConsumingStream() throws Exception {
        byte[] prefix = new byte[] {0x43, (byte) 0xCD, 0x13, 0x5A, (byte) 0xCA};
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(prefix);
        header.write(mp3FrameHeader());
        BufferedInputStream stream = new BufferedInputStream(new ByteArrayInputStream(header.toByteArray()), 16);

        JavaSoundAudioBackend.EncodedAudioKind kind = JavaSoundAudioBackend.detectAudioKind(stream);

        assertEquals(JavaSoundAudioBackend.EncodedAudioKind.MP3, kind);
        assertEquals(prefix[0] & 0xFF, stream.read());
    }

    @Test
    void finiteAssetDownloadAssemblesPartialContentResponses() throws Exception {
        byte[] audio = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/song.ogg", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            int start = range == null ? 0 : Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
            int end = Math.min(start + 3, audio.length - 1);
            byte[] chunk = java.util.Arrays.copyOfRange(audio, start, end + 1);
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + audio.length);
            exchange.sendResponseHeaders(206, chunk.length);
            exchange.getResponseBody().write(chunk);
            exchange.close();
        });
        server.start();
        try {
            JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri);
            String url = "http://localhost:" + server.getAddress().getPort() + "/song.ogg";

            byte[] decodedInput;
            try (InputStream stream = backend.openDecodableStream(new AudioAsset(url, AudioFormat.OPUS))) {
                decodedInput = stream.readAllBytes();
            }

            assertArrayEquals(audio, decodedInput);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonHttpAudioUrls() {
        JavaSoundAudioBackend backend = new JavaSoundAudioBackend();

        assertThrows(IllegalArgumentException.class,
                () -> backend.openDecodableStream(new AudioAsset("file:///tmp/song.opus", AudioFormat.OPUS)));
    }

    @Test
    void rejectsLoopbackAudioUrlsInProductionValidator() {
        JavaSoundAudioBackend backend = new JavaSoundAudioBackend();

        assertThrows(IllegalArgumentException.class,
                () -> backend.openDecodableStream(new AudioAsset("http://127.0.0.1/song.opus", AudioFormat.OPUS)));
    }

    @Test
    void serverProvidedAudioUrlsCanContainLiteralSpaces() throws Exception {
        byte[] audio = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AtomicReference<String> requestedPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(200, audio.length);
            exchange.getResponseBody().write(audio);
            exchange.close();
        });
        server.start();
        try {
            JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri);
            String url = "http://localhost:" + server.getAddress().getPort() + "/audio/Shooting Stars.mp3";

            byte[] decodedInput;
            try (InputStream stream = backend.openDecodableStream(new AudioAsset(url, AudioFormat.MP3))) {
                decodedInput = stream.readAllBytes();
            }

            assertEquals("/audio/Shooting%20Stars.mp3", requestedPath.get());
            assertArrayEquals(audio, decodedInput);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamRequestsDisableIcyMetadataAndStripMetadataBlocks() throws Exception {
        byte[] audio = "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] metadata = "StreamTitle='x';".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(audio, 0, 3);
        payload.write(1);
        payload.write(metadata);
        payload.write(audio, 3, 3);
        AtomicReference<String> icyMetadataHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/radio", exchange -> {
            icyMetadataHeader.set(exchange.getRequestHeaders().getFirst("Icy-MetaData"));
            byte[] response = payload.toByteArray();
            exchange.getResponseHeaders().set("icy-metaint", "3");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri);
            String url = "http://localhost:" + server.getAddress().getPort() + "/radio";

            byte[] decodedInput;
            try (InputStream stream = backend.openDecodableStream(new AudioAsset(url, AudioFormat.STREAM))) {
                decodedInput = stream.readAllBytes();
            }

            assertEquals("0", icyMetadataHeader.get());
            assertArrayEquals(audio, decodedInput);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedPlaybackIsNotLeftPausable() throws Exception {
        byte[] invalidAudio = "not an audio file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bad.opus", exchange -> {
            exchange.sendResponseHeaders(200, invalidAudio.length);
            exchange.getResponseBody().write(invalidAudio);
            exchange.close();
        });
        server.start();
        try {
            JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri);
            String url = "http://localhost:" + server.getAddress().getPort() + "/bad.opus";
            AudioCommandPacket packet = new AudioCommandPacket(
                    AudioCommandType.PLAY,
                    "global",
                    "failed-playback",
                    new KaraokeTrack("bad", TrackType.SONG, "Bad", "Artist", new AudioAsset(url, AudioFormat.OPUS), null, Duration.ofSeconds(1)),
                    PlaybackTarget.allPlayers(),
                    Duration.ZERO,
                    Instant.EPOCH,
                    "",
                    Duration.ZERO);

            backend.play(packet);
            waitUntil(() -> backend.status().state() == ClientPlaybackState.ERROR, Duration.ofSeconds(3));
            backend.pause(packet);

            assertEquals(ClientPlaybackState.ERROR, backend.status().state());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stopClosesStalledFiniteDownloadWorker() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stalled.opus", exchange -> {
            exchange.sendResponseHeaders(200, 1024);
            headersSent.countDown();
            try {
                releaseServer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            JavaSoundAudioBackend backend = new JavaSoundAudioBackend(uri -> uri);
            String playbackId = "stalled-download-" + System.nanoTime();
            String workerName = "evilkaraoke-audio-" + playbackId;
            String url = "http://localhost:" + server.getAddress().getPort() + "/stalled.opus";
            AudioCommandPacket packet = new AudioCommandPacket(
                    AudioCommandType.PLAY,
                    "global",
                    playbackId,
                    new KaraokeTrack("stalled", TrackType.SONG, "Stalled", "Artist", new AudioAsset(url, AudioFormat.OPUS), null, Duration.ofSeconds(1)),
                    PlaybackTarget.allPlayers(),
                    Duration.ZERO,
                    Instant.EPOCH,
                    "",
                    Duration.ZERO);

            backend.play(packet);
            assertTrue(headersSent.await(2, TimeUnit.SECONDS), "server did not start stalled response");
            backend.stop(packet);

            waitUntil(() -> !hasThreadNamed(workerName), Duration.ofSeconds(2));
            assertEquals(ClientPlaybackState.STOPPED, backend.status().state());
        } finally {
            releaseServer.countDown();
            server.stop(0);
        }
    }

    private static byte[] pcm16(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            bytes[i * 2] = (byte) (samples[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xFF);
        }
        return bytes;
    }

    private static byte[] wav(byte[] pcm, int sampleRate, int channels) throws java.io.IOException {
        return wav(pcm, sampleRate, channels, pcm.length);
    }

    private static byte[] wav(byte[] pcm, int sampleRate, int channels, int declaredPcmLength) throws java.io.IOException {
        int byteRate = sampleRate * channels * 2;
        int blockAlign = channels * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.write(new byte[] {'R', 'I', 'F', 'F'});
        writeLittleEndianInt(out, 36 + declaredPcmLength);
        out.write(new byte[] {'W', 'A', 'V', 'E'});
        out.write(new byte[] {'f', 'm', 't', ' '});
        writeLittleEndianInt(out, 16);
        writeLittleEndianShort(out, 1);
        writeLittleEndianShort(out, channels);
        writeLittleEndianInt(out, sampleRate);
        writeLittleEndianInt(out, byteRate);
        writeLittleEndianShort(out, blockAlign);
        writeLittleEndianShort(out, 16);
        out.write(new byte[] {'d', 'a', 't', 'a'});
        writeLittleEndianInt(out, declaredPcmLength);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeLittleEndianInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static HttpServer bytesServer(String path, byte[] bytes) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private static AudioCommandPacket playPacket(String playbackId, AudioAsset asset, Duration duration) {
        return new AudioCommandPacket(
                AudioCommandType.PLAY,
                "global",
                playbackId,
                new KaraokeTrack(playbackId, TrackType.SONG, "Test", "Artist", asset, null, duration),
                PlaybackTarget.allPlayers(),
                Duration.ZERO,
                Instant.EPOCH,
                "",
                Duration.ZERO);
    }

    private static byte[] oggHeader(String codecMarker) {
        byte[] header = new byte[96];
        byte[] ogg = "OggS".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] marker = codecMarker.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(ogg, 0, header, 0, ogg.length);
        System.arraycopy(marker, 0, header, 28, marker.length);
        return header;
    }

    private static byte[] mp3FrameHeader() {
        return new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64};
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }

    private static boolean hasThreadNamed(String threadName) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && threadName.equals(thread.getName()));
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private static final class RecordingLine implements SourceDataLine {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private javax.sound.sampled.AudioFormat format;
        private boolean open;
        private boolean running;
        private boolean started;
        private boolean drained;
        private boolean closed;

        @Override
        public void open(javax.sound.sampled.AudioFormat format, int bufferSize) {
            this.format = format;
            this.open = true;
            this.closed = false;
        }

        @Override
        public void open(javax.sound.sampled.AudioFormat format) {
            open(format, 0);
        }

        @Override
        public int write(byte[] buffer, int offset, int length) {
            written.write(buffer, offset, length);
            return length;
        }

        @Override
        public void drain() {
            drained = true;
        }

        @Override
        public void flush() {
        }

        @Override
        public void start() {
            started = true;
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isActive() {
            return running;
        }

        @Override
        public javax.sound.sampled.AudioFormat getFormat() {
            return format;
        }

        @Override
        public int getBufferSize() {
            return 64 * 1024;
        }

        @Override
        public int available() {
            return getBufferSize();
        }

        @Override
        public int getFramePosition() {
            return written.size() / Math.max(1, format == null ? 1 : format.getFrameSize());
        }

        @Override
        public long getLongFramePosition() {
            return getFramePosition();
        }

        @Override
        public long getMicrosecondPosition() {
            return 0L;
        }

        @Override
        public float getLevel() {
            return 0.0f;
        }

        @Override
        public Line.Info getLineInfo() {
            return new Line.Info(SourceDataLine.class);
        }

        @Override
        public void open() throws LineUnavailableException {
            open = true;
            closed = false;
        }

        @Override
        public void close() {
            open = false;
            closed = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public Control[] getControls() {
            return new Control[0];
        }

        @Override
        public boolean isControlSupported(Control.Type control) {
            return false;
        }

        @Override
        public Control getControl(Control.Type control) {
            throw new IllegalArgumentException("Unsupported control: " + control);
        }

        @Override
        public void addLineListener(LineListener listener) {
        }

        @Override
        public void removeLineListener(LineListener listener) {
        }

        byte[] writtenBytes() {
            return written.toByteArray();
        }
    }

    private static final class FailingInputStream extends InputStream {
        private final byte[] prefix;
        private int offset;

        private FailingInputStream(byte[] prefix) {
            this.prefix = prefix;
        }

        @Override
        public int read() throws java.io.IOException {
            if (offset >= prefix.length) {
                throw new java.io.IOException("Simulated stream failure");
            }
            return prefix[offset++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws java.io.IOException {
            java.util.Objects.checkFromIndexSize(off, len, buffer.length);
            if (len == 0) {
                return 0;
            }
            if (offset >= prefix.length) {
                throw new java.io.IOException("Simulated stream failure");
            }
            int copied = Math.min(len, prefix.length - offset);
            System.arraycopy(prefix, offset, buffer, off, copied);
            offset += copied;
            return copied;
        }
    }
}
