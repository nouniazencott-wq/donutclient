package com.example.donutclient.test.mixin;

import com.example.donutclient.test.TestFlags;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.systems.GpuSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Test-only: do not present frames that were not rendered. */
@Mixin(Minecraft.class)
public abstract class MinecraftTestMixin {
    @WrapWithCondition(method = "renderFrame",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
    private boolean donutTest$present(GpuSurface surface) {
        return TestFlags.renderWorld || !TestFlags.skipPresent;
    }

    @ModifyReturnValue(method = "getTickTargetMillis", at = @At("RETURN"))
    private float donutTest$speed(float original) {
        return original / TestFlags.speed;
    }
}
