package org.evilproject.evilkaraoke.server.stats;

import java.util.UUID;

public record UserStats(UUID playerId, String playerName, long listenSeconds, long songsListened, long songsRequested) {
    public UserStats withListen(long addedSeconds, long addedSongs) {
        return new UserStats(playerId, playerName, listenSeconds + addedSeconds, songsListened + addedSongs, songsRequested);
    }

    public UserStats withRequest() {
        return new UserStats(playerId, playerName, listenSeconds, songsListened, songsRequested + 1);
    }
}
