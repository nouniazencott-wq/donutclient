package com.example.donutclient;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.example.donutclient.config.ClientConfig;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.ModuleManager;
import com.example.donutclient.modules.BotModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.network.chat.Component;

/**
 * Client entrypoint. Wires the module system into Fabric's events:
 *
 *   ClientTickEvents.START_CLIENT_TICK -> Bot.onTick (the bot sets its inputs before vanilla reads them)
 *   ClientPlayConnectionEvents.DISCONNECT -> Bot.stop
 *   ClientCommandRegistrationCallback -> /bot start | stop | pause | resume | goto <x> <z> | status
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

        // The bot runs at the start of the tick so the keys it "presses" are
        // seen by vanilla input handling in the same tick.
        ClientTickEvents.START_CLIENT_TICK.register(client -> Bot.I.onTick());

        // Never keep playing across worlds.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Bot.I.onWorldLeft());

        // /bot start | stop | status  (client-side command, same switch as the keybind).
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, c) ->
                dispatcher.register(ClientCommands.literal("bot")
                        .then(ClientCommands.literal("start").executes(ctx -> {
                            manager.getModule(BotModule.class).setEnabled(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            manager.getModule(BotModule.class).setEnabled(false);
                            return 1;
                        }))
                        .then(ClientCommands.literal("pause").executes(ctx -> {
                            if (!Bot.I.isActive()) {
                                ctx.getSource().sendFeedback(Component.literal("[Bot] I'm not running."));
                            }
                            Bot.I.setPaused(true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("resume").executes(ctx -> {
                            if (!Bot.I.isActive()) {
                                ctx.getSource().sendFeedback(Component.literal("[Bot] I'm not running. Use /bot start."));
                            }
                            Bot.I.setPaused(false);
                            return 1;
                        }))
                        // /bot goto <x> <z>: just walk there (no game-beating plan).
                        .then(ClientCommands.literal("goto")
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer()).executes(ctx -> {
                                            int x = IntegerArgumentType.getInteger(ctx, "x");
                                            int z = IntegerArgumentType.getInteger(ctx, "z");
                                            if (Bot.I.isActive()) {
                                                manager.getModule(BotModule.class).setEnabled(false);
                                            }
                                            String err = Bot.I.startDebugNav(Goal.xz(x, z, 2.0));
                                            if (err != null) {
                                                ctx.getSource().sendFeedback(Component.literal("[Bot] " + err));
                                                return 0;
                                            }
                                            // Show the status panel while walking; the module switches off when done.
                                            manager.getModule(BotModule.class).setEnabled(true);
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("status").executes(ctx -> {
                            Bot bot = Bot.I;
                            String text = !bot.isActive() ? "idle"
                                    : (bot.isPaused() ? "paused" : bot.status())
                                    + (bot.stageCount > 0 ? " | stage " + bot.stageIndex + "/" + bot.stageCount
                                            + " (" + bot.stageName + ")" : "")
                                    + " | time " + bot.elapsedText() + " | deaths " + bot.deaths;
                            ctx.getSource().sendFeedback(Component.literal("[Bot] " + text));
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
