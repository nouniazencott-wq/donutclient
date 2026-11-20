package com.example.donutclient.test.mixin;

import net.minecraft.client.FramerateLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only: never sleep between frames, so accelerated tick rates actually run accelerated. */
@Mixin(FramerateLimiter.class)
public abstract class FramerateLimiterTestMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"), cancellable = true)
    private static void donutTest$noLimit(int framerateLimit, CallbackInfo ci) {
        ci.cancel();
    }
}
