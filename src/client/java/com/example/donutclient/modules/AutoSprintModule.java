package com.example.donutclient.modules;

import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;

/** Sprints whenever you walk forward. */
public class AutoSprintModule extends Module {
    public AutoSprintModule() {
        super("Auto Sprint", "Always sprint while moving forward.", Category.MOVEMENT, -1);
    }

    @Override
    public void onTick() {
        if (mc.player == null) return;
        if (mc.options.keyUp.isDown()
                && !mc.player.isShiftKeyDown()
                && !mc.player.isUsingItem()
                && !mc.player.horizontalCollision
                && mc.player.getFoodData().getFoodLevel() > 6) {
            mc.player.setSprinting(true);
        }
    }
}
