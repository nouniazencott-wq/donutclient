package com.example.donutclient.mixin;

import com.example.donutclient.module.ModuleManager;
import com.example.donutclient.modules.ZoomModule;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Scales the world FOV for the Zoom module (Camera#calculateFov in 26.x). */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float donutclient$applyZoom(float original) {
        ZoomModule zoom = ModuleManager.get().getModule(ZoomModule.class);
        return original * zoom.fovMultiplier();
    }
}
