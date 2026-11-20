package com.example.donutclient.gui;

import com.example.donutclient.config.ClientConfig;
import com.example.donutclient.esp.OreType;
import com.example.donutclient.modules.OreEspModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Settings for Ore ESP: one checkbox per ore, plus tracers, scan range and
 * the module's on/off switch. Changes apply immediately and are saved when
 * the screen closes.
 */
public class OreEspScreen extends Screen {
    private static final int COLUMN_WIDTH = 130;
    private static final int ROW_HEIGHT = 24;

    private final OreEspModule module;

    public OreEspScreen(OreEspModule module) {
        super(Component.literal("Ore ESP"));
        this.module = module;
    }

    @Override
    protected void init() {
        OreType[] types = OreType.values();
        int rows = (types.length + 1) / 2;
        int left = width / 2 - COLUMN_WIDTH;
        int top = Math.max(32, height / 2 - (rows + 3) * ROW_HEIGHT / 2);

        for (int i = 0; i < types.length; i++) {
            OreType type = types[i];
            int x = left + (i % 2) * COLUMN_WIDTH + 10;
            int y = top + (i / 2) * ROW_HEIGHT;
            addRenderableWidget(Checkbox.builder(Component.literal(type.displayName).withColor(type.rgb), font)
                    .pos(x, y)
                    .selected(module.isSelected(type))
                    .onValueChange((box, value) -> module.setSelected(type, value))
                    .build());
        }

        int y = top + rows * ROW_HEIGHT + 8;
        addRenderableWidget(Checkbox.builder(Component.literal("Tracer lines"), font)
                .pos(left + 10, y)
                .selected(module.tracersEnabled())
                .onValueChange((box, value) -> module.setTracers(value))
                .build());
        addRenderableWidget(Button.builder(rangeLabel(), button -> {
            module.setChunkRadius(nextRadius());
            button.setMessage(rangeLabel());
        }).bounds(left + COLUMN_WIDTH + 10, y - 1, COLUMN_WIDTH - 20, 20).build());

        y += ROW_HEIGHT + 8;
        addRenderableWidget(Button.builder(enabledLabel(), button -> {
            module.toggle();
            button.setMessage(enabledLabel());
        }).bounds(width / 2 - 122, y, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 + 2, y, 120, 20).build());
    }

    private Component rangeLabel() {
        return Component.literal("Range: " + module.getChunkRadius() * 16 + " blocks");
    }

    private Component enabledLabel() {
        return Component.literal("Ore ESP: " + (module.isEnabled() ? "ON" : "OFF"));
    }

    private int nextRadius() {
        int[] choices = OreEspModule.RADIUS_CHOICES;
        for (int choice : choices) {
            if (choice > module.getChunkRadius()) return choice;
        }
        return choices[0];
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        ClientConfig.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
