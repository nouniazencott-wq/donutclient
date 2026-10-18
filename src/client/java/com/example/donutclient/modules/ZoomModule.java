package com.example.donutclient.modules;

import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.example.donutclient.module.ModuleManager;
import org.lwjgl.glfw.GLFW;

/**
 * OptiFine-style zoom. The actual FOV change happens in GameRendererMixin,
 * which asks this module for a multiplier every frame. The module itself is
 * a "hold" toggle: it's on while the key is held, off when released.
 *
 * The smooth interpolation gives the camera a short ease-in instead of a snap.
 */
public class ZoomModule extends Module {
    private static final float TARGET = 0.25f;   // 25% of normal FOV ≈ 4x zoom
    private static final float SPEED = 0.35f;    // 0..1, higher = snappier

    private float current = 1.0f;

    public ZoomModule() {
        super("Zoom", "Hold to zoom the camera in.", Category.RENDER, GLFW.GLFW_KEY_C);
    }

    /**
     * Hold-to-use semantics: the key mapping toggles us on press, but we also
     * turn ourselves off as soon as the key is released. Checked each tick.
     */
    @Override
    public void onTick() {
        if (!ModuleManager.get().isKeyHeld(this)) {
            setEnabled(false);
        }
    }

    /** Called every frame by GameRendererMixin. */
    public float fovMultiplier() {
        float target = isEnabled() ? TARGET : 1.0f;
        current += (target - current) * SPEED;
        if (Math.abs(target - current) < 0.001f) current = target;
        return current;
    }

    @Override
    protected void onEnable() {
        // Disable mouse smoothing effects if you want: mc.options.smoothCamera().set(true) etc.
    }
}
