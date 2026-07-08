package org.evilproject.evilkaraoke.paper.messaging;

import java.util.function.BooleanSupplier;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.evilproject.evilkaraoke.common.codec.JsonPacketCodec;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;
import org.evilproject.evilkaraoke.common.protocol.PacketDebugFormatter;
import org.evilproject.evilkaraoke.common.protocol.ProtocolPacket;

public final class PlaybackMessenger {
    private final Plugin plugin;
    private final JsonPacketCodec codec;
    private final BooleanSupplier debugPackets;

    public PlaybackMessenger(Plugin plugin, JsonPacketCodec codec) {
        this(plugin, codec, () -> false);
    }

    public PlaybackMessenger(Plugin plugin, JsonPacketCodec codec, BooleanSupplier debugPackets) {
        this.plugin = plugin;
        this.codec = codec;
        this.debugPackets = debugPackets;
    }

    public void send(Player player, ProtocolPacket packet) {
        if (debugPackets.getAsBoolean()) {
            plugin.getLogger().info("Evilkaraoke debug packet OUT " + EvilKaraokeProtocol.AUDIO_CHANNEL + " to " + player.getName() + ": " + PacketDebugFormatter.describe(packet));
        }
        player.sendPluginMessage(plugin, EvilKaraokeProtocol.AUDIO_CHANNEL, codec.encode(packet));
    }
}
