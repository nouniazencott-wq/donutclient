package com.example.donutclient.gui;

import com.example.donutclient.config.ClientConfig;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.example.donutclient.module.ModuleManager;
import com.example.donutclient.setting.BoolSetting;
import com.example.donutclient.setting.NumSetting;
import com.example.donutclient.setting.Setting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Click GUI: one column per category.
 *   left click a module   -> toggle it
 *   right click a module  -> show / hide its settings
 *   click a setting       -> flip a switch, or drag a slider
 */
public class ClickGuiScreen extends Screen {
    private static final int MAX_colW = 124;
    private static final int COL_GAP = 6;
    private static final int ROW_H = 14;

    private static final int ACCENT = 0xFFFFAA00;
    private static final int HEADER_BG = 0xF0181818;
    private static final int ROW_BG = 0xD0202020;
    private static final int ROW_HOVER = 0xD0323232;
    private static final int SETTING_BG = 0xD0101010;

    /** Remembered between openings. */
    private static final Set<Module> EXPANDED = new HashSet<>();

    private record Row(int x, int y, int w, Module module, Setting setting) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + ROW_H;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    /** Column width and left edge for the current window size. */
    private int colW, left;
    private NumSetting dragging;
    private int dragX, dragW;

    public ClickGuiScreen() {
        super(Component.literal("Donut Client"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void layout() {
        rows.clear();
        Category[] cats = Category.values();
        colW = Math.min(MAX_colW, (width - 8 - (cats.length - 1) * COL_GAP) / cats.length);
        left = (width - (cats.length * colW + (cats.length - 1) * COL_GAP)) / 2;
        int x = left;
        for (Category cat : cats) {
            int y = 30 + ROW_H + 2;
            for (Module m : ModuleManager.get().byCategory(cat)) {
                rows.add(new Row(x, y, colW, m, null));
                y += ROW_H;
                if (EXPANDED.contains(m)) {
                    for (Setting s : m.getSettings()) {
                        rows.add(new Row(x, y, colW, m, s));
                        y += ROW_H;
                    }
                }
            }
            x += colW + COL_GAP;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractRenderState(g, mouseX, mouseY, a);
        layout();

        if (dragging != null) {
            dragging.setFraction((mouseX - dragX - 2) / (double) (dragW - 4));
        }

        g.centeredText(font, "Donut Client", width / 2, 10, ACCENT);

        // Category headers.
        int x = left;
        for (Category cat : Category.values()) {
            g.fill(x, 30, x + colW, 30 + ROW_H, HEADER_BG);
            g.fill(x, 30 + ROW_H, x + colW, 30 + ROW_H + 2, ACCENT);
            g.centeredText(font, cat.displayName, x + colW / 2, 33, 0xFFFFFFFF);
            x += colW + COL_GAP;
        }

        Module hovered = null;
        for (Row r : rows) {
            boolean hover = r.contains(mouseX, mouseY);
            if (r.setting == null) {
                Module m = r.module;
                if (hover) hovered = m;
                g.fill(r.x, r.y, r.x + r.w, r.y + ROW_H, hover ? ROW_HOVER : ROW_BG);
                if (m.isEnabled()) g.fill(r.x, r.y, r.x + 2, r.y + ROW_H, ACCENT);
                g.text(font, m.getName(), r.x + 6, r.y + 3, m.isEnabled() ? ACCENT : 0xFFD0D0D0);
                String right = m.getSettings().isEmpty() ? "" : (EXPANDED.contains(m) ? "-" : "+");
                String key = ModuleManager.get().keyName(m);
                if (!key.isEmpty()) right = "[" + key + "] " + right;
                g.text(font, right, r.x + r.w - font.width(right) - 4, r.y + 3, 0xFF808080);
            } else {
                g.fill(r.x, r.y, r.x + r.w, r.y + ROW_H, SETTING_BG);
                if (r.setting instanceof NumSetting n) {
                    int fillW = (int) ((r.w - 4) * n.fraction());
                    g.fill(r.x + 2, r.y + ROW_H - 3, r.x + 2 + fillW, r.y + ROW_H - 1, ACCENT);
                }
                String value = r.setting.display();
                int valueColor = r.setting instanceof BoolSetting b ? (b.get() ? 0xFF55FF55 : 0xFFFF5555) : 0xFFFFFFFF;
                g.text(font, r.setting.getName(), r.x + 10, r.y + 2, 0xFFB0B0B0);
                g.text(font, value, r.x + r.w - font.width(value) - 4, r.y + 2, valueColor);
            }
        }

        String hint = hovered != null
                ? hovered.getDescription()
                : "Left click: toggle   Right click: settings";
        g.centeredText(font, hint, width / 2, height - 16, 0xFFD0D0D0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        layout();
        for (Row r : rows) {
            if (!r.contains(event.x(), event.y())) continue;
            if (r.setting == null) {
                if (event.button() == 0) {
                    r.module.toggle();
                } else if (event.button() == 1 && !r.module.getSettings().isEmpty()) {
                    if (!EXPANDED.remove(r.module)) EXPANDED.add(r.module);
                }
            } else if (r.setting instanceof BoolSetting b) {
                b.toggle();
            } else if (r.setting instanceof NumSetting n) {
                dragging = n;
                dragX = r.x;
                dragW = r.w;
                n.setFraction((event.x() - dragX - 2) / (double) (dragW - 4));
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        dragging = null;
        return super.mouseReleased(event);
    }

    @Override
    public void removed() {
        dragging = null;
        ClientConfig.save();
        super.removed();
    }
}
