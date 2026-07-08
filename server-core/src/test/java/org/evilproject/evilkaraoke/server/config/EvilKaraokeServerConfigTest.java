package org.evilproject.evilkaraoke.server.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeEndpoints;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvilKaraokeServerConfigTest {
    @TempDir
    private Path tempDir;

    @Test
    void saveWritesDebugSectionReadByLoader() throws Exception {
        Path file = tempDir.resolve("evilkaraoke.json");
        EvilKaraokeServerConfig config = new EvilKaraokeServerConfig(
                NeurokaraokeEndpoints.defaults(),
                new EvilKaraokeConfig("@a", "record", 1.0f, 1.0f, 0.0f, 2, 3L, true, true, true, 60, true));

        config.save(file);

        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertTrue(root.getAsJsonObject("debug").get("logPackets").getAsBoolean());
        assertTrue(EvilKaraokeServerConfig.loadOrCreate(file, Logger.getLogger("test")).playback().debugPackets());
    }

    @Test
    void loaderAcceptsLegacyPlaybackDebugPacketsField() throws Exception {
        Path file = tempDir.resolve("legacy.json");
        Files.writeString(file, """
                {
                  "playback": {
                    "debugPackets": true
                  }
                }
                """);

        EvilKaraokeServerConfig config = EvilKaraokeServerConfig.loadOrCreate(file, Logger.getLogger("test"));

        assertTrue(config.playback().debugPackets());
    }
}
