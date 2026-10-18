package com.example.donutclient;

import com.example.donutclient.config.ClientConfig;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.ModuleManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * Client entrypoint. Wires the module system into Fabric's events:
 *
 *   ClientTickEvents.END_CLIENT_TICK  -> ModuleManager.onClientTick (keybinds + per-tick logic)
 *   HudElementRegistry                -> ModuleManager.onRenderHud  (drawing)
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
                    if (mc.level == null || mc.options.hideGui) return;
                    if (mc.getDebugOverlay().showDebugScreen()) return; // F3 open: get out of the way
                    manager.onRenderHud(new HudContext(graphics, delta));
                }
        );

        // Persist module state on exit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientConfig.save());
    }
}
