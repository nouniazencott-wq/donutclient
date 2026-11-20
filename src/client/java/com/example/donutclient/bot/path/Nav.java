package com.example.donutclient.bot.path;

import java.util.List;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
	/** Consecutive failed path executions. */
	private int failures;
	/** Consecutive searches that found no path at all (counted apart from {@link #failures}). */
	private int noPath;
	/** Ticks spent waiting to land before planning. */
	private int planTicks;
	/** Best (lowest) heuristic seen at the start of a plan, for the no-progress watchdog. */
	private double bestH = Double.MAX_VALUE;
	/** Number of partial/failed plans started without getting meaningfully closer to the goal. */
	private int noProgress;
	/** Number of plans of any kind started since the bot last got meaningfully closer to the goal. */
	private int plansSinceProgress;
	/** Ticks spent waiting for chunks to load when the search could not expand at all. */
	private int loadWait;
	/** While positive, the next plans are made without pillaring (after a pillar move failed). */
	private int noPillarPlans;
	/** Path nodes at which a move failed twice in a row; the planner routes around them (for this goal only). */
	private final LongOpenHashSet avoid = new LongOpenHashSet();
	/** Packed position of the node the last failed move was heading for. */
	private long lastFailNode;
	private boolean hasLastFailNode;

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
		clearCounters();
		// Also drop the wait timers and the pillar ban, so nothing stale leaks into the next goal.
		planTicks = 0;
		loadWait = 0;
		noPillarPlans = 0;
		bot.act.stopBreaking();
	}

	/** Clears the failure counters, the no-progress watchdog and the avoided nodes. */
	private void clearCounters() {
		failures = 0;
		noPath = 0;
		noProgress = 0;
		plansSinceProgress = 0;
		bestH = Double.MAX_VALUE;
		avoid.clear();
		hasLastFailNode = false;
	}

	/**
	 * Reports failure for the current goal and starts over with clean counters, so that a caller which keeps
	 * (or later reuses) the same goal instance gets a fresh attempt instead of FAILED on every tick forever.
	 */
	private Status giveUp() {
		search = null;
		exec = null;
		clearCounters();
		return Status.FAILED;
	}

	/**
	 * Remembers where a move failed; when the next failure is at the same node it is handed to the planner as
	 * off-limits, so the replan takes another way instead of producing the identical failing path again.
	 */
	private void noteFailedNode(BlockPos node) {
		// Never rule out a node that is itself the goal.
		if (node == null || goal.at(node.getX(), node.getY(), node.getZ())) {
			return;
		}
		long key = node.asLong();
		if (hasLastFailNode && key == lastFailNode) {
			avoid.add(key);
		}
		lastFailNode = key;
		hasLastFailNode = true;
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
				String reason = exec.failReason;
				if (reason.startsWith("pillar") || reason.equals("cannot place")) {
					// Pillaring/bridging did not work here; plan the next two paths without placing blocks.
					noPillarPlans = 2;
				} else if (!reason.equals("off path") && !reason.startsWith("no blocks")) {
					// The move itself is the problem (stuck, lava, timeout...): avoid it if it fails again.
					noteFailedNode(exec.failedNode());
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
				return giveUp();
			}
		}

		if (search == null) {
			// Wait (up to 3 s) until we are standing on something before planning from here.
			if (!player.onGround() && !player.isInWater() && !player.getAbilities().flying && ++planTicks < 60) {
				return Status.RUNNING;
			}
			planTicks = 0;
			double h = goal.h(start.getX(), start.getY(), start.getZ());
			if (Double.isNaN(h) || h >= Double.MAX_VALUE) {
				// A goal with no target at all (e.g. Goal.any of an empty list): searching would be pointless.
				bot.dbg("nav failed: goal has no target (" + goal + ")");
				return giveUp();
			}
			if (h < bestH - 2.0) {
				bestH = h;
				noProgress = 0;
				plansSinceProgress = 0;
			} else {
				// Watchdog: plans that find a complete path are not counted in noProgress (see below), so a
				// long detour away from the goal is allowed; the total number of plans stays capped.
				noProgress++;
				plansSinceProgress++;
				if (noProgress > 6 || plansSinceProgress > 30) {
					bot.dbg("nav failed: no progress toward " + goal + " from " + start.toShortString());
					return giveUp();
				}
			}
			boolean canPlace = bot.inv.countThrowaway() > 2;
			if (noPillarPlans > 0) {
				// Counts down to zero only (it used to run away into the negatives).
				noPillarPlans--;
				canPlace = false;
			}
			search = new Pathfinder(bot.level(), start, goal, bot.inv.tools(), canPlace, allowBreak, 40000);
			search.avoid = avoid.isEmpty() ? null : avoid;
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
			// These one-tick retries must not feed the no-progress watchdog, which otherwise ended the
			// 10 s wait after only a handful of ticks.
			if (noProgress > 0) {
				noProgress--;
			}
			if (plansSinceProgress > 0) {
				plansSinceProgress--;
			}
			return Status.RUNNING;
		}
		loadWait = 0;
		if (path == null || path.size() < 2) {
			info = "no path to " + goal;
			if (!avoid.isEmpty()) {
				// The avoided nodes may be the only way through: forget them and search once more.
				avoid.clear();
				hasLastFailNode = false;
				return Status.RUNNING;
			}
			if (++noPath > 3) {
				bot.dbg("nav failed: no path from " + start.toShortString() + " to " + goal);
				return giveUp();
			}
			return Status.RUNNING;
		}
		noPath = 0;
		if (execReachesGoal && noProgress > 0) {
			// A complete path exists, so being further from the goal than before is a detour, not a dead end.
			// If walking it keeps failing, the execution failure counter ends the attempt instead.
			noProgress--;
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
