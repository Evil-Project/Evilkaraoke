package org.evilproject.evilkaraoke.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.junit.jupiter.api.Test;

class JavaSoundAudioBackendTest {
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

    private static byte[] oggHeader(String codecMarker) {
        byte[] header = new byte[96];
        byte[] ogg = "OggS".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] marker = codecMarker.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(ogg, 0, header, 0, ogg.length);
        System.arraycopy(marker, 0, header, 28, marker.length);
        return header;
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
}
