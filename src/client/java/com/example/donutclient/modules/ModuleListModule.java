package com.example.donutclient.modules;

import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.example.donutclient.module.ModuleManager;

import java.util.ArrayList;
import java.util.List;

/** Top-right list of everything that is switched on. */
public class ModuleListModule extends Module {
    public ModuleListModule() {
        super("Module List", "Lists enabled modules in the top-right corner.", Category.HUD, -1);
        setEnabled(true);
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        List<String> names = new ArrayList<>();
        for (Module m : ModuleManager.get().all()) {
            if (m.isEnabled() && m.getCategory() != Category.HUD) names.add(m.getName());
        }
        names.sort((a, b) -> ctx.textWidth(b) - ctx.textWidth(a));
        int y = 4;
        for (String n : names) {
            ctx.label(n, ctx.screenWidth - ctx.textWidth(n) - 4, y, 0xFFFFAA00);
            y += ctx.font.lineHeight + 4;
        }
    }
}
