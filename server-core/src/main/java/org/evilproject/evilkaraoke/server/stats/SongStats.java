package org.evilproject.evilkaraoke.server.stats;

public record SongStats(String songId, String title, long timesPlayed, long timesRequested) {
    public SongStats played() {
        return new SongStats(songId, title, timesPlayed + 1, timesRequested);
    }

    public SongStats requested() {
        return new SongStats(songId, title, timesPlayed, timesRequested + 1);
    }
}
