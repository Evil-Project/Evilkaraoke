package org.evilproject.evilkaraoke.common.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.TrackType;
import org.evilproject.evilkaraoke.common.protocol.AudioStreamChunkPacket;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;
import org.junit.jupiter.api.Test;

class ServerAudioStreamRelayTest {
    @Test
    void finiteAssetRelaySendsChunkAndEnd() throws Exception {
        byte[] pcm = pcmBytes(256);
        HttpServer server = bytesServer("/song.wav", wav(pcm), false);
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();

            relay.relay("global", "playback-1", track(url(server, "/song.wav"), AudioFormat.UNKNOWN, Duration.ofSeconds(30)),
                    PlaybackTarget.allPlayers(), Duration.ZERO, Instant.EPOCH, "", packets::add, () -> true).join();

            assertEquals(2, packets.size());
            AudioStreamChunkPacket chunk = assertInstanceOf(AudioStreamChunkPacket.class, packets.get(0));
            assertEquals(0, chunk.sequence());
            assertEquals(44_100.0f, chunk.sampleRate());
            assertEquals(1, chunk.channels());
            assertEquals(16, chunk.bitsPerSample());
            assertArrayEquals(pcm, chunk.decodedData());
            AudioStreamChunkPacket end = assertInstanceOf(AudioStreamChunkPacket.class, packets.get(1));
            assertTrue(end.end());
            assertEquals(1, end.sequence());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void finiteTrackRelayPacesDecodedPcmByItsByteRate() throws Exception {
        // ~820 KB of mono 44.1 kHz PCM (88_200 B/s) decodes to more than the ~705 KB
        // prebuffer, so the relay must pace the tail at the decoded PCM rate rather
        // than racing the whole track to the client. Before this was paced by the PCM
        // byte-rate the relay flushed everything at once, overflowing the client's
        // audio buffer and cutting long songs off partway.
        byte[] pcm = pcmBytes(820_000);
        HttpServer server = bytesServer("/song.wav", wav(pcm), false);
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();

            long startNanos = System.nanoTime();
            relay.relay("global", "playback-1", track(url(server, "/song.wav"), AudioFormat.UNKNOWN, Duration.ofSeconds(30)),
                    PlaybackTarget.allPlayers(), Duration.ZERO, Instant.EPOCH, "", packets::add, () -> true).join();
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            assertArrayEquals(pcm, combinedAudio(packets));
            assertTrue(elapsedMillis >= 700L,
                    "expected the relay to pace the decoded PCM tail, but it finished in " + elapsedMillis + "ms");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void finiteAssetStreamsWhileDownloadIsStillInProgress() throws Exception {
        // The origin writes the head of the file, then withholds the tail until a
        // chunk has been broadcast. If the relay buffered the whole download before
        // decoding (the old behavior), no chunk could ever be sent and the await
        // below would time out, failing the assertion.
        byte[] pcm = pcmBytes(ServerAudioStreamRelay.CHUNK_BYTES * 8);
        byte[] payload = wav(pcm);
        int headLength = ServerAudioStreamRelay.CHUNK_BYTES * 2;
        java.util.concurrent.CountDownLatch chunkBroadcast = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean chunkSentBeforeDownloadCompleted = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/song.wav", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload, 0, headLength);
            exchange.getResponseBody().flush();
            try {
                chunkSentBeforeDownloadCompleted.set(
                        chunkBroadcast.await(10, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseBody().write(payload, headLength, payload.length - headLength);
            exchange.close();
        });
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = java.util.Collections.synchronizedList(new ArrayList<>());

            relay.relay("global", "playback-1", track(url(server, "/song.wav"), AudioFormat.UNKNOWN, Duration.ofSeconds(30)),
                    PlaybackTarget.allPlayers(), Duration.ZERO, Instant.EPOCH, "", packet -> {
                        packets.add(packet);
                        chunkBroadcast.countDown();
                    }, () -> true).join();

            assertTrue(chunkSentBeforeDownloadCompleted.get(),
                    "expected the relay to broadcast audio before the download completed");
            assertArrayEquals(pcm, combinedAudio(packets));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamRelayStripsIcyMetadataBlocks() throws Exception {
        byte[] pcm = pcmBytes(256);
        byte[] audio = wav(pcm);
        byte[] metadata = "StreamTitle='x';".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = bytesServer("/radio", icyPayload(audio, 3, metadata), true);
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();

            relay.relay("global", "playback-1", track(url(server, "/radio"), AudioFormat.STREAM, null),
                    PlaybackTarget.allPlayers(), Duration.ZERO, Instant.EPOCH, "", packets::add, () -> true).join();

            assertArrayEquals(pcm, combinedAudio(packets));
            assertTrue(assertInstanceOf(AudioStreamChunkPacket.class, packets.getLast()).end());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellationSuppressesRemainingChunksAndEndPacket() throws Exception {
        byte[] pcm = pcmBytes(ServerAudioStreamRelay.CHUNK_BYTES * 2);
        HttpServer server = bytesServer("/song.wav", wav(pcm), false);
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();
            AtomicBoolean keepGoing = new AtomicBoolean(true);

            relay.relay("global", "playback-1", track(url(server, "/song.wav"), AudioFormat.UNKNOWN, Duration.ofSeconds(30)),
                    PlaybackTarget.allPlayers(), Duration.ZERO, Instant.EPOCH, "", packet -> {
                        packets.add(packet);
                        keepGoing.set(false);
                    }, keepGoing::get).join();

            assertEquals(1, packets.size());
            AudioStreamChunkPacket chunk = assertInstanceOf(AudioStreamChunkPacket.class, packets.get(0));
            assertEquals(0, chunk.sequence());
            assertTrue(chunk.decodedData().length > 0);
            assertTrue(packets.stream()
                    .map(AudioStreamChunkPacket.class::cast)
                    .noneMatch(AudioStreamChunkPacket::end));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void chunkDispatchFailureDoesNotAppendFallbackBytesToSameStream() throws Exception {
        byte[] primary = pcmBytes(256);
        byte[] fallback = pcmBytes(128);
        AtomicBoolean fallbackRequested = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/primary", exchange -> {
            byte[] payload = wav(primary);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.createContext("/fallback", exchange -> {
            fallbackRequested.set(true);
            byte[] payload = wav(fallback);
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();
            KaraokeTrack track = new KaraokeTrack(
                    "track",
                    TrackType.SONG,
                    "Track",
                    "Artist",
                    new AudioAsset(url(server, "/primary"), AudioFormat.UNKNOWN),
                    new AudioAsset(url(server, "/fallback"), AudioFormat.UNKNOWN),
                    Duration.ofSeconds(30));

            relay.relay("global", "playback-1", track, PlaybackTarget.allPlayers(),
                    Duration.ZERO, Instant.EPOCH, "", packet -> {
                        packets.add(packet);
                        if (packet instanceof AudioStreamChunkPacket chunk && !chunk.end() && chunk.error().isBlank()) {
                            throw new IllegalStateException("send failed after chunk dispatch");
                        }
                    }, () -> true).join();

            assertArrayEquals(primary, combinedAudio(packets));
            assertTrue(!assertInstanceOf(AudioStreamChunkPacket.class, packets.getLast()).error().isBlank());
            assertEquals(false, fallbackRequested.get());
        } finally {
            server.stop(0);
        }
    }

    private static byte[] combinedAudio(List<ProtocolPacket> packets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        packets.stream()
                .map(AudioStreamChunkPacket.class::cast)
                .filter(packet -> !packet.end())
                .forEach(packet -> {
                    try {
                        out.write(packet.decodedData());
                    } catch (java.io.IOException ex) {
                        throw new AssertionError(ex);
                    }
                });
        return out.toByteArray();
    }

    private static HttpServer bytesServer(String path, byte[] bytes, boolean icyMetadata) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, exchange -> {
            if (icyMetadata) {
                exchange.getResponseHeaders().set("icy-metaint", "3");
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private static String url(HttpServer server, String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private static KaraokeTrack track(String url, AudioFormat format, Duration duration) {
        return new KaraokeTrack(
                "track",
                TrackType.SONG,
                "Track",
                "Artist",
                new AudioAsset(url, format),
                null,
                duration);
    }

    private static byte[] pcmBytes(int length) {
        byte[] pcm = new byte[length + (length % 2)];
        for (int i = 0; i < pcm.length; i += 2) {
            short sample = (short) (i * 7);
            pcm[i] = (byte) (sample & 0xFF);
            pcm[i + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return pcm;
    }

    private static byte[] wav(byte[] pcm) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int sampleRate = 44_100;
        short channels = 1;
        short bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        short blockAlign = (short) (channels * bitsPerSample / 8);
        writeAscii(out, "RIFF");
        writeIntLe(out, 36 + pcm.length);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLe(out, 16);
        writeShortLe(out, (short) 1);
        writeShortLe(out, channels);
        writeIntLe(out, sampleRate);
        writeIntLe(out, byteRate);
        writeShortLe(out, blockAlign);
        writeShortLe(out, bitsPerSample);
        writeAscii(out, "data");
        writeIntLe(out, pcm.length);
        out.write(pcm);
        return out.toByteArray();
    }

    private static byte[] icyPayload(byte[] audio, int interval, byte[] metadata) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;
        boolean wroteMetadata = false;
        while (offset < audio.length) {
            int length = Math.min(interval, audio.length - offset);
            out.write(audio, offset, length);
            offset += length;
            if (!wroteMetadata) {
                out.write(1);
                out.write(metadata);
                wroteMetadata = true;
            } else if (offset < audio.length) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeShortLe(ByteArrayOutputStream out, short value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeIntLe(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }
}
