package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Finds a nearby free position where the bot can put down a utility block (crafting table, furnace, ...). */
public final class PlaceSpot {
	private PlaceSpot() {
	}

	/**
	 * Searches the 5x5 columns around the bot (at feet and head height) for the closest spot that is empty, has a
	 * solid plain block underneath, does not overlap the player and is within 4 blocks of the eyes.
	 *
	 * @return the best position, or null if there is none
	 */
	public static BlockPos find(Bot bot) {
		BlockPos feet = bot.feet();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dy = 0; dy <= 1; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (dx == 0 && dz == 0) {
						continue;
					}
					BlockPos p = feet.offset(dx, dy, dz);
					BlockState s = bot.level().getBlockState(p);
					// the spot itself must be air or a replaceable non-fluid (grass, snow layer, ...)
					if (!s.isAir() && !(s.canBeReplaced() && s.getFluidState().isEmpty())) {
						continue;
					}
					BlockState below = bot.level().getBlockState(p.below());
					if (below.isAir() || below.canBeReplaced()) {
						continue;
					}
					// never build on top of chests, furnaces and the like
					if (below.hasBlockEntity()) {
						continue;
					}
					if (bot.player().getBoundingBox().inflate(0.3).intersects(new AABB(p))) {
						continue;
					}
					// slightly prefer spots at feet height
					double d = bot.act.eyeDist(p) + dy * 0.5;
					if (d < bestD && d < 4.0) {
						bestD = d;
						best = p;
					}
				}
			}
		}
		return best;
	}
}
