package org.evilproject.evilkaraoke.server.playback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.model.PlaybackState;
import org.evilproject.evilkaraoke.common.model.PlaybackTarget;
import org.evilproject.evilkaraoke.common.model.SoundCategory;
import org.evilproject.evilkaraoke.common.model.TargetMode;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;
import org.evilproject.evilkaraoke.common.protocol.ClientStatusPacket;
import org.evilproject.evilkaraoke.server.api.NeurokaraokeClient;
import org.evilproject.evilkaraoke.server.config.EvilkaraokeConfig;
import org.evilproject.evilkaraoke.server.messaging.ClientRegistry;
import org.evilproject.evilkaraoke.server.platform.KaraokePlayer;
import org.evilproject.evilkaraoke.server.platform.ServerPlaybackPlatform;
import org.evilproject.evilkaraoke.server.queue.KaraokeSession;

public final class PlaybackCoordinator {
    private static final long BUFFER_TIME_SECONDS = 10L;
    private static final long CLIENT_START_TIMEOUT_SECONDS = 10L;

    private final ServerPlaybackPlatform platform;
    private final ClientRegistry clientRegistry;
    private final KaraokeSession session = new KaraokeSession();
    private NeurokaraokeClient neurokaraokeClient;
    private EvilkaraokeConfig config;
    private int autoAdvanceTask = -1;

    private TargetMode audienceMode = TargetMode.ALL;
    private UUID audiencePlayer;
    private String audienceLabel = "@a";
    private String currentPlaybackId;
    private final Map<UUID, ClientPlaybackState> clientStates = new HashMap<>();
    private boolean timerStarted = false;

    public PlaybackCoordinator(ServerPlaybackPlatform platform, ClientRegistry clientRegistry, NeurokaraokeClient neurokaraokeClient, EvilkaraokeConfig config) {
        this.platform = platform;
        this.clientRegistry = clientRegistry;
        this.neurokaraokeClient = neurokaraokeClient;
        this.config = config;
        this.audienceLabel = config.defaultTargets();
        this.audienceMode = "@a".equals(config.defaultTargets()) ? TargetMode.ALL : TargetMode.SELECTOR;
    }

    public void update(NeurokaraokeClient neurokaraokeClient, EvilkaraokeConfig config) {
        this.neurokaraokeClient = neurokaraokeClient;
        this.config = config;
    }

    public CompletableFuture<KaraokeTrack> requestSearch(String query, KaraokePlayer requester) {
        return neurokaraokeClient.search(query).thenCompose(results -> {
            if (results.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("No Neurokaraoke results for: " + query));
            }
            KaraokeTrack track = results.getFirst();
            return request(track, requester).thenApply(ignored -> track);
        });
    }

    public CompletableFuture<KaraokeTrack> requestRandom(KaraokePlayer requester) {
        return neurokaraokeClient.randomSong().thenCompose(track -> request(track, requester).thenApply(ignored -> track));
    }

    public CompletableFuture<KaraokeTrack> requestRadio(String station, KaraokePlayer requester) {
        return neurokaraokeClient.radio(station).thenCompose(track -> request(track, requester).thenApply(ignored -> track));
    }

    public CompletableFuture<Void> request(KaraokeTrack track, KaraokePlayer requester) {
        session.request(track, requester.id(), requester.name());
        if (session.current().isEmpty()) {
            platform.runNow(this::playNext);
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Integer> requestAll(List<KaraokeTrack> tracks, KaraokePlayer requester) {
        if (tracks.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }
        boolean shouldStart = session.current().isEmpty();
        for (KaraokeTrack track : tracks) {
            session.request(track, requester.id(), requester.name());
        }
        if (shouldStart) {
            platform.runNow(this::playNext);
        }
        return CompletableFuture.completedFuture(tracks.size());
    }

    public void playNext() {
        session.next().ifPresentOrElse(
                queued -> beginPlayback(queued, ""),
                () -> {
                    clearPlaybackTracking();
                    platform.fine("Evilkaraoke queue is empty.");
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
                () -> platform.fine("No previous track in history."));
    }

    public void stop() {
        session.stop();
        clearPlaybackTracking();
        broadcastControl(AudioCommandType.STOP, "stop");
    }

    public void handleClientStatus(UUID playerId, ClientStatusPacket status) {
        if (currentPlaybackId == null || !currentPlaybackId.equals(status.playbackId())) {
            return;
        }
        clientStates.put(playerId, status.state());
        if (status.state() == ClientPlaybackState.STOPPED && session.snapshot().state() == PlaybackState.PLAYING) {
            playNext();
            return;
        }
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
        platform.fine("Started playback timer after " + reason);
    }

    public void syncPlayer(KaraokePlayer player) {
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
        platform.sendAudio(player, packet);
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

    public Optional<KaraokeSession.QueuedTrack> cancelAt(int position) {
        return session.removeAt(position - 1);
    }

    public List<KaraokeSession.QueuedTrack> cancelAll() {
        return session.removeAllQueued();
    }

    public List<KaraokeSession.QueuedTrack> cancelAllByRequester(UUID requester) {
        return session.removeRequestsByRequester(requester);
    }

    public Optional<KaraokeSession.QueuedTrack> moveRequest(int fromPosition, int toPosition) {
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
        for (KaraokePlayer player : recipients()) {
            if (!config.requireClientMod() || clientRegistry.session(player.id()).isPresent()) {
                platform.sendAudio(player, packet);
            }
        }
    }

    private List<KaraokePlayer> recipients() {
        if (audienceMode == TargetMode.PLAYER && audiencePlayer != null) {
            return platform.player(audiencePlayer).map(List::of).orElseGet(List::of);
        }
        return new ArrayList<>(platform.onlinePlayers());
    }

    private void schedulePlaybackStartFallback(KaraokeTrack track, String playbackId) {
        cancelAutoAdvance();
        autoAdvanceTask = platform.runLater(() -> {
            if (currentPlaybackId != null && currentPlaybackId.equals(playbackId) && !timerStarted) {
                startPlaybackTimer(track, playbackId, "client start timeout");
            }
        }, Math.max(1L, CLIENT_START_TIMEOUT_SECONDS * 20L));
    }

    private void scheduleAutoAdvance(KaraokeTrack track, String playbackId) {
        cancelAutoAdvance();
        track.finiteDuration().ifPresent(duration -> {
            Duration remaining = duration.minus(session.snapshot().offset()).plus(Duration.ofSeconds(BUFFER_TIME_SECONDS));
            long delayTicks = ticksCeil(remaining);
            autoAdvanceTask = platform.runLater(() -> {
                if (currentPlaybackId != null && currentPlaybackId.equals(playbackId)) {
                    playNext();
                }
            }, delayTicks);
        });
    }

    private static long ticksCeil(Duration duration) {
        long millis = Math.max(1L, duration.toMillis());
        return Math.max(1L, (millis + 49L) / 50L);
    }

    private void cancelAutoAdvance() {
        if (autoAdvanceTask != -1) {
            platform.cancelTask(autoAdvanceTask);
            autoAdvanceTask = -1;
        }
    }
}
