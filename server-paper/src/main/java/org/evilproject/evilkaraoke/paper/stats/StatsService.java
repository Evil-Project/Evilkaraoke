package org.evilproject.evilkaraoke.paper.stats;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class StatsService {
    private final Logger logger;
    private final Path storageFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, UserStats> users = new ConcurrentHashMap<>();
    private final Map<String, SongStats> songs = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;

    public StatsService(Logger logger, Path storageFile) {
        this.logger = logger;
        this.storageFile = storageFile;
    }

    public void load() {
        if (!Files.exists(storageFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(storageFile, StandardCharsets.UTF_8)) {
            StatsSnapshot snapshot = gson.fromJson(reader, StatsSnapshot.class);
            if (snapshot == null) {
                return;
            }
            if (snapshot.users != null) {
                snapshot.users.forEach(user -> users.put(user.playerId(), user));
            }
            if (snapshot.songs != null) {
                snapshot.songs.forEach(song -> songs.put(song.songId(), song));
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to load Evilkaraoke stats", ex);
        }
    }

    public synchronized void save() {
        if (!dirty) {
            return;
        }
        try {
            Files.createDirectories(storageFile.getParent());
            try (Writer writer = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8)) {
                gson.toJson(new StatsSnapshot(new ArrayList<>(users.values()), new ArrayList<>(songs.values())), writer);
            }
            dirty = false;
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to save Evilkaraoke stats", ex);
        }
    }

    public void recordRequest(UUID playerId, String playerName, String songId, String title) {
        users.compute(playerId, (id, existing) -> {
            UserStats base = existing == null ? new UserStats(playerId, playerName, 0, 0, 0) : existing;
            return base.withRequest();
        });
        songs.compute(songId, (id, existing) -> {
            SongStats base = existing == null ? new SongStats(songId, title, 0, 0) : existing;
            return base.requested();
        });
        dirty = true;
    }

    public void recordPlay(UUID playerId, String playerName, String songId, String title, long listenSeconds) {
        if (playerId != null) {
            users.compute(playerId, (id, existing) -> {
                UserStats base = existing == null ? new UserStats(playerId, playerName, 0, 0, 0) : existing;
                return base.withListen(listenSeconds, 1);
            });
        }
        songs.compute(songId, (id, existing) -> {
            SongStats base = existing == null ? new SongStats(songId, title, 0, 0) : existing;
            return base.played();
        });
        dirty = true;
    }

    public UserStats user(UUID playerId, String playerName) {
        return users.getOrDefault(playerId, new UserStats(playerId, playerName, 0, 0, 0));
    }

    public Optional<UserStats> findUserByName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        return users.values().stream()
                .filter(user -> user.playerName() != null && user.playerName().equalsIgnoreCase(playerName))
                .findFirst();
    }

    public List<UserStats> topUsersByTime(int limit) {
        return topUsers(Comparator.comparingLong(UserStats::listenSeconds).reversed(), limit);
    }

    public List<UserStats> topUsersBySongs(int limit) {
        return topUsers(Comparator.comparingLong(UserStats::songsListened).reversed(), limit);
    }

    public List<UserStats> topUsersByRequests(int limit) {
        return topUsers(Comparator.comparingLong(UserStats::songsRequested).reversed(), limit);
    }

    public List<SongStats> topSongsByPlays(int limit) {
        return topSongs(Comparator.comparingLong(SongStats::timesPlayed).reversed(), limit);
    }

    public List<SongStats> topSongsByRequests(int limit) {
        return topSongs(Comparator.comparingLong(SongStats::timesRequested).reversed(), limit);
    }

    public ServerStats serverStats() {
        long totalSeconds = users.values().stream().mapToLong(UserStats::listenSeconds).sum();
        long totalPlayed = songs.values().stream().mapToLong(SongStats::timesPlayed).sum();
        long totalRequested = songs.values().stream().mapToLong(SongStats::timesRequested).sum();
        return new ServerStats(totalSeconds, totalPlayed, totalRequested);
    }

    private List<UserStats> topUsers(Comparator<UserStats> comparator, int limit) {
        return users.values().stream().sorted(comparator).limit(Math.max(1, limit)).toList();
    }

    private List<SongStats> topSongs(Comparator<SongStats> comparator, int limit) {
        return songs.values().stream().sorted(comparator).limit(Math.max(1, limit)).toList();
    }

    public record ServerStats(long totalListenSeconds, long totalSongsPlayed, long totalSongsRequested) {
    }

    private static final class StatsSnapshot {
        List<UserStats> users;
        List<SongStats> songs;

        StatsSnapshot(List<UserStats> users, List<SongStats> songs) {
            this.users = users;
            this.songs = songs;
        }
    }
}
