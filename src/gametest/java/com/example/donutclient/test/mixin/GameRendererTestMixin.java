package com.example.donutclient.test.mixin;

import com.example.donutclient.test.TestFlags;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: skip drawing between screenshots so software rendering does not throttle the game. */
@Mixin(GameRenderer.class)
public abstract class GameRendererTestMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void donutTest$skipLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!TestFlags.renderWorld && TestFlags.skipLevel) ci.cancel();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void donutTest$skipRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (!TestFlags.renderWorld && TestFlags.skipRender) ci.cancel();
    }

    @Inject(method = "extract", at = @At("HEAD"), cancellable = true)
    private void donutTest$skipExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (!TestFlags.renderWorld && TestFlags.skipExtract) ci.cancel();
    }
}
