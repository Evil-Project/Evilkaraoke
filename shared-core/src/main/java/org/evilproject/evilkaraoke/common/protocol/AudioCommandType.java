package org.evilproject.evilkaraoke.common.protocol;

public enum AudioCommandType {
    PLAY,
    PAUSE,
    RESUME,
    STOP,
    SYNC,
    VOLUME,
    /** Asks the receiving client to update its lyric caption display. */
    LYRICS
}
