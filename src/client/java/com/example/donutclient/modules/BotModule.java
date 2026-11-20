package com.example.donutclient.modules;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Toggles the autonomous "beat the game" bot (singleplayer only). */
public class BotModule extends Module {
    public BotModule() {
        super("Auto Beat", "Plays the game by itself until the Ender Dragon is dead. Singleplayer only.",
                Category.MISC, GLFW.GLFW_KEY_B);
    }

    @Override
    protected void onEnable() {
        String err = Bot.I.start();
        if (err != null) {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Bot] " + err));
            setEnabled(false);
        }
    }

    @Override
    protected void onDisable() {
        Bot.I.stop();
    }

    @Override
    public void onTick() {
        if (!Bot.I.isActive()) setEnabled(false);
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        ctx.label("Bot: " + Bot.I.status(), 6, 40, 0xFF7CFC9A);
    }
}
