package com.example.donutclient.modules;

import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;

/**
 * Classic WASD + mouse-button overlay. Reads the player's *actual* bindings
 * from Options, so it stays correct if they rebind movement keys.
 */
public class KeystrokesModule extends Module {
    private static final int SIZE = 18;
    private static final int GAP = 2;

    public KeystrokesModule() {
        super("Keystrokes", "Shows which movement keys and mouse buttons are held.", Category.HUD, GLFW.GLFW_KEY_K);
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        Options o = mc.options;

        // Anchor: bottom-left, above the hotbar area.
        int baseX = 8;
        int baseY = ctx.screenHeight - 8 - (SIZE * 3 + GAP * 2);

        // Row 1: W
        key(ctx, o.keyUp, baseX + SIZE + GAP, baseY);
        // Row 2: A S D
        key(ctx, o.keyLeft, baseX, baseY + SIZE + GAP);
        key(ctx, o.keyDown, baseX + SIZE + GAP, baseY + SIZE + GAP);
        key(ctx, o.keyRight, baseX + (SIZE + GAP) * 2, baseY + SIZE + GAP);
        // Row 3: LMB RMB (wide buttons)
        int wide = (SIZE * 3 + GAP * 2 - GAP) / 2;
        wideKey(ctx, "LMB", o.keyAttack.isDown(), baseX, baseY + (SIZE + GAP) * 2, wide);
        wideKey(ctx, "RMB", o.keyUse.isDown(), baseX + wide + GAP, baseY + (SIZE + GAP) * 2, wide);
    }

    private void key(HudContext ctx, KeyMapping mapping, int x, int y) {
        String label = mapping.getTranslatedKeyMessage().getString();
        if (label.length() > 1) label = label.substring(0, 1); // "W" not "Key W"
        drawBox(ctx, label.toUpperCase(), mapping.isDown(), x, y, SIZE);
    }

    private void wideKey(HudContext ctx, String label, boolean down, int x, int y, int w) {
        drawBox(ctx, label, down, x, y, w);
    }

    private void drawBox(HudContext ctx, String label, boolean down, int x, int y, int w) {
        int bg = down ? 0xCCFFFFFF : 0x80000000;
        int fg = down ? 0xFF000000 : 0xFFFFFFFF;
        ctx.rect(x, y, x + w, y + SIZE, bg);
        int tx = x + (w - ctx.textWidth(label)) / 2;
        int ty = y + (SIZE - ctx.font.lineHeight) / 2 + 1;
        ctx.text(label, tx, ty, fg);
    }
}
