package org.evilproject.evilkaraoke.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

public final class EvilKaraokeClientConfig {
    public static final String FILE_NAME = "evilkaraoke-client.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private volatile boolean lyricsEnabled;

    private EvilKaraokeClientConfig(Path file, Logger logger, boolean lyricsEnabled) {
        this.file = file;
        this.logger = logger;
        this.lyricsEnabled = lyricsEnabled;
    }

    public static EvilKaraokeClientConfig load(Path configDirectory, Logger logger) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        Objects.requireNonNull(logger, "logger");

        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new EvilKaraokeClientConfig(file, logger, false);
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StoredConfig stored = GSON.fromJson(reader, StoredConfig.class);
            return new EvilKaraokeClientConfig(file, logger, stored != null && stored.lyricsEnabled());
        } catch (IOException | JsonParseException ex) {
            logger.log(Level.WARNING, "Could not load Evilkaraoke client config from " + file, ex);
            return new EvilKaraokeClientConfig(file, logger, false);
        }
    }

    public boolean lyricsEnabled() {
        return lyricsEnabled;
    }

    public synchronized void setLyricsEnabled(boolean lyricsEnabled) {
        this.lyricsEnabled = lyricsEnabled;
        persist(lyricsEnabled);
    }

    private void persist(boolean lyricsEnabled) {
        Path parent = file.getParent();
        Path temporaryFile = null;
        try {
            Path targetDirectory = parent == null ? Path.of(".") : parent;
            Files.createDirectories(targetDirectory);
            temporaryFile = Files.createTempFile(targetDirectory, FILE_NAME, ".tmp");

            try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
                GSON.toJson(new StoredConfig(lyricsEnabled), writer);
            }

            try {
                Files.move(temporaryFile, file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporaryFile = null;
        } catch (IOException | JsonParseException ex) {
            logger.log(Level.WARNING, "Could not save Evilkaraoke client config to " + file, ex);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ex) {
                    logger.log(Level.FINE, "Could not remove temporary Evilkaraoke client config " + temporaryFile, ex);
                }
            }
        }
    }

    private record StoredConfig(boolean lyricsEnabled) {
    }
}
