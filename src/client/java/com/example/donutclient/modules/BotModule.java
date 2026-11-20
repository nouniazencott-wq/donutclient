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
        if (Bot.I.isActive()) {
            return; // already started some other way (/bot goto): just show the panel
        }
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

    /** Status panel: play time and deaths, the plan stage with a progress bar, and what the bot is doing now. */
    @Override
    public void onRenderHud(HudContext ctx) {
        Bot bot = Bot.I;
        String title = "Auto Beat  " + bot.elapsedText() + "  deaths " + bot.deaths + (bot.isPaused() ? "  [PAUSED]" : "");
        String stage = bot.stageCount > 0
                ? "Stage " + bot.stageIndex + "/" + bot.stageCount + ": " + bot.stageName
                : (bot.stageName.isEmpty() ? "Planning..." : bot.stageName);
        String status = bot.status();

        int lineHeight = ctx.font.lineHeight + 2;
        int pad = 3;
        // Below the FPS and coordinates labels.
        int x = 4, y = 34;
        int width = Math.max(Math.max(ctx.textWidth(title), ctx.textWidth(stage)), Math.max(ctx.textWidth(status), 120));
        int height = lineHeight * 3 + 5;
        ctx.rect(x - 2, y - 2, x + width + pad * 2, y + height + pad, 0x90000000);

        ctx.text(title, x + pad, y + 1, bot.isPaused() ? 0xFFFFD75E : 0xFF7CFC9A);
        ctx.text(stage, x + pad, y + 1 + lineHeight, 0xFFFFFFFF);
        // Progress through the plan.
        int barY = y + 1 + lineHeight * 2;
        int filled = bot.stageCount > 0 ? width * Math.max(0, bot.stageIndex - 1) / bot.stageCount : 0;
        ctx.rect(x + pad, barY, x + pad + width, barY + 3, 0xFF3A3A3A);
        ctx.rect(x + pad, barY, x + pad + filled, barY + 3, 0xFF7CFC9A);
        ctx.text(status, x + pad, barY + 6, 0xFFC8C8C8);
    }
}
