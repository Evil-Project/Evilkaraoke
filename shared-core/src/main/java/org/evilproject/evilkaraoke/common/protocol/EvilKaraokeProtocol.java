package org.evilproject.evilkaraoke.common.protocol;

public final class EvilKaraokeProtocol {
    public static final int VERSION = 1;
    public static final String NAMESPACE = "evilkaraoke";
    public static final String HELLO_CHANNEL = NAMESPACE + ":hello";
    public static final String AUDIO_CHANNEL = NAMESPACE + ":audio";
    public static final String STATUS_CHANNEL = NAMESPACE + ":status";

    private EvilKaraokeProtocol() {
    }
}
