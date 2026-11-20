package com.example.donutclient.modules;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * "Auto Beat": toggles the singleplayer bot that plays the game by itself
 * until the Ender Dragon is dead. The module is only a thin switch around
 * {@link Bot}; it also draws the bot's current status line on the HUD.
 */
public class BotModule extends Module {
    public BotModule() {
        super("Auto Beat", "Plays the game by itself until the Ender Dragon is dead. Singleplayer only.",
                Category.MISC, GLFW.GLFW_KEY_B);
    }

    /** Starts the bot; if it refuses (e.g. not in singleplayer) show why and switch back off. */
    @Override
    protected void onEnable() {
        String err = Bot.I.start();
        if (err != null) {
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Bot] " + err));
            }
            setEnabled(false);
        }
    }

    @Override
    protected void onDisable() {
        Bot.I.stop();
    }

    /** The bot can stop on its own (finished, failed, left the world): mirror that in the toggle. */
    @Override
    public void onTick() {
        if (!Bot.I.isActive()) {
            setEnabled(false);
        }
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        // Below the FPS and coordinates labels.
        ctx.label("Bot: " + Bot.I.status(), 6, 40, 0xFF7CFC9A);
    }
}
