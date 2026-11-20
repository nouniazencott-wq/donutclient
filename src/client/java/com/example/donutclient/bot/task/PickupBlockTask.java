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
		// phase 2: walk onto the dropped item
		ItemEntity drop = null;
		for (Entity e : bot.level().entitiesForRendering()) {
			if (e instanceof ItemEntity ie && ie.getItem().is(item) && ie.blockPosition().distSqr(pos) < 36.0) {
				drop = ie;
			}
		}
		if (drop == null) {
			return ticks > 40;
		}
		BlockPos dp = drop.blockPosition();
		if (goal == null || !goal.at(dp.getX(), dp.getY(), dp.getZ())) {
			goal = Goal.near(dp, 0.5);
		}
		if (bot.nav.goTo(goal) == Nav.Status.FAILED) {
			return true;
		}
		return false;
	}
}
