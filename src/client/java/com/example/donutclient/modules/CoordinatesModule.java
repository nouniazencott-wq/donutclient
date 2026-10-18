package com.example.donutclient.modules;

import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public class CoordinatesModule extends Module {
    public CoordinatesModule() {
        super("Coordinates", "Shows your XYZ position and facing direction.", Category.HUD, -1);
        setEnabled(true);
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        if (mc.player == null) return;

        BlockPos pos = mc.player.blockPosition();
        Direction facing = mc.player.getDirection();

        String line = String.format("XYZ: %d / %d / %d  (%s)",
                pos.getX(), pos.getY(), pos.getZ(), facing.getName());

        // Just under the FPS counter.
        ctx.label(line, 4, 4 + ctx.font.lineHeight + 6, 0xFFFFFFFF);
    }
}
