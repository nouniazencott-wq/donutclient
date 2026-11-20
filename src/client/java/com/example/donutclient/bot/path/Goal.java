package com.example.donutclient.bot.path;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * A pathfinding target: a predicate saying which feet positions count as "arrived" plus an admissible-ish
 * heuristic (in ticks) estimating the remaining travel cost from a position.
 */
public interface Goal {
	/** Cost in ticks of sprinting one block horizontally; the unit of the heuristic. */
	double SPRINT = 3.56;

	/** True when a player standing with its feet at (x, y, z) satisfies the goal. */
	boolean at(int x, int y, int z);

	/** Estimated remaining cost in ticks from (x, y, z). */
	double h(int x, int y, int z);

	/**
	 * Travel-cost estimate for a displacement: octile distance on the horizontal plane at sprint speed, plus a
	 * per-block cost for climbing (expensive) or descending (cheap).
	 */
	static double metric(int dx, int dy, int dz) {
		dx = Math.abs(dx);
		dz = Math.abs(dz);
		double horizontal = (Math.max(dx, dz) - Math.min(dx, dz)) + Math.min(dx, dz) * 1.414;
		return horizontal * SPRINT + (dy > 0 ? dy * 6.0 : -dy * 1.5);
	}

	/** Stand exactly in the given block. */
	static Goal block(BlockPos pos) {
		final int gx = pos.getX();
		final int gy = pos.getY();
		final int gz = pos.getZ();
		return new Goal() {
			@Override
			public boolean at(int x, int y, int z) {
				return x == gx && y == gy && z == gz;
			}

			@Override
			public double h(int x, int y, int z) {
				return metric(gx - x, gy - y, gz - z);
			}

			@Override
			public String toString() {
				return "block " + gx + "," + gy + "," + gz;
			}
		};
	}

	/** Stand anywhere within {@code r} blocks (euclidean, block coordinates) of the given block. */
	static Goal near(BlockPos pos, double r) {
		final int gx = pos.getX();
		final int gy = pos.getY();
		final int gz = pos.getZ();
		final double r2 = r * r;
		return new Goal() {
			@Override
			public boolean at(int x, int y, int z) {
				double dx = x - gx;
				double dy = y - gy;
				double dz = z - gz;
				return dx * dx + dy * dy + dz * dz <= r2;
			}

			@Override
			public double h(int x, int y, int z) {
				return Math.max(0, metric(gx - x, gy - y, gz - z) - r * SPRINT);
			}

			@Override
			public String toString() {
				return "near " + gx + "," + gy + "," + gz + " r" + r;
			}
		};
	}

	/**
	 * Stand somewhere from which the given block is within interaction range of the player's eyes, but never
	 * inside the block itself (neither feet nor head in it).
	 */
	static Goal reach(BlockPos pos, double range) {
		final double cx = pos.getX() + 0.5;
		final double cy = pos.getY() + 0.5;
		final double cz = pos.getZ() + 0.5;
		final int gx = pos.getX();
		final int gy = pos.getY();
		final int gz = pos.getZ();
		final double r2 = range * range;
		return new Goal() {
			@Override
			public boolean at(int x, int y, int z) {
				// Standing in the target block (feet or head) does not count.
				if (x == gx && z == gz && (y == gy || y == gy - 1)) {
					return false;
				}
				// Distance from the eye position (feet + 1.62) to the block centre.
				double dx = x + 0.5 - cx;
				double dy = y + 1.62 - cy;
				double dz = z + 0.5 - cz;
				return dx * dx + dy * dy + dz * dz <= r2;
			}

			@Override
			public double h(int x, int y, int z) {
				return Math.max(0, metric(gx - x, gy - 1 - y, gz - z) - range * SPRINT);
			}

			@Override
			public String toString() {
				return "reach " + gx + "," + gy + "," + gz;
			}
		};
	}

	/** Get within {@code r} blocks horizontally of the column (gx, gz), at any height. */
	static Goal xz(final int gx, final int gz, final double r) {
		final double r2 = r * r;
		return new Goal() {
			@Override
			public boolean at(int x, int y, int z) {
				double dx = x - gx;
				double dz = z - gz;
				return dx * dx + dz * dz <= r2;
			}

			@Override
			public double h(int x, int y, int z) {
				return Math.max(0, metric(gx - x, 0, gz - z) - r * SPRINT);
			}

			@Override
			public String toString() {
				return "xz " + gx + "," + gz;
			}
		};
	}

	/** Reach the given Y level anywhere. */
	static Goal y(final int gy) {
		return new Goal() {
			@Override
			public boolean at(int x, int y, int z) {
				return y == gy;
			}

			@Override
			public double h(int x, int y, int z) {
				return metric(0, gy - y, 0);
			}

			@Override
			public String toString() {
				return "y " + gy;
			}
		};
	}

	/** Satisfied by any of the given goals; heuristic is the minimum of theirs. */
	static Goal any(final List<Goal> goals) {
		return new Goal() {
			@Override
			public boolean at(int x, int y, int z) {
				for (Goal g : goals) {
					if (g.at(x, y, z)) {
						return true;
					}
				}
				return false;
			}

			@Override
			public double h(int x, int y, int z) {
				double best = Double.MAX_VALUE;
				for (Goal g : goals) {
					best = Math.min(best, g.h(x, y, z));
				}
				return best;
			}

			@Override
			public String toString() {
				return "any of " + goals.size();
			}
		};
	}
}
