package org.evilproject.evilkaraoke.fabric;

import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.evilproject.evilkaraoke.client.net.ClientAudioController;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;

/**
 * Fabric client entrypoint. Registers Evilkaraoke's custom payload channels and
 * forwards raw bytes to the loader-neutral {@link ClientAudioController}. The mod
 * only plays audio: it never inspects queue or command state, matching the
 * server-authoritative design and requirement that clients are audio-only.
 */
public final class EvilkaraokeFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");
    private static final String MOD_VERSION = "0.1.0";

    private final CustomPacketPayload.Type<EvilkaraokePayload> helloType = EvilkaraokePayload.type(EvilkaraokeProtocol.HELLO_CHANNEL);
    private final CustomPacketPayload.Type<EvilkaraokePayload> audioType = EvilkaraokePayload.type(EvilkaraokeProtocol.AUDIO_CHANNEL);
    private final CustomPacketPayload.Type<EvilkaraokePayload> statusType = EvilkaraokePayload.type(EvilkaraokeProtocol.STATUS_CHANNEL);

    private ClientAudioController controller;

    @Override
    public void onInitializeClient() {
        String minecraftVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        controller = new ClientAudioController(LOGGER, MOD_VERSION, minecraftVersion, "fabric");

        // Show the vanilla music toast whenever a karaoke track starts playing.
        controller.setOnPlay(this::showMusicToast);

        PayloadTypeRegistry.clientboundPlay().register(audioType, EvilkaraokePayload.codec(audioType));
        PayloadTypeRegistry.serverboundPlay().register(helloType, EvilkaraokePayload.codec(helloType));
        PayloadTypeRegistry.serverboundPlay().register(statusType, EvilkaraokePayload.codec(statusType));

        ClientPlayNetworking.registerGlobalReceiver(audioType, (payload, context) ->
                context.client().execute(() -> controller.handleAudioPayload(payload.data())));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Send unconditionally — if the server is running Evilkaraoke it will
            // handle the packet; if not, the server silently ignores unknown channels.
            // A canSend() guard would silently drop the hello when the server's
            // known-channels packet hasn't arrived yet, preventing audio delivery.
            ClientPlayNetworking.send(new EvilkaraokePayload(helloType, controller.helloPayload()));
            LOGGER.info("Sent Evilkaraoke client handshake to server.");
        });

        // Stop audio immediately when the player leaves a server so the playback
        // thread does not outlive the play session.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            controller.stopAll();
            LOGGER.info("Evilkaraoke stopped playback on server disconnect.");
        });

        LOGGER.info("Evilkaraoke Fabric client initialized (audio-only).");
    }

    /**
     * Surfaces the playing track as the vanilla music toast so players see the
     * same "now playing" notification they get from jukeboxes.
     */
    private void showMusicToast(KaraokeTrack track) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        Component title = Component.literal(track.title());
        Component artist = Component.literal(track.artist());
        mc.execute(() -> SystemToast.add(
                mc.gui.toastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                title,
                artist));
    }
}
