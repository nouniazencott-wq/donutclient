package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

/** Breaks a block the bot placed earlier (crafting table, furnace, ...) and collects it again. */
public final class PickupBlockTask extends Task {
	private final BlockPos pos;
	private final Item item;
	private int ticks;
	/** How many of the item the bot carried when the task started; -1 until the first tick. */
	private int startCount = -1;
	private Goal goal;
	/** Ticks since the block is gone during which no dropped item was in sight. */
	private int lostTicks;
	/** The cell of the dropped item that {@link #goal} currently leads to; null while the goal is the block. */
	private BlockPos dropPos;

	public PickupBlockTask(Bot bot, BlockPos pos, Item item) {
		super(bot);
		this.pos = pos;
		this.item = item;
	}

	@Override
	public boolean interruptible() {
		return false;
	}

	@Override
	public boolean tick() {
		if (startCount < 0) {
			startCount = bot.inv.count(item);
		}
		// give up after 20 seconds
		if (++ticks > 400) {
			return true;
		}
		if (bot.inv.count(item) > startCount) {
			return true;
		}
		// phase 1: the block is still standing - break it
		if (!bot.level().getBlockState(pos).isAir() && bot.level().getBlockState(pos).getBlock().asItem() == item) {
			if (bot.act.inReach(pos)) {
				bot.act.breakBlock(pos);
			} else if (bot.nav.goTo(goal == null ? (goal = Goal.reach(pos, 3.5)) : goal) == Nav.Status.FAILED) {
				return true;
			}
			return false;
		}
		// phase 2: walk onto the dropped item (the nearest one, should there be several)
		ItemEntity drop = null;
		double best = Double.MAX_VALUE;
		for (Entity e : bot.level().entitiesForRendering()) {
			if (e instanceof ItemEntity ie && ie.getItem().is(item) && ie.blockPosition().distSqr(pos) < 36.0) {
				double d = ie.distanceToSqr(bot.player());
				if (d < best) {
					best = d;
					drop = ie;
				}
			}
		}
		if (drop == null) {
			// The item shows up a moment after the block breaks, so only give up once it has been missing for
			// two seconds (counted from now, not from the task start - breaking alone can take longer than that,
			// and the block used to be left behind then).
			return ++lostTicks > 40;
		}
		lostTicks = 0;
		BlockPos dp = drop.blockPosition();
		// (the "reach the block" goal of phase 1 must not be kept here: it can count a drop's cell as reached
		// while the bot stands too far away to pick the item up)
		if (goal == null || !dp.equals(dropPos)) {
			dropPos = dp;
			goal = Goal.near(dp, 0.5);
		}
		if (bot.nav.goTo(goal) == Nav.Status.FAILED) {
			return true;
		}
		return false;
	}
}
