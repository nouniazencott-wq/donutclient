package com.example.donutclient.module;

import com.example.donutclient.DonutClient;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.modules.CoordinatesModule;
import com.example.donutclient.modules.FpsModule;
import com.example.donutclient.modules.KeystrokesModule;
import com.example.donutclient.modules.ZoomModule;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns every module, their keybinds, and dispatches tick/render events.
 */
public final class ModuleManager {
    private static final ModuleManager INSTANCE = new ModuleManager();

    public static ModuleManager get() {
        return INSTANCE;
    }

    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Map<Module, KeyMapping> keyMappings = new LinkedHashMap<>();
    private final KeyMapping.Category keyCategory =
            KeyMapping.Category.register(DonutClient.id("modules"));

    private ModuleManager() {}

    /** Register the built-in modules. Add new ones here. */
    public void registerDefaults() {
        register(new FpsModule());
        register(new CoordinatesModule());
        register(new KeystrokesModule());
        register(new ZoomModule());
    }

    public void register(Module module) {
        if (modules.containsKey(module.getId())) {
            throw new IllegalStateException("Duplicate module id: " + module.getId());
        }
        modules.put(module.getId(), module);

        // One vanilla-style keybind per module so users can rebind it in
        // Options > Controls. Unbound modules still get an entry (UNKNOWN key).
        int key = module.getKeyCode() >= 0 ? module.getKeyCode() : InputConstants.UNKNOWN.getValue();
        KeyMapping mapping = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + DonutClient.MOD_ID + "." + module.getId(),
                InputConstants.Type.KEYSYM,
                key,
                keyCategory
        ));
        keyMappings.put(module, mapping);
    }

    // ---- dispatch ---------------------------------------------------------

    /** Called from ClientTickEvents.END_CLIENT_TICK. */
    public void onClientTick(Minecraft client) {
        // Handle toggles first so a module sees its new state in the same tick.
        for (Map.Entry<Module, KeyMapping> e : keyMappings.entrySet()) {
            while (e.getValue().consumeClick()) {
                e.getKey().toggle();
            }
        }
        for (Module m : modules.values()) {
            if (m.isEnabled()) m.onTick();
        }
    }

    /** Called from the HUD element registered in DonutClientClient. */
    public void onRenderHud(HudContext ctx) {
        for (Module m : modules.values()) {
            if (m.isEnabled()) m.onRenderHud(ctx);
        }
    }

    /** True while the module's bound key is physically held. Used by hold-style modules (Zoom). */
    public boolean isKeyHeld(Module module) {
        KeyMapping mapping = keyMappings.get(module);
        return mapping != null && mapping.isDown();
    }

    // ---- queries ------------------------------------------------------------

    public Optional<Module> byId(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    public <T extends Module> T getModule(Class<T> type) {
        for (Module m : modules.values()) {
            if (type.isInstance(m)) return type.cast(m);
        }
        throw new IllegalArgumentException("No module of type " + type.getSimpleName());
    }

    public List<Module> all() {
        return Collections.unmodifiableList(new ArrayList<>(modules.values()));
    }

    public List<Module> byCategory(Category category) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules.values()) {
            if (m.getCategory() == category) out.add(m);
        }
        return out;
    }
}
