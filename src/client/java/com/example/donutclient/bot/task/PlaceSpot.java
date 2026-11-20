package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Finds a free spot next to the player where a utility block can be placed. */
public final class PlaceSpot {
    private PlaceSpot() {}

    public static BlockPos find(Bot bot) {
        BlockPos feet = bot.feet();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos p = feet.offset(dx, dy, dz);
                    BlockState s = bot.level().getBlockState(p);
                    if (!s.isAir() && !(s.canBeReplaced() && s.getFluidState().isEmpty())) continue;
                    BlockState below = bot.level().getBlockState(p.below());
                    if (below.isAir() || below.canBeReplaced()) continue;
                    if (below.hasBlockEntity()) continue;
                    double d = bot.act.eyeDist(p) + dy * 0.5;
                    if (d < bestD && d < 4.0) { bestD = d; best = p; }
                }
            }
        }
        return best;
    }
}
