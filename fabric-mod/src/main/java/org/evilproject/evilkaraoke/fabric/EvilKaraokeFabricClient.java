package org.evilproject.evilkaraoke.fabric;

import java.util.logging.Logger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.sounds.SoundSource;
import org.evilproject.evilkaraoke.client.config.EvilKaraokeClientConfig;
import org.evilproject.evilkaraoke.client.net.ClientAudioController;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.protocol.EvilKaraokeProtocol;

/**
 * Fabric client entrypoint. Registers Evilkaraoke's custom payload channels and
 * forwards raw bytes to the loader-neutral {@link ClientAudioController}. The mod
 * handles local audio and timed lyric captions without inspecting queue or
 * command state, preserving the server-authoritative design.
 */
public final class EvilKaraokeFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = Logger.getLogger("Evilkaraoke");
    private static final int STATUS_REPORT_INTERVAL_TICKS = 20; // Report status every second
    private static final int HELLO_RETRY_TICKS = 100;

    private final CustomPacketPayload.Type<EvilKaraokePayload> helloType = EvilKaraokePayload.type(EvilKaraokeProtocol.HELLO_CHANNEL);
    private final CustomPacketPayload.Type<EvilKaraokePayload> audioType = EvilKaraokePayload.type(EvilKaraokeProtocol.AUDIO_CHANNEL);
    private final CustomPacketPayload.Type<EvilKaraokePayload> statusType = EvilKaraokePayload.type(EvilKaraokeProtocol.STATUS_CHANNEL);

    private ClientAudioController controller;
    private EvilKaraokeClientConfig clientConfig;
    private KaraokeNowPlayingToast musicToast;
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
        clientConfig = EvilKaraokeClientConfig.load(FabricLoader.getInstance().getConfigDir(), LOGGER);
        controller = new ClientAudioController(LOGGER, modVersion, minecraftVersion, "fabric");
        controller.setLyricsEnabled(clientConfig.lyricsEnabled());

        // Timed lyric lines use the same HUD area as `/title ... actionbar`.
        controller.setOnPlay(track -> {
            KaraokeLyricActionBar.hide();
            showMusicToast(track);
        });
        controller.setOnLyric(KaraokeLyricActionBar::showLyric);
        controller.setOnLyricClear(KaraokeLyricActionBar::hide);
        controller.setOnLyricsToggled(this::onLyricsToggled);

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
            KaraokeLyricActionBar.hide();
            hideMusicToast(client);
            restoreMinecraftBackgroundAudio(client);
            LOGGER.info("Evilkaraoke stopped playback on server disconnect.");
        });

        LOGGER.info("Evilkaraoke Fabric client initialized (audio and timed captions).");
    }

    private void updateMinecraftVolume(Minecraft client) {
        if (client.options != null) {
            controller.setGameVolume(client.options.getFinalSoundSourceVolume(soundSource(controller.soundCategory())));
            updateMinecraftBackgroundAudioMute(client);
        }
        controller.tickLyrics();
        KaraokeLyricActionBar.tick(controller.isPlaybackSessionActive());

        sendPendingHello();

        // Send status updates periodically while playing
        tickCounter++;
        if (tickCounter >= STATUS_REPORT_INTERVAL_TICKS) {
            tickCounter = 0;
            sendStatusUpdate();
        }
    }

    private void onLyricsToggled(boolean enabled) {
        clientConfig.setLyricsEnabled(enabled);
        if (!enabled) {
            KaraokeLyricActionBar.hide();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(enabled
                    ? "Evilkaraoke lyrics enabled."
                    : "Evilkaraoke lyrics disabled."));
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

    private void showMusicToast(KaraokeTrack track) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        hideMusicToast(minecraft);
        if (track == null) {
            return;
        }
        musicToast = new KaraokeNowPlayingToast(Component.literal(track.artist() + " - " + track.title()));
        minecraft.gui.toastManager().addToast(musicToast);
    }

    private void hideMusicToast(Minecraft minecraft) {
        if (musicToast != null) {
            musicToast.hide();
            musicToast = null;
        }
    }
}
