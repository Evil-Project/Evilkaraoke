package org.evilproject.evilkaraoke.paper.playback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.model.TargetMode;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.config.EvilkaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.messaging.PlaybackMessenger;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;

public final class PlaybackCoordinator {
    private final Plugin plugin;
    private final ClientRegistry clientRegistry;
    private final PlaybackMessenger messenger;
    private final KaraokeSession session = new KaraokeSession();
    private NeurokaraokeClient neurokaraokeClient;
    private EvilkaraokeConfig config;
    private int autoAdvanceTask = -1;

    private TargetMode audienceMode = TargetMode.ALL;
    private UUID audiencePlayer;
    private String audienceLabel = "@a";

    public PlaybackCoordinator(Plugin plugin, ClientRegistry clientRegistry, PlaybackMessenger messenger, NeurokaraokeClient neurokaraokeClient, EvilkaraokeConfig config) {
        this.plugin = plugin;
        this.clientRegistry = clientRegistry;
        this.messenger = messenger;
        this.neurokaraokeClient = neurokaraokeClient;
        this.config = config;
        this.audienceLabel = config.defaultTargets();
        this.audienceMode = "@a".equals(config.defaultTargets()) ? TargetMode.ALL : TargetMode.SELECTOR;
    }

    public void update(NeurokaraokeClient neurokaraokeClient, EvilkaraokeConfig config) {
        this.neurokaraokeClient = neurokaraokeClient;
        this.config = config;
    }

    public CompletableFuture<KaraokeTrack> requestSearch(String query, Player requester) {
        return neurokaraokeClient.search(query).thenCompose(results -> {
            if (results.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("No Neurokaraoke results for: " + query));
            }
            KaraokeTrack track = results.getFirst();
            return request(track, requester).thenApply(ignored -> track);
        });
    }

    public CompletableFuture<KaraokeTrack> requestRandom(Player requester) {
        return neurokaraokeClient.randomSong().thenCompose(track -> request(track, requester).thenApply(ignored -> track));
    }

    public CompletableFuture<KaraokeTrack> requestRadio(String station, Player requester) {
        return neurokaraokeClient.radio(station).thenCompose(track -> request(track, requester).thenApply(ignored -> track));
    }

    public CompletableFuture<Void> request(KaraokeTrack track, Player requester) {
        session.request(track, requester.getUniqueId(), requester.getName());
        if (session.current().isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, this::playNext);
        }
        return CompletableFuture.completedFuture(null);
    }

    public void playNext() {
        session.next().ifPresentOrElse(queued -> {
            String playbackId = UUID.randomUUID().toString();
            AudioCommandPacket packet = new AudioCommandPacket(
                    AudioCommandType.PLAY,
                    KaraokeSession.GLOBAL_SESSION_ID,
                    playbackId,
                    queued.track(),
                    audienceTarget(),
                    Duration.ZERO,
                    Instant.now(),
                    "",
                    Duration.ZERO);
            broadcast(packet);
            scheduleAutoAdvance(queued.track());
        }, () -> plugin.getLogger().fine("Evilkaraoke queue is empty."));
    }

    public void pause() {
        session.pause();
        broadcastControl(AudioCommandType.PAUSE, "pause");
    }

    public void resume() {
        session.resume();
        broadcastControl(AudioCommandType.RESUME, "resume");
    }

    public void skip() {
        broadcastControl(AudioCommandType.STOP, "skip");
        playNext();
    }

    public void stop() {
        session.stop();
        cancelAutoAdvance();
        broadcastControl(AudioCommandType.STOP, "stop");
    }

    public void setAudienceAll() {
        this.audienceMode = TargetMode.ALL;
        this.audiencePlayer = null;
        this.audienceLabel = "@a";
    }

    public void setAudiencePlayer(UUID playerId, String label) {
        this.audienceMode = TargetMode.PLAYER;
        this.audiencePlayer = playerId;
        this.audienceLabel = label;
    }

    public String audienceLabel() {
        return audienceLabel;
    }

    public KaraokeSession.PlaybackSnapshot snapshot() {
        return session.snapshot();
    }

    public Collection<KaraokeSession.QueuedTrack> queue() {
        return session.queuedTracks();
    }

    private PlaybackTarget audienceTarget() {
        String selector = audienceMode == TargetMode.PLAYER && audiencePlayer != null ? audiencePlayer.toString() : audienceLabel;
        return new PlaybackTarget(
                audienceMode,
                selector,
                soundCategory(),
                null,
                config.defaultVolume(),
                config.defaultPitch(),
                config.defaultMinVolume());
    }

    private SoundCategory soundCategory() {
        try {
            return SoundCategory.valueOf(config.defaultSource().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SoundCategory.MUSIC;
        }
    }

    private void broadcastControl(AudioCommandType type, String reason) {
        KaraokeSession.PlaybackSnapshot snapshot = session.snapshot();
        String playbackId = snapshot.current() == null ? "none" : snapshot.current().track().id();
        broadcast(new AudioCommandPacket(
                type,
                KaraokeSession.GLOBAL_SESSION_ID,
                playbackId,
                snapshot.current() == null ? null : snapshot.current().track(),
                audienceTarget(),
                snapshot.offset(),
                Instant.now(),
                reason,
                Duration.ZERO));
    }

    private void broadcast(AudioCommandPacket packet) {
        for (Player player : recipients()) {
            if (!config.requireClientMod() || clientRegistry.session(player.getUniqueId()).isPresent()) {
                messenger.send(player, packet);
            }
        }
    }

    private List<Player> recipients() {
        if (audienceMode == TargetMode.PLAYER && audiencePlayer != null) {
            Player target = Bukkit.getPlayer(audiencePlayer);
            return target == null ? List.of() : List.of(target);
        }
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    private void scheduleAutoAdvance(KaraokeTrack track) {
        cancelAutoAdvance();
        track.finiteDuration().ifPresent(duration ->
                autoAdvanceTask = Bukkit.getScheduler().runTaskLater(plugin, this::playNext, Math.max(1L, duration.toSeconds() * 20L)).getTaskId());
    }

    private void cancelAutoAdvance() {
        if (autoAdvanceTask != -1) {
            Bukkit.getScheduler().cancelTask(autoAdvanceTask);
            autoAdvanceTask = -1;
        }
    }
}
