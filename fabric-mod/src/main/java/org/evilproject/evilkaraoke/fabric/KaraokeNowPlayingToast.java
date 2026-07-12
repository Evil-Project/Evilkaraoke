package org.evilproject.evilkaraoke.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.ColorLerper;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

/**
 * Renders karaoke track announcements in the same style as the vanilla
 * "Now Playing" music toast: the {@code toast/now_playing} background, the
 * color-cycling music notes icon, and the slide-in from the top-left corner.
 * Vanilla's {@code NowPlayingToast} cannot be reused directly because it reads
 * the track name from {@code MusicManager}, which only knows vanilla music.
 */
final class KaraokeNowPlayingToast implements Toast {
    private static final Identifier BACKGROUND_SPRITE = Identifier.withDefaultNamespace("toast/now_playing");
    private static final Identifier MUSIC_NOTES_SPRITE = Identifier.parse("icon/music_notes");
    private static final int PADDING = 7;
    private static final int MUSIC_NOTES_SIZE = 16;
    private static final int HEIGHT = 30;
    private static final int TEXT_START = PADDING + MUSIC_NOTES_SIZE + PADDING;
    private static final int VISIBILITY_DURATION_MS = 5000;
    private static final int TEXT_COLOR = DyeColor.LIGHT_GRAY.getTextColor();
    private static final long MUSIC_COLOR_CHANGE_FREQUENCY_MS = 25L;

    private final Component text;
    private int musicNoteColorTick;
    private long lastMusicNoteColorChange;
    private int musicNoteColor = -1;
    private Toast.Visibility wantedVisibility = Toast.Visibility.SHOW;

    KaraokeNowPlayingToast(Component text) {
        this.text = text;
    }

    void hide() {
        this.wantedVisibility = Toast.Visibility.HIDE;
    }

    @Override
    public Toast.Visibility getWantedVisibility() {
        return wantedVisibility;
    }

    @Override
    public void update(ToastManager manager, long fullyVisibleForMs) {
        if (fullyVisibleForMs >= VISIBILITY_DURATION_MS * manager.getNotificationDisplayTimeMultiplier()) {
            this.wantedVisibility = Toast.Visibility.HIDE;
        }
        long now = System.currentTimeMillis();
        if (now > lastMusicNoteColorChange + MUSIC_COLOR_CHANGE_FREQUENCY_MS) {
            musicNoteColorTick++;
            lastMusicNoteColorChange = now;
            musicNoteColor = ColorLerper.getLerpedColor(ColorLerper.Type.MUSIC_NOTE, musicNoteColorTick);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long fullyVisibleForMs) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND_SPRITE, 0, 0, width(), HEIGHT);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MUSIC_NOTES_SPRITE, PADDING, PADDING,
                MUSIC_NOTES_SIZE, MUSIC_NOTES_SIZE, musicNoteColor);
        graphics.text(font, text, TEXT_START, HEIGHT / 2 - 9 / 2, TEXT_COLOR);
    }

    @Override
    public int width() {
        return TEXT_START + Minecraft.getInstance().font.width(text) + PADDING;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public float xPos(int screenWidth, float visiblePortion) {
        return width() * visiblePortion - width();
    }

    @Override
    public float yPos(int firstSlotIndex) {
        return 0.0F;
    }
}
