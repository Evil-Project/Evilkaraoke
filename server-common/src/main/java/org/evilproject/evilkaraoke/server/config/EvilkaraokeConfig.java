package org.evilproject.evilkaraoke.server.config;

public record EvilkaraokeConfig(
        String defaultTargets,
        String defaultSource,
        float defaultVolume,
        float defaultPitch,
        float defaultMinVolume,
        int randomCacheSize,
        long pauseBetweenSongsSeconds,
        boolean requireClientMod,
        boolean allowRadio,
        boolean statsEnabled,
        int statsSaveIntervalSeconds,
        boolean debugPackets
    ) {
    public static EvilkaraokeConfig defaults() {
        return new EvilkaraokeConfig("@a", "record", 1.0f, 1.0f, 0.0f, 2, 3L, true, true, true, 60, false);
    }
}
