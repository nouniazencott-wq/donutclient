package com.example.donutclient.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;

/**
 * Small facade over Minecraft's HUD drawing API.
 *
 * Modules draw through this class instead of touching GuiGraphicsExtractor
 * directly. When Mojang changes the rendering API again (they did a big
 * rewrite in 26.1), only this file needs updating.
 */
public final class HudContext {
    public final GuiGraphicsExtractor graphics;
    public final DeltaTracker delta;
    public final Minecraft mc;
    public final Font font;
    public final int screenWidth;
    public final int screenHeight;

    public HudContext(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        this.graphics = graphics;
        this.delta = delta;
        this.mc = Minecraft.getInstance();
        this.font = mc.font;
        this.screenWidth = graphics.guiWidth();
        this.screenHeight = graphics.guiHeight();
    }

    /** Draw text with a drop shadow. Color is ARGB (0xAARRGGBB). */
    public void text(String s, int x, int y, int argb) {
        graphics.drawString(font, s, x, y, argb);
    }

    /** Solid rectangle. Color is ARGB. */
    public void rect(int x1, int y1, int x2, int y2, int argb) {
        graphics.fill(x1, y1, x2, y2, argb);
    }

    /** Text on a translucent dark background — the classic "HUD module" look. */
    public void label(String s, int x, int y, int textArgb) {
        int pad = 2;
        int w = font.width(s);
        int h = font.lineHeight;
        rect(x - pad, y - pad, x + w + pad, y + h + pad, 0x80000000);
        text(s, x, y, textArgb);
    }

    public int textWidth(String s) {
        return font.width(s);
    }
}
