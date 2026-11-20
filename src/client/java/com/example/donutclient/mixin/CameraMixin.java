package com.example.donutclient.mixin;

import com.example.donutclient.module.ModuleManager;
import com.example.donutclient.modules.ZoomModule;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Scales the camera FOV for the Zoom module. In 26.2 the world FOV is
 * computed in Camera#calculateFov (it moved out of GameRenderer).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float donutclient$applyZoom(float original) {
        ZoomModule zoom = ModuleManager.get().getModule(ZoomModule.class);
        return original * zoom.fovMultiplier();
    }
}
