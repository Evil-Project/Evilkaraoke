package org.evilproject.evilkaraoke.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.Position;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.model.TargetMode;
import org.junit.jupiter.api.Test;

class PlaybackGainTest {
    @Test
    void positionlessUsesBaseVolume() {
        PlaybackTarget target = new PlaybackTarget(TargetMode.ALL, "@a", SoundCategory.MUSIC, null, 0.5f, 1.0f, 0.0f);
        float gain = PlaybackGain.forListener(target, new PlaybackGain.ListenerPosition("world", 0, 0, 0));
        assertEquals(0.5f, gain, 0.0001f);
    }

    @Test
    void distanceAttenuatesTowardMinVolume() {
        Position source = new Position("world", 0, 0, 0);
        PlaybackTarget target = new PlaybackTarget(TargetMode.SELECTOR, "@a", SoundCategory.MUSIC, source, 1.0f, 1.0f, 0.1f);

        float near = PlaybackGain.forListener(target, new PlaybackGain.ListenerPosition("world", 1, 0, 0));
        float far = PlaybackGain.forListener(target, new PlaybackGain.ListenerPosition("world", 100, 0, 0));

        assertTrue(near > far, "closer listener should be louder");
        assertEquals(0.1f, far, 0.0001f, "beyond radius floors at minVolume");
    }

    @Test
    void differentWorldFloorsAtMinVolume() {
        Position source = new Position("world", 0, 0, 0);
        PlaybackTarget target = new PlaybackTarget(TargetMode.SELECTOR, "@a", SoundCategory.MUSIC, source, 1.0f, 1.0f, 0.05f);
        float gain = PlaybackGain.forListener(target, new PlaybackGain.ListenerPosition("nether", 0, 0, 0));
        assertEquals(0.05f, gain, 0.0001f);
    }
}
