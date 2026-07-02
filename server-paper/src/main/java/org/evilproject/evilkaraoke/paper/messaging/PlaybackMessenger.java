package org.evilproject.evilkaraoke.paper.messaging;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;

public final class PlaybackMessenger {
    private final Plugin plugin;
    private final JsonPacketCodec codec;

    public PlaybackMessenger(Plugin plugin, JsonPacketCodec codec) {
        this.plugin = plugin;
        this.codec = codec;
    }

    public void send(Player player, AudioCommandPacket packet) {
        player.sendPluginMessage(plugin, EvilkaraokeProtocol.AUDIO_CHANNEL, codec.encode(packet));
    }
}
