package org.evilproject.evilkaraoke.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.InputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpServer;
import org.evilproject.evilkaraoke.common.model.AudioAsset;
import org.evilproject.evilkaraoke.common.model.AudioFormat;
import org.junit.jupiter.api.Test;

class JavaSoundAudioBackendTest {
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
            JavaSoundAudioBackend backend = new JavaSoundAudioBackend();
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
}
