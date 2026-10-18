package com.example.donutclient.modules;

import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;

public class FpsModule extends Module {
    public FpsModule() {
        super("FPS", "Shows frames per second in the corner.", Category.HUD, -1);
        setEnabled(true); // on by default
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        ctx.label(mc.getFps() + " FPS", 4, 4, 0xFFFFFFFF);
    }
}
