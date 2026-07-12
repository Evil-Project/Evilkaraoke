package org.evilproject.evilkaraoke.client.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvilKaraokeClientConfigTest {
    private static final Logger LOGGER = Logger.getLogger(EvilKaraokeClientConfigTest.class.getName());

    @TempDir
    Path tempDir;

    @Test
    void missingConfigDefaultsLyricsToDisabled() {
        EvilKaraokeClientConfig config = EvilKaraokeClientConfig.load(tempDir, LOGGER);

        assertFalse(config.lyricsEnabled());
    }

    @Test
    void remembersEnabledAndDisabledLyricsSettings() {
        Path configDirectory = tempDir.resolve("nested").resolve("config");
        EvilKaraokeClientConfig config = EvilKaraokeClientConfig.load(configDirectory, LOGGER);

        config.setLyricsEnabled(true);
        assertTrue(config.lyricsEnabled());
        assertTrue(EvilKaraokeClientConfig.load(configDirectory, LOGGER).lyricsEnabled());

        config.setLyricsEnabled(false);
        assertFalse(config.lyricsEnabled());
        assertFalse(EvilKaraokeClientConfig.load(configDirectory, LOGGER).lyricsEnabled());
    }

    @Test
    void malformedConfigDefaultsLyricsToDisabled() throws IOException {
        Files.writeString(tempDir.resolve(EvilKaraokeClientConfig.FILE_NAME), "{not valid json");

        EvilKaraokeClientConfig config = EvilKaraokeClientConfig.load(tempDir, LOGGER);

        assertFalse(config.lyricsEnabled());
    }

    @Test
    void failedSaveKeepsTheRuntimeSetting() throws IOException {
        Path configDirectory = tempDir.resolve("not-a-directory");
        Files.writeString(configDirectory, "blocks directory creation");
        EvilKaraokeClientConfig config = EvilKaraokeClientConfig.load(configDirectory, LOGGER);

        config.setLyricsEnabled(true);

        assertTrue(config.lyricsEnabled());
    }
}
