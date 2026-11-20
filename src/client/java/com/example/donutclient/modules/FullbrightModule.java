package com.example.donutclient.modules;

import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Client-side night vision so caves and bases are fully lit. */
public class FullbrightModule extends Module {
    private boolean applied;

    public FullbrightModule() {
        super("Fullbright", "See in the dark (client-side night vision).", Category.RENDER, -1);
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            mc.player.addEffect(new MobEffectInstance(
                    MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
            applied = true;
        }
    }

    @Override
    protected void onDisable() {
        if (applied && mc.player != null) {
            MobEffectInstance e = mc.player.getEffect(MobEffects.NIGHT_VISION);
            if (e != null && e.isInfiniteDuration()) mc.player.removeEffect(MobEffects.NIGHT_VISION);
        }
        applied = false;
    }
}
