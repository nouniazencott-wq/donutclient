package com.example.donutclient.mixin;

import com.example.donutclient.module.ModuleManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World-render hook. Vanilla collects "gizmos" (debug shapes) for the frame and
 * finalizes them here; we add ours to the same collector right before that so
 * they are drawn this frame.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Final
    private SimpleGizmoCollector renderThreadGizmos;

    @Inject(method = "finalizeGizmoCollection", at = @At("HEAD"))
    private void donutclient$renderWorld(CallbackInfo ci) {
        try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(this.renderThreadGizmos)) {
            ModuleManager.get().onRenderWorld();
        }
    }
}
