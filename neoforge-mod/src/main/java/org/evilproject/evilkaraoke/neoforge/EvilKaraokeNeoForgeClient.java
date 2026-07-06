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
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;

/**
 * NeoForge client entrypoint. Registers Evilkaraoke's payload channels and hands
 * bytes to the shared {@link ClientAudioController}. Audio playback only; all
 * command/queue logic stays on the server.
 */
public final class EvilKaraokeNeoForgeClient {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");
    private static final String MOD_VERSION = "0.1.1";
    private static final int STATUS_REPORT_INTERVAL_TICKS = 20; // Report status every second
    private static final int HELLO_RETRY_TICKS = 100;

    private final EvilKaraokePayload.Type<EvilKaraokePayload> audioType;
    private final EvilKaraokePayload.Type<EvilKaraokePayload> helloType;
    private final EvilKaraokePayload.Type<EvilKaraokePayload> statusType;

    private ClientAudioController controller;
    private int tickCounter = 0;
    private int helloRetryTicks = 0;
    private boolean backgroundAudioMuted = false;

    public static void init(IEventBus modBus, ModContainer container,
                            EvilKaraokePayload.Type<EvilKaraokePayload> audioType,
                            EvilKaraokePayload.Type<EvilKaraokePayload> helloType,
                            EvilKaraokePayload.Type<EvilKaraokePayload> statusType) {
        new EvilKaraokeNeoForgeClient(modBus, container, audioType, helloType, statusType);
    }

    private EvilKaraokeNeoForgeClient(IEventBus modBus, ModContainer container,
                                      EvilKaraokePayload.Type<EvilKaraokePayload> audioType,
                                      EvilKaraokePayload.Type<EvilKaraokePayload> helloType,
                                      EvilKaraokePayload.Type<EvilKaraokePayload> statusType) {
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

    private void handleAudio(EvilKaraokePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> controller.handleAudioPayload(payload.data()));
    }

    private void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        helloRetryTicks = HELLO_RETRY_TICKS;
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Stop any active playback so the audio thread does not outlive the
        // play session when the player disconnects or quits to the main menu.
        helloRetryTicks = 0;
        controller.stopAll();
        restoreMinecraftBackgroundAudio(Minecraft.getInstance());
        LOGGER.info("Evilkaraoke stopped playback on server disconnect.");
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) {
            controller.setGameVolume(mc.options.getFinalSoundSourceVolume(soundSource(controller.soundCategory())));
            updateMinecraftBackgroundAudioMute(mc);
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
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        EvilKaraokePayload payload = new EvilKaraokePayload(helloType, controller.helloPayload());
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(payload));
        helloRetryTicks = 0;
        LOGGER.info("Sent Evilkaraoke hello handshake to server.");
    }

    private void sendStatusUpdate() {
        if (Minecraft.getInstance().getConnection() != null) {
            EvilKaraokePayload payload = new EvilKaraokePayload(statusType, controller.statusPayload());
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

    private void updateMinecraftBackgroundAudioMute(Minecraft mc) {
        if (controller.isPlaybackSessionActive()) {
            mc.getSoundManager().updateCategoryVolume(SoundSource.MUSIC, 0.0f);
            mc.getSoundManager().updateCategoryVolume(SoundSource.RECORDS, 0.0f);
            backgroundAudioMuted = true;
        } else if (backgroundAudioMuted) {
            restoreMinecraftBackgroundAudio(mc);
        }
    }

    private void restoreMinecraftBackgroundAudio(Minecraft mc) {
        if (!backgroundAudioMuted || mc == null || mc.options == null) {
            return;
        }
        // Restore to the player's configured slider values, not a hardcoded 1.0f,
        // otherwise ending a karaoke track resets the user's music/records volume
        // to full every time.
        mc.getSoundManager().updateCategoryVolume(
                SoundSource.MUSIC, mc.options.getSoundSourceVolume(SoundSource.MUSIC));
        mc.getSoundManager().updateCategoryVolume(
                SoundSource.RECORDS, mc.options.getSoundSourceVolume(SoundSource.RECORDS));
        backgroundAudioMuted = false;
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
