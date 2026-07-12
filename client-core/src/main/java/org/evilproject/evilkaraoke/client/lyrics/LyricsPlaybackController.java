package org.evilproject.evilkaraoke.client.lyrics;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.evilproject.evilkaraoke.common.model.KaraokeTrack;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandPacket;
import org.evilproject.evilkaraoke.common.protocol.AudioCommandType;
import org.evilproject.evilkaraoke.common.protocol.ClientPlaybackState;

/** Matches asynchronously loaded lyric cues to the client's actual audio position. */
public final class LyricsPlaybackController {
    private static final int UNRESOLVED_CUE = Integer.MIN_VALUE;
    private static final int NO_ACTIVE_CUE = -1;

    private final Logger logger;
    private final LyricsProvider provider;
    private final Object lock = new Object();

    private volatile Consumer<String> renderer;
    private volatile Runnable clearer;
    private long generation;
    private String playbackId;
    private Duration initialOffset = Duration.ZERO;
    private Duration trackDuration;
    private List<TimedLyric> cues = List.of();
    private int activeCue = UNRESOLVED_CUE;
    private boolean displayVisible;

    public LyricsPlaybackController(Logger logger) {
        this(logger, new NeurokaraokeLyricsClient());
    }

    public LyricsPlaybackController(Logger logger, LyricsProvider provider) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public void setRenderer(Consumer<String> renderer) {
        this.renderer = renderer;
        if (renderer == null) {
            stop();
        }
    }

    public void setClearer(Runnable clearer) {
        this.clearer = clearer;
    }

    public void handleCommand(AudioCommandPacket command) {
        if (command.command() == AudioCommandType.PLAY) {
            begin(command);
        } else if (command.command() == AudioCommandType.STOP) {
            stop();
        }
    }

    public void stop() {
        boolean shouldClear;
        synchronized (lock) {
            shouldClear = displayVisible;
            generation++;
            playbackId = null;
            initialOffset = Duration.ZERO;
            trackDuration = null;
            cues = List.of();
            activeCue = UNRESOLVED_CUE;
            displayVisible = false;
        }
        if (shouldClear) {
            clearDisplayCallback();
        }
    }

    /** Repositions the cue cursor so the next tick restores the lyric at the current audio position. */
    public void resync() {
        synchronized (lock) {
            activeCue = UNRESOLVED_CUE;
        }
    }

    /** Clears any currently rendered lyric without ending the playback session. */
    public void clearDisplay() {
        boolean shouldClear;
        synchronized (lock) {
            shouldClear = displayVisible;
            activeCue = UNRESOLVED_CUE;
            displayVisible = false;
        }
        if (shouldClear) {
            clearDisplayCallback();
        }
    }

    public void tick(boolean lyricsEnabled, ClientPlaybackState state, Optional<Duration> playbackPosition) {
        if (!lyricsEnabled || state != ClientPlaybackState.PLAYING || renderer == null) {
            return;
        }

        String dueText = null;
        boolean shouldClear = false;
        synchronized (lock) {
            if (playbackId == null) {
                return;
            }
            Duration position = playbackPosition.orElse(initialOffset);
            if (position.isNegative()) {
                position = Duration.ZERO;
            }

            int currentCue = trackDuration != null && position.compareTo(trackDuration) >= 0
                    ? NO_ACTIVE_CUE
                    : currentCueAt(cues, position);
            if (currentCue == activeCue) {
                return;
            }
            activeCue = currentCue;

            boolean shouldShow = currentCue >= 0 && !isInstrumental(cues.get(currentCue).text());
            if (shouldShow) {
                dueText = cues.get(currentCue).text();
                displayVisible = true;
            } else {
                shouldClear = displayVisible;
                displayVisible = false;
            }
        }

        if (dueText != null) {
            showDisplayCallback(dueText);
        } else if (shouldClear) {
            clearDisplayCallback();
        }
    }

    private void begin(AudioCommandPacket command) {
        KaraokeTrack track = command.track();
        UUID songId = track == null ? null : canonicalUuid(track.id());
        Consumer<String> currentRenderer = renderer;

        final long requestGeneration;
        boolean shouldClear;
        synchronized (lock) {
            shouldClear = displayVisible;
            requestGeneration = ++generation;
            playbackId = command.playbackId();
            initialOffset = command.offset() == null || command.offset().isNegative() ? Duration.ZERO : command.offset();
            trackDuration = track == null ? null : track.finiteDuration()
                    .filter(duration -> !duration.isZero() && !duration.isNegative())
                    .orElse(null);
            cues = List.of();
            activeCue = UNRESOLVED_CUE;
            displayVisible = false;
        }
        if (shouldClear) {
            clearDisplayCallback();
        }
        if (songId == null || currentRenderer == null) {
            return;
        }

        CompletableFuture<List<TimedLyric>> request;
        try {
            request = Objects.requireNonNull(provider.lyrics(songId), "lyrics provider future");
        } catch (RuntimeException ex) {
            complete(requestGeneration, null, ex);
            return;
        }
        request.whenComplete((loaded, error) -> complete(requestGeneration, loaded, error));
    }

    private void complete(long requestGeneration, List<TimedLyric> loaded, Throwable error) {
        synchronized (lock) {
            if (requestGeneration != generation || playbackId == null) {
                return;
            }
            if (error == null) {
                cues = loaded == null ? List.of() : loaded.stream()
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(TimedLyric::offset))
                        .toList();
                activeCue = UNRESOLVED_CUE;
                return;
            }
        }
        logger.log(Level.FINE, "Could not load Neurokaraoke lyrics", unwrap(error));
    }

    private void showDisplayCallback(String text) {
        Consumer<String> currentRenderer = renderer;
        if (currentRenderer == null) {
            return;
        }
        try {
            currentRenderer.accept(text);
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Could not show an Evilkaraoke lyric line", ex);
        }
    }

    private void clearDisplayCallback() {
        Runnable currentClearer = clearer;
        if (currentClearer == null) {
            return;
        }
        try {
            currentClearer.run();
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "Could not clear the Evilkaraoke lyric line", ex);
        }
    }

    private static int currentCueAt(List<TimedLyric> cues, Duration position) {
        int low = 0;
        int high = cues.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).offset().compareTo(position) <= 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low - 1;
    }

    private static boolean isInstrumental(String text) {
        return "(instrumental)".equalsIgnoreCase(text.trim());
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
