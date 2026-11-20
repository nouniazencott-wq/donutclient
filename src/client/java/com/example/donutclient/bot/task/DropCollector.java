package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
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
    /** Entity ids of drops we gave up on (unreachable or timed out); forgotten again every two minutes. */
    private final IntOpenHashSet ignored = new IntOpenHashSet();
    /** Bot tick at which {@link #ignored} was last emptied. */
    private long ignoredSince;
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

    /** True if the stack fits into the inventory: a slot is free, or a stack of the same item has room left. */
    private boolean canHold(ItemStack stack) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = bot.inv.get(i);
            if (s.isEmpty() || (s.getCount() < s.getMaxStackSize() && ItemStack.isSameItemSameComponents(s, stack))) {
                return true;
            }
        }
        return false;
    }

    /** True if the drop lies in or right above lava, where walking onto it would burn the bot. */
    private boolean inLava(ItemEntity it) {
        BlockPos p = it.blockPosition();
        return bot.level().getFluidState(p).is(FluidTags.LAVA) || bot.level().getFluidState(p.below()).is(FluidTags.LAVA);
    }

    /**
     * Picks the nearest wanted drop if needed and moves towards it.
     *
     * @return true while a drop is being collected, false when there is nothing (left) to collect
     */
    public boolean tick() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            target = null;
            // Give ignored drops another chance now and then: the way to them may be clear by now.
            if (bot.tick - ignoredSince > 2400L) {
                ignored.clear();
                ignoredSince = bot.tick;
            }
            double best = range * range;
            for (Entity e : bot.level().entitiesForRendering()) {
                // Drops that do not fit (not even onto an existing stack) or lie in lava are skipped, not ignored
                // for good: they become collectable as soon as there is room again.
                if (e instanceof ItemEntity it && !ignored.contains(e.getId()) && wanted.test(it.getItem())
                        && canHold(it.getItem()) && !inLava(it)) {
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
        // no room for it any more, or it slid into lava: let go of it for now
        if (!canHold(target.getItem()) || inLava(target)) {
            target = null;
            return false;
        }
        // give up on a drop after 12 seconds
        if (++ticks > 240) {
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
