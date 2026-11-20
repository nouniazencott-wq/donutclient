package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** Walks to nearby item drops; gives up on ones it cannot get. */
public final class DropCollector {
    private final Bot bot;
    private final Predicate<ItemStack> wanted;
    private final IntOpenHashSet ignored = new IntOpenHashSet();
    private ItemEntity target;
    private int ticks;
    private Goal goal;
    private BlockPos goalPos;
    public double range = 10;

    public DropCollector(Bot bot, Predicate<ItemStack> wanted) {
        this.bot = bot;
        this.wanted = wanted;
    }

    public void reset() { target = null; goal = null; }

    /** @return true while it is busy fetching something (the caller should not act this tick) */
    public boolean tick() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            target = null;
            double bestD = range * range;
            for (Entity e : bot.level().entitiesForRendering()) {
                if (e instanceof ItemEntity ie && !ignored.contains(e.getId()) && wanted.test(ie.getItem())) {
                    double d = ie.distanceToSqr(bot.player());
                    if (d < bestD) { bestD = d; target = ie; }
                }
            }
            ticks = 0;
            goal = null;
            if (target == null) return false;
        }
        if (++ticks > 240 || bot.inv.freeSlots() == 0) { ignored.add(target.getId()); target = null; return false; }
        double dx = target.getX() - bot.player().getX(), dz = target.getZ() - bot.player().getZ();
        double dy = target.getY() - bot.player().getY();
        if (dx * dx + dz * dz < 2.5 * 2.5 && Math.abs(dy) < 1.3) {
            // Close: just walk onto it.
            bot.nav.reset();
            bot.act.faceXZ(target.getX(), target.getZ());
            bot.input.forward = true;
            if (bot.player().horizontalCollision && bot.player().onGround()) bot.input.jump = true;
            if (bot.player().isInWater()) bot.input.jump = true;
            return true;
        }
        BlockPos ip = target.blockPosition();
        if (goal == null || goalPos.distSqr(ip) > 1) { goalPos = ip; goal = Goal.near(ip, 1.5); }
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) { ignored.add(target.getId()); target = null; }
        return true;
    }
}
