package com.example.donutclient;

import com.example.donutclient.config.ClientConfig;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;

/**
 * Client entrypoint. Wires the module system into Fabric's events:
 *
 *   ClientTickEvents.END_CLIENT_TICK  -> ModuleManager.onClientTick (keybinds + per-tick logic)
 *   HudElementRegistry                -> ModuleManager.onRenderHud  (drawing)
 *   LevelExtractionEvents.END_EXTRACTION -> ModuleManager.onRenderWorld (3D overlays)
 *   ClientLifecycleEvents.CLIENT_STOPPING -> save config
 */
public class DonutClientClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        DonutClient.LOGGER.info("DonutClient client init");

        ModuleManager manager = ModuleManager.get();
        manager.registerDefaults();
        ClientConfig.load();

        // Keybinds + module ticks.
        ClientTickEvents.END_CLIENT_TICK.register(manager::onClientTick);

        // HUD. Attached just before the chat layer so our overlays sit above
        // vanilla HUD but below chat/debug text.
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                DonutClient.id("modules"),
                (graphics, delta) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level == null || mc.gui.hud.isHidden()) return;
                    if (mc.getDebugOverlay().showDebugScreen()) return; // F3 open: get out of the way
                    manager.onRenderHud(new HudContext(graphics, delta));
                }
        );

        // In-world overlays (Ore ESP). Fired on the main thread while vanilla's
        // per-frame gizmo collector is open, so modules can call
        // Gizmos.cuboid/line directly. (Gizmos added from the LevelRenderEvents
        // hooks are silently dropped — use the extraction phase.)
        LevelExtractionEvents.END_EXTRACTION.register(manager::onRenderWorld);

        // Persist module state on exit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientConfig.save());
    }
}
