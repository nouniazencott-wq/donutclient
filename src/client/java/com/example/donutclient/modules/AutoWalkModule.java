package com.example.donutclient.modules;

import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;

/** Holds the forward key for you - handy for long base-hunting trips. */
public class AutoWalkModule extends Module {
    public AutoWalkModule() {
        super("Auto Walk", "Keeps walking forward until turned off.", Category.MOVEMENT, -1);
    }

    @Override
    public void onTick() {
        if (mc.player != null && mc.gui.screen() == null) mc.options.keyUp.setDown(true);
    }

    @Override
    protected void onDisable() {
        mc.options.keyUp.setDown(false);
    }
}
