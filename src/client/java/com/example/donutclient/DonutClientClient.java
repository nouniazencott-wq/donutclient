package com.example.donutclient;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.config.ClientConfig;
import com.example.donutclient.modules.BotModule;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;
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

        // The bot drives the player at the start of each tick (before movement is applied).
        ClientTickEvents.START_CLIENT_TICK.register(client -> Bot.I.onTick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Bot.I.stop());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommands.literal("bot")
                        .then(ClientCommands.literal("start").executes(c -> {
                            manager.getModule(BotModule.class).setEnabled(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(c -> {
                            manager.getModule(BotModule.class).setEnabled(false);
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(c -> {
                            c.getSource().sendFeedback(Component.literal("[Bot] " + Bot.I.status()));
                            return 1;
                        }))));

        // Keybinds + module ticks.
        ClientTickEvents.END_CLIENT_TICK.register(manager::onClientTick);

        // HUD. Attached just before the chat layer so our overlays sit above
        // vanilla HUD but below chat/debug text.
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                DonutClient.id("modules"),
                (graphics, delta) -> {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level == null) return;
                    if (mc.getDebugOverlay().showDebugScreen()) return; // F3 open: get out of the way
                    manager.onRenderHud(new HudContext(graphics, delta));
                }
        );

        // Persist module state on exit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ClientConfig.save());
    }
}
