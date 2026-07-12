package org.evilproject.evilkaraoke.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** Displays timed lyrics in the vanilla {@code /title ... actionbar} HUD area. */
final class KaraokeLyricActionBar {
    private static final int SCREEN_MARGIN = 16;

    // PLAY, lyric, and tick callbacks all run on the client game thread.
    private static String currentText;

    private KaraokeLyricActionBar() {
    }

    static void showLyric(String text) {
        if (text == null || text.isBlank()) {
            hide();
            return;
        }
        currentText = text;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        setActionBar(minecraft, displayText(minecraft, text));
    }

    static void hide() {
        boolean ownsActionBar = currentText != null;
        currentText = null;
        if (ownsActionBar) {
            clearActionBar();
        }
    }

    static void tick(boolean sessionActive) {
        if (!sessionActive) {
            hide();
            return;
        }
        String text = currentText;
        Minecraft minecraft = Minecraft.getInstance();
        if (text != null && minecraft != null) {
            setActionBar(minecraft, displayText(minecraft, text));
        }
    }

    private static Component displayText(Minecraft minecraft, String text) {
        Font font = minecraft.font;
        int availableWidth = Math.max(0, minecraft.getWindow().getGuiScaledWidth() - (SCREEN_MARGIN * 2));
        if (font.width(text) <= availableWidth) {
            return Component.literal(text);
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        if (availableWidth <= ellipsisWidth) {
            return Component.literal(font.plainSubstrByWidth(ellipsis, availableWidth));
        }
        return Component.literal(font.plainSubstrByWidth(text, availableWidth - ellipsisWidth) + ellipsis);
    }

    private static void setActionBar(Minecraft minecraft, Component text) {
        minecraft.gui.hud.setOverlayMessage(text, false);
    }

    private static void clearActionBar() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.gui.hud.setOverlayMessage(null, false);
        }
    }
}
