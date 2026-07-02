package org.evilproject.evilkaraoke.fabric;

import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.evilproject.evilkaraoke.client.net.ClientAudioController;
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

        PayloadTypeRegistry.playS2C().register(audioType, EvilkaraokePayload.codec(audioType));
        PayloadTypeRegistry.playC2S().register(helloType, EvilkaraokePayload.codec(helloType));
        PayloadTypeRegistry.playC2S().register(statusType, EvilkaraokePayload.codec(statusType));

        ClientPlayNetworking.registerGlobalReceiver(audioType, (payload, context) ->
                context.client().execute(() -> controller.handleAudioPayload(payload.data())));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (ClientPlayNetworking.canSend(helloType)) {
                ClientPlayNetworking.send(new EvilkaraokePayload(helloType, controller.helloPayload()));
                LOGGER.info("Sent Evilkaraoke client handshake to server.");
            }
        });

        LOGGER.info("Evilkaraoke Fabric client initialized (audio-only).");
    }
}
