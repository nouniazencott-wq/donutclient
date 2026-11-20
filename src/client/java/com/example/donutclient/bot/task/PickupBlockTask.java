package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;

/** Breaks a utility block (table, furnace) and collects the dropped item. */
public final class PickupBlockTask extends Task {
    private final BlockPos pos;
    private final Item item;
    private int ticks;
    private int startCount = -1;
    private Goal goal;

    public PickupBlockTask(Bot bot, BlockPos pos, Item item) {
        super(bot);
        this.pos = pos;
        this.item = item;
    }

    @Override
    public boolean interruptible() { return false; }

    @Override
    public boolean tick() {
        if (startCount < 0) startCount = bot.inv.count(item);
        if (++ticks > 400) return true;
        if (bot.inv.count(item) > startCount) return true;
        if (!bot.level().getBlockState(pos).isAir() && bot.level().getBlockState(pos).getBlock().asItem() == item) {
            if (bot.act.inReach(pos)) bot.act.breakBlock(pos);
            else if (bot.nav.goTo(goal == null ? goal = Goal.reach(pos, 3.5) : goal) == Nav.Status.FAILED) return true;
            return false;
        }
        ItemEntity drop = null;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof ItemEntity ie && ie.getItem().is(item) && ie.blockPosition().distSqr(pos) < 36) drop = ie;
        }
        if (drop == null) return ticks > 40;
        BlockPos dp = drop.blockPosition();
        if (goal == null || !goal.at(dp.getX(), dp.getY(), dp.getZ())) goal = Goal.near(dp, 0.5);
        if (bot.nav.goTo(goal) == Nav.Status.FAILED) return true;
        return false;
    }
}
