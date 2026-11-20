package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Helper (not a task) that walks the bot onto nearby dropped items matching a predicate.
 * Call {@link #tick()} every tick; while it returns true it is driving the bot towards a drop.
 */
public final class DropCollector {
    private final Bot bot;
    private final Predicate<ItemStack> wanted;
    /** Entity ids of drops we gave up on (unreachable, timed out, or inventory full). */
    private final IntOpenHashSet ignored = new IntOpenHashSet();
    private ItemEntity target;
    private int ticks;
    private Goal goal;
    private BlockPos goalPos;
    /** Maximum distance (blocks) at which a drop is considered. */
    public double range = 10.0;

    public DropCollector(Bot bot, Predicate<ItemStack> wanted) {
        this.bot = bot;
        this.wanted = wanted;
    }

    /** Forgets the current target and path goal (the ignore list is kept). */
    public void reset() {
        target = null;
        goal = null;
    }

    /**
     * Picks the nearest wanted drop if needed and moves towards it.
     *
     * @return true while a drop is being collected, false when there is nothing (left) to collect
     */
    public boolean tick() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            target = null;
            double best = range * range;
            for (Entity e : bot.level().entitiesForRendering()) {
                if (e instanceof ItemEntity it && !ignored.contains(e.getId()) && wanted.test(it.getItem())) {
                    double d = it.distanceToSqr(bot.player());
                    if (d < best) {
                        best = d;
                        target = it;
                    }
                }
            }
            ticks = 0;
            goal = null;
            if (target == null) return false;
        }
        // give up on a drop after 12 seconds, or when there is no room to pick it up
        if (++ticks > 240 || bot.inv.freeSlots() == 0) {
            ignored.add(target.getId());
            target = null;
            return false;
        }
        double dx = target.getX() - bot.player().getX();
        double dz = target.getZ() - bot.player().getZ();
        double dy = target.getY() - bot.player().getY();
        if (dx * dx + dz * dz < 6.25 && Math.abs(dy) < 1.3) {
            // close enough: skip pathfinding and just walk straight onto it
            bot.nav.reset();
            bot.act.faceXZ(target.getX(), target.getZ());
            bot.input.forward = true;
            if (bot.player().horizontalCollision && bot.player().onGround()) bot.input.jump = true;
            if (bot.player().isInWater()) bot.input.jump = true;
            return true;
        }
        BlockPos p = target.blockPosition();
        // re-plan only when the drop has moved noticeably
        if (goal == null || goalPos.distSqr(p) > 1.0) {
            goalPos = p;
            goal = Goal.near(p, 1.5);
        }
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) {
            ignored.add(target.getId());
            target = null;
        }
        return true;
    }
}
