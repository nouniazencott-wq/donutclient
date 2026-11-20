package com.example.donutclient.mixin;

import com.example.donutclient.bot.Bot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla cancels block breaking every tick the attack key is not held.
 * While the bot is mining, that logic must stay out of the way.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void donutclient$botMining(boolean down, CallbackInfo ci) {
        if (Bot.I.isActive()) ci.cancel();
    }
}
