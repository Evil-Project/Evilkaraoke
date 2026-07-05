package org.evilproject.evilkaraoke.paper.playback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.model.TargetMode;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.paper.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.paper.config.EvilkaraokeConfig;
import org.evilproject.evilkaraoke.paper.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.paper.messaging.PlaybackMessenger;
import org.evilproject.evilkaraoke.paper.queue.KaraokeSession;

public final class PlaybackCoordinator {
    private static final long BUFFER_TIME_SECONDS = 10L; // Extra time to account for client buffering
    private static final long CLIENT_START_TIMEOUT_SECONDS = 10L;

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

    /** Tracks the current playback ID and which clients have started playing */
    private String currentPlaybackId = null;
    private final Map<UUID, ClientPlaybackState> clientStates = new HashMap<>();
    private boolean timerStarted = false;

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

    public CompletableFuture<Integer> requestAll(List<KaraokeTrack> tracks, Player requester) {
        if (tracks.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        boolean shouldStart = session.current().isEmpty();
        for (KaraokeTrack track : tracks) {
            session.request(track, requester.getUniqueId(), requester.getName());
        }
        if (shouldStart) {
            Bukkit.getScheduler().runTask(plugin, this::playNext);
        }
        return CompletableFuture.completedFuture(tracks.size());
    }

    public void playNext() {
        session.next().ifPresentOrElse(
                queued -> beginPlayback(queued, ""),
                () -> {
                    clearPlaybackTracking();
                    plugin.getLogger().fine("Evilkaraoke queue is empty.");
                });
    }

    private void beginPlayback(KaraokeSession.QueuedTrack queued, String reason) {
        currentPlaybackId = UUID.randomUUID().toString();
        clientStates.clear();
        timerStarted = false;
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                KaraokeSession.GLOBAL_SESSION_ID,
                currentPlaybackId,
                queued.track(),
                audienceTarget(),
                Duration.ZERO,
                Instant.now(),
                reason,
                Duration.ZERO);
        broadcast(packet);
        schedulePlaybackStartFallback(queued.track(), currentPlaybackId);
    }

    private void clearPlaybackTracking() {
        cancelAutoAdvance();
        currentPlaybackId = null;
        clientStates.clear();
        timerStarted = false;
    }

    public void pause() {
        session.pause();
        cancelAutoAdvance();
        broadcastControl(AudioCommandType.PAUSE, "pause");
    }

    public void resume() {
        session.resume();
        if (currentPlaybackId != null) {
            session.current().ifPresent(current -> {
                if (timerStarted) {
                    scheduleAutoAdvance(current.track(), currentPlaybackId);
                } else {
                    schedulePlaybackStartFallback(current.track(), currentPlaybackId);
                }
            });
        }
        broadcastControl(AudioCommandType.RESUME, "resume");
    }

    public void skip() {
        broadcastControl(AudioCommandType.STOP, "skip");
        playNext();
    }

    public void previous() {
        session.previous().ifPresentOrElse(
                queued -> beginPlayback(queued, "previous"),
                () -> plugin.getLogger().fine("No previous track in history."));
    }

    public void stop() {
        session.stop();
        clearPlaybackTracking();
        broadcastControl(AudioCommandType.STOP, "stop");
    }

    /**
     * Handles a status update from a client. When the first client transitions to
     * PLAYING state, starts the session timer so auto-advance happens relative to
     * actual playback rather than the buffering phase.
     */
    public void handleClientStatus(UUID playerId, ClientStatusPacket status) {
        if (currentPlaybackId == null || !currentPlaybackId.equals(status.playbackId())) {
            return;
        }

        clientStates.put(playerId, status.state());

        if (status.state() == ClientPlaybackState.STOPPED && session.snapshot().state() == PlaybackState.PLAYING) {
            playNext();
            return;
        }

        // Start the session timer when the first client actually begins playing.
        if (!timerStarted && status.state() == ClientPlaybackState.PLAYING) {
            session.current().ifPresent(current ->
                    startPlaybackTimer(current.track(), status.playbackId(), "client " + playerId + " began playing"));
        }
    }

    private void startPlaybackTimer(KaraokeTrack track, String playbackId, String reason) {
        KaraokeSession.PlaybackSnapshot snapshot = session.snapshot();
        if (timerStarted
                || currentPlaybackId == null
                || !currentPlaybackId.equals(playbackId)
                || snapshot.current() == null
                || snapshot.state() != PlaybackState.PLAYING) {
            return;
        }
        timerStarted = true;
        session.startTimer();
        scheduleAutoAdvance(track, playbackId);
        plugin.getLogger().fine("Started playback timer after " + reason);
    }

    /**
     * Sends the current playback state to a single player. Called when a player
     * (re)joins with the mod installed so they pick up the song mid-stream rather
     * than missing it until the next track starts.
     *
     * <p>Does nothing if the session is idle or paused — a paused track will be
     * resumed for everyone at once via the normal resume command.
     */
    public void syncPlayer(Player player) {
        KaraokeSession.PlaybackSnapshot snapshot = session.snapshot();
        if (snapshot.current() == null || snapshot.state() != PlaybackState.PLAYING || currentPlaybackId == null) {
            return;
        }
        AudioCommandPacket packet = new AudioCommandPacket(
                AudioCommandType.PLAY,
                KaraokeSession.GLOBAL_SESSION_ID,
                currentPlaybackId,
                snapshot.current().track(),
                audienceTarget(),
                snapshot.offset(),
                Instant.now(),
                "rejoin-sync",
                Duration.ZERO);
        messenger.send(player, packet);
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

    /**
     * Removes a track from the queue at the given position (1-indexed for user display).
     * Returns the removed track if successful, empty otherwise.
     */
    public java.util.Optional<KaraokeSession.QueuedTrack> cancelAt(int position) {
        return session.removeAt(position - 1);
    }

    public List<KaraokeSession.QueuedTrack> cancelAll() {
        return session.removeAllQueued();
    }

    public List<KaraokeSession.QueuedTrack> cancelAllByRequester(UUID requester) {
        return session.removeRequestsByRequester(requester);
    }

    public java.util.Optional<KaraokeSession.QueuedTrack> moveRequest(int fromPosition, int toPosition) {
        return session.moveRequest(fromPosition - 1, toPosition - 1);
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
        String playbackId = currentPlaybackId == null ? "none" : currentPlaybackId;
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

    private void schedulePlaybackStartFallback(KaraokeTrack track, String playbackId) {
        cancelAutoAdvance();
        autoAdvanceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (currentPlaybackId != null && currentPlaybackId.equals(playbackId) && !timerStarted) {
                startPlaybackTimer(track, playbackId, "client start timeout");
            }
        }, Math.max(1L, CLIENT_START_TIMEOUT_SECONDS * 20L)).getTaskId();
    }

    private void scheduleAutoAdvance(KaraokeTrack track, String playbackId) {
        cancelAutoAdvance();
        track.finiteDuration().ifPresent(duration -> {
            Duration remaining = duration.minus(session.snapshot().offset()).plus(Duration.ofSeconds(BUFFER_TIME_SECONDS));
            long delayTicks = ticksCeil(remaining);
            autoAdvanceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (currentPlaybackId != null && currentPlaybackId.equals(playbackId)) {
                    playNext();
                }
            }, delayTicks).getTaskId();
        });
    }

    private static long ticksCeil(Duration duration) {
        long millis = Math.max(1L, duration.toMillis());
        return Math.max(1L, (millis + 49L) / 50L);
    }

    private void cancelAutoAdvance() {
        if (autoAdvanceTask != -1) {
            Bukkit.getScheduler().cancelTask(autoAdvanceTask);
            autoAdvanceTask = -1;
        }
    }
}
