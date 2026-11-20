package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Memory;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;

/** After a death: walks back to where the bot died and picks its dropped items up again. */
public final class RecoverItemsTask extends Task {
	private Goal goal;
	private final DropCollector drops;
	/** Ticks spent at the death spot without anything left to collect. */
	private int idle;
	private int ticks;

	public RecoverItemsTask(Bot bot) {
		super(bot);
		drops = new DropCollector(bot, s -> true);
		drops.range = 14.0;
	}

	/**
	 * Whether a recovery run is worthwhile: the bot died in the current dimension, within the last 6 minutes
	 * (items despawn after 5), within 250 blocks, and it did not already die again near or on the way to that spot.
	 */
	public static boolean pending(Bot bot) {
		Memory m = bot.memory;
		if (m.deathPos == null) {
			return false;
		}
		if (m.deathDim != bot.level().dimension() || m.deathsNearby >= 1 || bot.tick - m.deathTick > 7200
				|| m.diedRecovering || m.deathPos.distSqr(bot.feet()) > 62500.0) {
			return false;
		}
		return true;
	}

	@Override
	public boolean tick() {
		Memory m = bot.memory;
		if (!pending(bot) || ++ticks > 7200) {
			m.deathPos = null;
			m.recovering = false;
			return true;
		}
		m.recovering = true;
		bot.setStatus("going back for my items at " + m.deathPos.toShortString());
		if (bot.feet().distSqr(m.deathPos) < 64.0) {
			if (drops.tick()) {
				idle = 0;
				return false;
			}
			// nothing to pick up for 3 seconds: everything is collected (or gone)
			if (++idle > 60) {
				m.deathPos = null;
				m.recovering = false;
				return true;
			}
			if (bot.feet().distSqr(m.deathPos) < 9.0) {
				return false;
			}
		}
		if (goal == null) {
			goal = Goal.near(m.deathPos, 2.0);
		}
		Nav.Status st = bot.nav.goTo(goal);
		if (st == Nav.Status.FAILED) {
			m.deathPos = null;
			m.recovering = false;
			return true;
		}
		return false;
	}
}
