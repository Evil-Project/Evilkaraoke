package org.evilproject.evilkaraoke.client.audio;

import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.Position;

/**
 * Translates the server's {@link PlaybackTarget} into a linear gain, mirroring
 * vanilla {@code /playsound} semantics:
 * <ul>
 *   <li>base gain is the packet volume clamped to [0, 1] for the mixer;</li>
 *   <li>when a world position is supplied, gain attenuates with distance up to
 *       the volume-derived radius, then floors at {@code minVolume};</li>
 *   <li>with no position (positionless like {@code /playsound} volume &ge; 1) the
 *       base gain is used everywhere.</li>
 * </ul>
 * This keeps the "all players vs a single player" decision on the server (packet
 * routing) while the client reproduces the same volume falloff a jukebox/sound
 * would have.
 */
public final class PlaybackGain {
    private PlaybackGain() {
    }

    public static float forListener(PlaybackTarget target, ListenerPosition listener) {
        float base = clamp01(target.volume());
        Position source = target.position();
        if (source == null || listener == null || !listener.worldKey().equals(source.worldKey())) {
            return source == null ? base : clamp01(target.minVolume());
        }
        double dx = listener.x() - source.x();
        double dy = listener.y() - source.y();
        double dz = listener.z() - source.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double radius = Math.max(1.0, target.volume() * 16.0);
        if (distance >= radius) {
            return clamp01(target.minVolume());
        }
        float attenuated = (float) (base * (1.0 - distance / radius));
        return Math.max(clamp01(target.minVolume()), clamp01(attenuated));
    }

    private static float clamp01(float value) {
        if (value < 0.0f) {
            return 0.0f;
        }
        return Math.min(value, 1.0f);
    }

    public record ListenerPosition(String worldKey, double x, double y, double z) {
    }
}
