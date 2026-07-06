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
        byte[] audio = "finite-audio".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server = bytesServer("/song.bin", audio, false);
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();

            relay.relay("global", "playback-1", track(url(server, "/song.bin"), AudioFormat.UNKNOWN, Duration.ofSeconds(30)),
                    PlaybackTarget.allPlayers(), Duration.ZERO, Instant.EPOCH, "", packets::add, () -> true).join();

            assertEquals(2, packets.size());
            AudioStreamChunkPacket chunk = assertInstanceOf(AudioStreamChunkPacket.class, packets.get(0));
            assertEquals(0, chunk.sequence());
            assertArrayEquals(audio, chunk.decodedData());
            AudioStreamChunkPacket end = assertInstanceOf(AudioStreamChunkPacket.class, packets.get(1));
            assertTrue(end.end());
            assertEquals(1, end.sequence());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamRelayStripsIcyMetadataBlocks() throws Exception {
        byte[] audio = "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] metadata = "StreamTitle='x';".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(audio, 0, 3);
        payload.write(1);
        payload.write(metadata);
        payload.write(audio, 3, 3);
        HttpServer server = bytesServer("/radio", payload.toByteArray(), true);
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();

            relay.relay("global", "playback-1", track(url(server, "/radio"), AudioFormat.STREAM, null),
                    PlaybackTarget.allPlayers(), Duration.ZERO, Instant.EPOCH, "", packets::add, () -> true).join();

            assertArrayEquals(audio, combinedAudio(packets));
            assertTrue(assertInstanceOf(AudioStreamChunkPacket.class, packets.getLast()).end());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellationSuppressesRemainingChunksAndEndPacket() throws Exception {
        byte[] audio = new byte[ServerAudioStreamRelay.CHUNK_BYTES * 2];
        java.util.Arrays.fill(audio, (byte) 7);
        HttpServer server = bytesServer("/song.bin", audio, false);
        server.start();
        try {
            ServerAudioStreamRelay relay = new ServerAudioStreamRelay(URI::normalize);
            List<ProtocolPacket> packets = new ArrayList<>();
            AtomicBoolean keepGoing = new AtomicBoolean(true);

            relay.relay("global", "playback-1", track(url(server, "/song.bin"), AudioFormat.UNKNOWN, Duration.ofSeconds(30)),
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
        byte[] primary = "primary".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] fallback = "fallback".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AtomicBoolean fallbackRequested = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/primary", exchange -> {
            exchange.sendResponseHeaders(200, primary.length);
            exchange.getResponseBody().write(primary);
            exchange.close();
        });
        server.createContext("/fallback", exchange -> {
            fallbackRequested.set(true);
            exchange.sendResponseHeaders(200, fallback.length);
            exchange.getResponseBody().write(fallback);
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
}
