package org.evilproject.evilkaraoke.neoforge;

import java.util.logging.Logger;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.evilproject.evilkaraoke.client.net.ClientAudioController;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.protocol.EvilkaraokeProtocol;

/**
 * NeoForge client entrypoint. Registers Evilkaraoke's payload channels and hands
 * bytes to the shared {@link ClientAudioController}. Audio playback only; all
 * command/queue logic stays on the server.
 */
public final class EvilkaraokeNeoForgeClient {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");
    private static final String MOD_VERSION = "0.1.1";
    private static final int STATUS_REPORT_INTERVAL_TICKS = 20; // Report status every second
    private static final int HELLO_RETRY_TICKS = 100;
    private static final int HELLO_RETRY_INTERVAL_TICKS = 20;

    private final EvilkaraokePayload.Type<EvilkaraokePayload> audioType;
    private final EvilkaraokePayload.Type<EvilkaraokePayload> helloType;
    private final EvilkaraokePayload.Type<EvilkaraokePayload> statusType;

    private ClientAudioController controller;
    private int tickCounter = 0;
    private int helloRetryTicks = 0;
    private int helloRetryCooldown = 0;

    public static void init(IEventBus modBus, ModContainer container,
                            EvilkaraokePayload.Type<EvilkaraokePayload> audioType,
                            EvilkaraokePayload.Type<EvilkaraokePayload> helloType,
                            EvilkaraokePayload.Type<EvilkaraokePayload> statusType) {
        new EvilkaraokeNeoForgeClient(modBus, container, audioType, helloType, statusType);
    }

    private EvilkaraokeNeoForgeClient(IEventBus modBus, ModContainer container,
                                      EvilkaraokePayload.Type<EvilkaraokePayload> audioType,
                                      EvilkaraokePayload.Type<EvilkaraokePayload> helloType,
                                      EvilkaraokePayload.Type<EvilkaraokePayload> statusType) {
        this.audioType = audioType;
        this.helloType = helloType;
        this.statusType = statusType;

        modBus.addListener(this::registerClientPayloads);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        // Stop audio when the player disconnects from a server.
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);

        String minecraftVersion = SharedConstants.getCurrentVersion().name();
        controller = new ClientAudioController(LOGGER, MOD_VERSION, minecraftVersion, "neoforge");

        // Show the vanilla music toast whenever a karaoke track starts playing.
        controller.setOnPlay(this::showMusicToast);

        LOGGER.info("Evilkaraoke NeoForge client constructed (audio-only).");
    }

    private void registerClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(audioType, this::handleAudio);
    }

    private void handleAudio(EvilkaraokePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> controller.handleAudioPayload(payload.data()));
    }

    private void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        helloRetryTicks = HELLO_RETRY_TICKS;
        helloRetryCooldown = 0;
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Stop any active playback so the audio thread does not outlive the
        // play session when the player disconnects or quits to the main menu.
        helloRetryTicks = 0;
        helloRetryCooldown = 0;
        controller.stopAll();
        LOGGER.info("Evilkaraoke stopped playback on server disconnect.");
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            controller.setGameVolume(mc.options.getFinalSoundSourceVolume(soundSource(controller.soundCategory())));
        }

        sendPendingHello();

        // Send status updates periodically while playing
        tickCounter++;
        if (tickCounter >= STATUS_REPORT_INTERVAL_TICKS) {
            tickCounter = 0;
            sendStatusUpdate();
        }
    }

    private void sendPendingHello() {
        if (helloRetryTicks <= 0) {
            return;
        }
        helloRetryTicks--;
        if (helloRetryCooldown > 0) {
            helloRetryCooldown--;
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        EvilkaraokePayload payload = new EvilkaraokePayload(helloType, controller.helloPayload());
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        helloRetryCooldown = HELLO_RETRY_INTERVAL_TICKS;
        LOGGER.info("Sent Evilkaraoke hello handshake to server.");
    }

    private void sendStatusUpdate() {
        if (Minecraft.getInstance().getConnection() != null) {
            EvilkaraokePayload payload = new EvilkaraokePayload(statusType, controller.statusPayload());
            Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        }
    }

    private static SoundSource soundSource(SoundCategory category) {
        return switch (category) {
            case MASTER -> SoundSource.MASTER;
            case MUSIC -> SoundSource.MUSIC;
            case RECORD -> SoundSource.RECORDS;
            case WEATHER -> SoundSource.WEATHER;
            case BLOCK -> SoundSource.BLOCKS;
            case HOSTILE -> SoundSource.HOSTILE;
            case NEUTRAL -> SoundSource.NEUTRAL;
            case PLAYER -> SoundSource.PLAYERS;
            case AMBIENT -> SoundSource.AMBIENT;
            case VOICE -> SoundSource.VOICE;
        };
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
