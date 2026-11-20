package com.example.donutclient.module;

import com.example.donutclient.DonutClient;
import com.example.donutclient.hud.HudContext;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.minecraft.client.Minecraft;

/**
 * Base class for every feature in the client.
 *
 * A module is a toggleable unit with a name, a category, and optional hooks:
 *  - onEnable / onDisable       state transitions
 *  - onTick                     every client tick while enabled
 *  - onRenderHud                every HUD frame while enabled
 *  - onRenderWorld              every world frame while enabled (3D overlays)
 *  - writeSettings/readSettings extra options persisted in the config file
 *
 * Keep modules small and single-purpose. If a module needs to change vanilla
 * behaviour that has no event, add a Mixin in the mixin package and have the
 * mixin consult the module's state (see CameraMixin + ZoomModule).
 */
public abstract class Module {
    protected final Minecraft mc = Minecraft.getInstance();

    private final String name;
    private final String description;
    private final Category category;
    private boolean enabled;

    /** Default key (GLFW keycode) or -1 for unbound. Persisted in config. */
    private int keyCode;

    protected Module(String name, String description, Category category, int defaultKey) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keyCode = defaultKey;
    }

    // ---- state ---------------------------------------------------------

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (enabled) onEnable(); else onDisable();
        DonutClient.LOGGER.debug("{} -> {}", name, enabled ? "ON" : "OFF");
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ---- hooks (override as needed) --------------------------------------

    protected void onEnable() {}

    protected void onDisable() {}

    /** Called every client tick while enabled. mc.player may be null. */
    public void onTick() {}

    /** Called every HUD frame while enabled (only when a world is loaded). */
    public void onRenderHud(HudContext ctx) {}

    /**
     * Called every frame while enabled, at the end of level extraction, while
     * vanilla's per-frame gizmo collector is open. Draw 3D overlays here with
     * net.minecraft.gizmos.Gizmos; they are rendered on the following frame.
     */
    public void onRenderWorld(LevelExtractionContext ctx) {}

    /** Store module-specific options. Called when the config is saved. */
    public void writeSettings(JsonObject json) {}

    /** Restore what writeSettings stored. Keys may be missing (older config). */
    public void readSettings(JsonObject json) {}

    // ---- metadata --------------------------------------------------------

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Category getCategory() { return category; }
    public int getKeyCode() { return keyCode; }
    public void setKeyCode(int keyCode) { this.keyCode = keyCode; }

    /** Stable identifier used in config and translation keys. */
    public String getId() {
        return name.toLowerCase().replace(' ', '_');
    }
}
