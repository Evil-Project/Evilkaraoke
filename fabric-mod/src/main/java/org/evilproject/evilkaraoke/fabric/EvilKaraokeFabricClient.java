package org.evilproject.evilkaraoke.fabric;

import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.sounds.SoundSource;
import org.evilproject.evilkaraoke.client.net.ClientAudioController;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;

/**
 * Fabric client entrypoint. Registers Evilkaraoke's custom payload channels and
 * forwards raw bytes to the loader-neutral {@link ClientAudioController}. The mod
 * only plays audio: it never inspects queue or command state, matching the
 * server-authoritative design and requirement that clients are audio-only.
 */
public final class EvilKaraokeFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");
    private static final int STATUS_REPORT_INTERVAL_TICKS = 20; // Report status every second
    private static final int HELLO_RETRY_TICKS = 100;

    private final CustomPacketPayload.Type<EvilKaraokePayload> helloType = EvilKaraokePayload.type(EvilKaraokeProtocol.HELLO_CHANNEL);
    private final CustomPacketPayload.Type<EvilKaraokePayload> audioType = EvilKaraokePayload.type(EvilKaraokeProtocol.AUDIO_CHANNEL);
    private final CustomPacketPayload.Type<EvilKaraokePayload> statusType = EvilKaraokePayload.type(EvilKaraokeProtocol.STATUS_CHANNEL);

    private ClientAudioController controller;
    private int tickCounter = 0;
    private int helloRetryTicks = 0;
    private boolean backgroundAudioMuted = false;

    @Override
    public void onInitializeClient() {
        String modVersion = FabricLoader.getInstance()
                .getModContainer("evilkaraoke")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        String minecraftVersion = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        controller = new ClientAudioController(LOGGER, modVersion, minecraftVersion, "fabric");

        // Show the vanilla music toast whenever a karaoke track starts playing.
        controller.setOnPlay(this::showMusicToast);

        ClientPlayNetworking.registerGlobalReceiver(audioType, (payload, context) ->
                context.client().execute(() -> controller.handleAudioPayload(payload.data())));

        ClientTickEvents.END_CLIENT_TICK.register(this::updateMinecraftVolume);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            helloRetryTicks = HELLO_RETRY_TICKS;
        });

        // Stop audio immediately when the player leaves a server so the playback
        // thread does not outlive the play session.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            helloRetryTicks = 0;
            controller.stopAll();
            restoreMinecraftBackgroundAudio(client);
            LOGGER.info("Evilkaraoke stopped playback on server disconnect.");
        });

        LOGGER.info("Evilkaraoke Fabric client initialized (audio-only).");
    }

    private void updateMinecraftVolume(Minecraft client) {
        if (client.options != null) {
            controller.setGameVolume(client.options.getFinalSoundSourceVolume(soundSource(controller.soundCategory())));
            updateMinecraftBackgroundAudioMute(client);
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
        if (!ClientPlayNetworking.canSend(helloType)) {
            return;
        }
        ClientPlayNetworking.send(new EvilKaraokePayload(helloType, controller.helloPayload()));
        helloRetryTicks = 0;
        LOGGER.info("Sent Evilkaraoke client handshake to server.");
    }

    private void sendStatusUpdate() {
        if (ClientPlayNetworking.canSend(statusType)) {
            ClientPlayNetworking.send(new EvilKaraokePayload(statusType, controller.statusPayload()));
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

    private void updateMinecraftBackgroundAudioMute(Minecraft client) {
        if (controller.isPlaybackSessionActive()) {
            client.getSoundManager().updateCategoryVolume(SoundSource.MUSIC, 0.0f);
            client.getSoundManager().updateCategoryVolume(SoundSource.RECORDS, 0.0f);
            backgroundAudioMuted = true;
        } else if (backgroundAudioMuted) {
            restoreMinecraftBackgroundAudio(client);
        }
    }

    private void restoreMinecraftBackgroundAudio(Minecraft client) {
        if (!backgroundAudioMuted || client == null || client.options == null) {
            return;
        }
        // Restore to the player's configured slider values, not a hardcoded 1.0f,
        // otherwise ending a karaoke track resets the user's music/records volume
        // to full every time.
        client.getSoundManager().updateCategoryVolume(
                SoundSource.MUSIC, client.options.getSoundSourceVolume(SoundSource.MUSIC));
        client.getSoundManager().updateCategoryVolume(
                SoundSource.RECORDS, client.options.getSoundSourceVolume(SoundSource.RECORDS));
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
