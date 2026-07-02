package org.evilproject.evilkaraoke.neoforge;

import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.evilproject.evilkaraoke.client.net.ClientAudioController;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;

/**
 * NeoForge client entrypoint. Registers Evilkaraoke's payload channels and hands
 * bytes to the shared {@link ClientAudioController}. Audio playback only; all
 * command/queue logic stays on the server.
 */
@Mod(value = "evilkaraoke", dist = Dist.CLIENT)
public final class EvilkaraokeNeoForgeClient {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");
    private static final String MOD_VERSION = "0.1.0";

    private final EvilkaraokePayload.Type<EvilkaraokePayload> audioType = EvilkaraokePayload.type(EvilkaraokeProtocol.AUDIO_CHANNEL);
    private final EvilkaraokePayload.Type<EvilkaraokePayload> helloType = EvilkaraokePayload.type(EvilkaraokeProtocol.HELLO_CHANNEL);
    private final EvilkaraokePayload.Type<EvilkaraokePayload> statusType = EvilkaraokePayload.type(EvilkaraokeProtocol.STATUS_CHANNEL);

    private ClientAudioController controller;

    public EvilkaraokeNeoForgeClient(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        // Stop audio when the player disconnects from a server.
        NeoForge.EVENT_BUS.addListener(this::onLogout);

        String minecraftVersion = container.getModInfo().getVersion().toString();
        controller = new ClientAudioController(LOGGER, MOD_VERSION, minecraftVersion, "neoforge");

        // Show the vanilla music toast whenever a karaoke track starts playing.
        controller.setOnPlay(this::showMusicToast);

        LOGGER.info("Evilkaraoke NeoForge client constructed (audio-only).");
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(audioType, EvilkaraokePayload.codec(audioType), this::handleAudio);
        registrar.playToServer(helloType, EvilkaraokePayload.codec(helloType), (payload, context) -> {
        });
        registrar.playToServer(statusType, EvilkaraokePayload.codec(statusType), (payload, context) -> {
        });
    }

    private void handleAudio(EvilkaraokePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> controller.handleAudioPayload(payload.data()));
    }

    private void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (Minecraft.getInstance().getConnection() != null) {
            EvilkaraokePayload payload = new EvilkaraokePayload(helloType, controller.helloPayload());
            Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
            LOGGER.info("Sent Evilkaraoke hello handshake to server.");
        }
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Stop any active playback so the audio thread does not outlive the
        // play session when the player disconnects or quits to the main menu.
        controller.stopAll();
        LOGGER.info("Evilkaraoke stopped playback on server disconnect.");
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
