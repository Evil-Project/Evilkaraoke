package org.evilproject.evilkaraoke.server.api;

import java.time.Duration;
import java.util.List;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;

public record NeurokaraokeSetlist(String id, String name, int songCount, Duration totalDuration, List<KaraokeTrack> songs) {
    public NeurokaraokeSetlist(String id, String name, int songCount, Duration totalDuration) {
        this(id, name, songCount, totalDuration, List.of());
    }

    public NeurokaraokeSetlist {
        id = id == null || id.isBlank() ? name : id;
        name = name == null || name.isBlank() ? "Untitled Setlist" : name;
        songCount = Math.max(0, songCount);
        songs = songs == null ? List.of() : List.copyOf(songs);
    }
}
