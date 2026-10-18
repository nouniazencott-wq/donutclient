package com.example.donutclient.mixin;

import com.example.donutclient.module.ModuleManager;
import com.example.donutclient.modules.ZoomModule;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Scales the camera FOV for the Zoom module.
 *
 * This is the only place the client touches vanilla internals, and the one
 * spot most likely to need a tweak when Minecraft updates: the target method
 * is GameRenderer#getFov. Since 26.x is unobfuscated, open GameRenderer in
 * your IDE (Ctrl/Cmd+click) and confirm the name/signature if this fails to
 * apply — Mixin will log "Critical injection failure" at startup if so.
 *
 * MixinExtras (@ModifyReturnValue) ships with Fabric Loader, no extra dependency.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float donutclient$applyZoom(float original) {
        ZoomModule zoom = ModuleManager.get().getModule(ZoomModule.class);
        return original * zoom.fovMultiplier();
    }
}
