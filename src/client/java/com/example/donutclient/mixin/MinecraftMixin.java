package com.example.donutclient.mixin;

import com.example.donutclient.bot.Bot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps vanilla's per-tick "continue attack" handling out of the bot's way.
 *
 * Minecraft#continueAttack runs every tick and, because the attack key is not
 * physically held while the bot plays, would abort whatever block the bot is
 * currently breaking. While the bot is active it drives block breaking itself,
 * so the vanilla call is cancelled.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void donutclient$botMining(boolean down, CallbackInfo ci) {
        if (Bot.I.isActive()) {
            ci.cancel();
        }
    }
}
