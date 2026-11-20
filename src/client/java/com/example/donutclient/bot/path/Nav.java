package com.example.donutclient.bot.path;

import java.util.List;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * High-level navigation: owns the current {@link Goal}, runs the incremental {@link Pathfinder} search a few
 * milliseconds per tick, hands the resulting path to a {@link PathExecutor}, and replans when a path segment
 * ends or fails. Call {@link #goTo(Goal)} every tick with the same goal instance.
 */
public final class Nav {
	/** Result of one {@link #goTo(Goal)} tick. */
	public enum Status {
		RUNNING,
		DONE,
		FAILED
	}

	private final Bot bot;
	private Goal goal;
	/** Search in progress, or null. */
	private Pathfinder search;
	/** Path currently being walked, or null. */
	private PathExecutor exec;
	/** Whether the path being walked ends at the goal (false for a partial "best so far" path). */
	private boolean execReachesGoal;
	/** Consecutive failed plans / failed path executions. */
	private int failures;
	/** Ticks spent waiting to land before planning. */
	private int planTicks;
	/** Best (lowest) heuristic seen at the start of a plan, for the no-progress watchdog. */
	private double bestH = Double.MAX_VALUE;
	/** Number of plans started without getting meaningfully closer to the goal. */
	private int noProgress;
	/** Ticks spent waiting for chunks to load when the search could not expand at all. */
	private int loadWait;
	/** While positive, the next plans are made without pillaring (after a pillar move failed). */
	private int noPillarPlans;

	/** Whether paths may dig through blocks. */
	public boolean allowBreak = true;
	/** Optional centre of an area in which the pathfinder is told to stay under cover. */
	public BlockPos coverCenter;
	public int coverRadius = 14;
	/** Extra cost for open (exposed) nodes while in the End. */
	public double endOpenCost = 22.0;
	/** Human-readable status of the last replan/failure, for the HUD. */
	public String info = "";

	public Nav(Bot bot) {
		this.bot = bot;
	}

	/** Drops the goal, any search and path, and stops any block breaking in progress. */
	public void reset() {
		goal = null;
		search = null;
		exec = null;
		failures = 0;
		noProgress = 0;
		bestH = Double.MAX_VALUE;
		bot.act.stopBreaking();
	}

	public Goal goal() {
		return goal;
	}

	/**
	 * Advances navigation toward {@code g} by one tick. Passing a different goal instance than last tick
	 * resets all state.
	 */
	public Status goTo(Goal g) {
		if (g != goal) {
			reset();
			goal = g;
		}
		LocalPlayer player = bot.player();
		BlockPos start = startPos();
		if (exec == null && goal.at(start.getX(), start.getY(), start.getZ()) && (player.onGround() || player.isInWater())) {
			return Status.DONE;
		}

		if (exec != null) {
			PathExecutor.Result result = exec.tick();
			if (result == PathExecutor.Result.RUNNING) {
				return Status.RUNNING;
			}
			if (result == PathExecutor.Result.FAILED) {
				info = "replan: " + exec.failReason;
				if (exec.failReason.startsWith("pillar")) {
					// Pillaring did not work here; plan the next two paths without it.
					noPillarPlans = 2;
				}
				failures++;
			} else {
				failures = 0;
				if (execReachesGoal) {
					exec = null;
					return Status.DONE;
				}
			}
			exec = null;
			if (failures > 12) {
				bot.dbg("nav failed: " + info + " (" + goal + ")");
				return Status.FAILED;
			}
		}

		if (search == null) {
			// Wait (up to 3 s) until we are standing on something before planning from here.
			if (!player.onGround() && !player.isInWater() && !player.getAbilities().flying && ++planTicks < 60) {
				return Status.RUNNING;
			}
			planTicks = 0;
			double h = goal.h(start.getX(), start.getY(), start.getZ());
			if (h < bestH - 2.0) {
				bestH = h;
				noProgress = 0;
			} else if (++noProgress > 6) {
				bot.dbg("nav failed: no progress toward " + goal + " from " + start.toShortString());
				return Status.FAILED;
			}
			search = new Pathfinder(bot.level(), start, goal, bot.inv.tools(),
					bot.inv.countThrowaway() > 2 && noPillarPlans-- <= 0, allowBreak, 40000);
			search.coverCenter = coverCenter;
			search.coverRadius = coverRadius;
			ResourceKey<Level> dimension = bot.level().dimension();
			search.openCost = dimension == Level.NETHER ? 16.0 : dimension == Level.END ? endOpenCost : 0;
		}

		// Keep afloat while thinking.
		if (bot.player().isInWater()) {
			bot.input.jump = true;
		}
		// Spend at most 12 ms of search per tick.
		if (!search.step(12000000L)) {
			return Status.RUNNING;
		}
		List<BlockPos> path = search.path();
		execReachesGoal = search.reachedGoal();
		int expanded = search.expanded();
		search = null;
		// Almost nothing expanded: the surrounding chunks are probably not loaded yet; retry for up to 10 s.
		if (path == null && expanded <= 4 && ++loadWait < 200) {
			return Status.RUNNING;
		}
		loadWait = 0;
		if (path == null || path.size() < 2) {
			info = "no path to " + goal;
			if (++failures > 3) {
				bot.dbg("nav failed: no path from " + start.toShortString() + " to " + goal);
				return Status.FAILED;
			}
			return Status.RUNNING;
		}
		exec = new PathExecutor(bot, path);
		return Status.RUNNING;
	}

	/**
	 * Block to plan from: the feet block, or the one above when the feet block is not passable/water but the
	 * block above is (e.g. standing on a slab, soul sand or farmland).
	 */
	private BlockPos startPos() {
		BlockPos feet = bot.feet();
		int flags = Blocks2.flags(bot.level(), feet, bot.level().getBlockState(feet));
		if ((flags & (Blocks2.PASS | Blocks2.WATER)) == 0) {
			BlockPos above = feet.above();
			int aboveFlags = Blocks2.flags(bot.level(), above, bot.level().getBlockState(above));
			if ((aboveFlags & (Blocks2.PASS | Blocks2.WATER)) != 0) {
				return above;
			}
		}
		return feet;
	}
}
