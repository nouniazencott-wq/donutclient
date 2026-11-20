package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Finds blocks of a given kind, walks to them, breaks them and collects the drops until a caller-supplied
 * condition is met. When no matching block is in the scanned area the bot explores: towards an optional
 * locate hint, otherwise in a straight line along a pseudo-random compass heading.
 */
public final class MineTask extends Task {
    private final String what;
    private final Predicate<BlockState> block;
    private final Predicate<ItemStack> drop;
    private final BooleanSupplier done;
    /** If true the target is reached by pathing into it (the pathfinder digs); otherwise it is broken from reach. */
    private final boolean walkInto;
    /** Scan radius passed to {@link com.example.donutclient.bot.Scan#find}. */
    public int scanRadius = 8;
    public int minY = Integer.MIN_VALUE;
    public int maxY = Integer.MAX_VALUE;
    /** Extra position filter for candidates. */
    public Predicate<BlockPos> where = p -> true;
    private List<BlockPos> candidates = new ArrayList<>();
    /** Ticks after which a blacklisted target may be tried again (5 minutes). */
    private static final long BLACKLIST_TICKS = 6000L;
    /** Ticks to wait for the locate hint before exploring without it. */
    private static final int HINT_WAIT_TICKS = 200;
    /** Targets that could not be reached or broken, with the bot tick at which they were given up. */
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private BlockPos target;
    private Goal goal;
    private long lastScan = -1000L;
    private Goal exploreGoal;
    /** Exploration direction in radians; negative = not chosen yet. */
    private double heading = -1.0;
    /** Optional supplier of an asynchronous locate query pointing at where the block is likely to be found. */
    public Supplier<Locate.Query> hint;
    private Locate.Query hintQuery;
    private int hintUses;
    /** Ticks spent waiting for a pending {@link #hintQuery}. */
    private int hintWait;
    private int exploreTicks;
    private final DropCollector drops;
    private int breakTicks;
    private int targetTicks;

    /**
     * @param what     name shown in the status line
     * @param block    which blocks to mine
     * @param drop     which item drops to pick up
     * @param done     the task finishes as soon as this returns true
     * @param walkInto path into the block instead of breaking it from a distance
     */
    public MineTask(Bot bot, String what, Predicate<BlockState> block, Predicate<ItemStack> drop, BooleanSupplier done,
            boolean walkInto) {
        super(bot);
        this.what = what;
        this.block = block;
        this.drop = drop;
        this.done = done;
        this.walkInto = walkInto;
        this.drops = new DropCollector(bot, drop);
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        target = null;
        goal = null;
        exploreGoal = null;
        drops.reset();
    }

    @Override
    public boolean tick() {
        if (done.getAsBoolean()) {
            bot.nav.reset();
            return true;
        }
        if (!bot.act.breaking && drops.tick()) {
            bot.setStatus("picking up " + what);
            return false;
        }
        // Target is gone (mined, or changed): pick a new one.
        if (target != null && !block.test(bot.level().getBlockState(target))) {
            target = null;
            goal = null;
        }
        if (target == null) {
            target = pickTarget();
            targetTicks = 0;
            breakTicks = 0;
            if (target == null) {
                explore();
                return false;
            }
            exploreGoal = null;
            goal = walkInto
                    ? Goal.any(List.of(Goal.block(target), Goal.block(target.below())))
                    : Goal.reach(target, 4.0);
        }
        bot.setStatus("mining " + what + " at " + target.toShortString());
        if (++targetTicks > 2400) {
            giveUp();
            return false;
        }
        if (!walkInto) {
            BlockPos f = bot.feet();
            boolean settled = bot.player().onGround() || bot.player().isInWater();
            if (settled && goal.at(f.getX(), f.getY(), f.getZ()) && bot.act.inReach(target)) {
                bot.nav.reset();
                if (++breakTicks > 400) {
                    giveUp();
                    return false;
                }
                bot.act.breakBlock(target);
                return false;
            }
        }
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) {
            giveUp();
        } else if (st == Nav.Status.DONE && walkInto && block.test(bot.level().getBlockState(target))) {
            // Arrived but the block is still there: break it by hand if possible.
            if (bot.act.inReach(target)) {
                bot.act.breakBlock(target);
            } else {
                giveUp();
            }
        }
        return false;
    }

    /** Blacklists the current target and drops it. */
    private void giveUp() {
        if (target != null) {
            blacklist.put(target, bot.tick);
        }
        target = null;
        goal = null;
        bot.nav.reset();
    }

    /**
     * Returns the cheapest known candidate (horizontal distance plus twice the height difference) and removes
     * it from the list, rescanning the world at most every 60 ticks when the list is empty. Null if none.
     */
    private BlockPos pickTarget() {
        // unsafe() is checked again here: mining a neighbour may have uncovered lava next to a candidate
        candidates.removeIf(p -> banned(p) || !block.test(bot.level().getBlockState(p)) || unsafe(p));
        if (candidates.isEmpty() && bot.tick - lastScan > 60L) {
            lastScan = bot.tick;
            candidates = bot.scan.find(s -> block.test(s), scanRadius, 64, minY, maxY);
            candidates.removeIf(p -> banned(p) || unsafe(p) || bot.memory.dangerous(p) || !where.test(p));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        BlockPos f = bot.feet();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : candidates) {
            double d = Math.abs(p.getX() - f.getX()) + Math.abs(p.getZ() - f.getZ()) + 2.0 * Math.abs(p.getY() - f.getY());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        candidates.remove(best);
        return best;
    }

    /**
     * True while {@code p} is blacklisted. Entries expire after {@link #BLACKLIST_TICKS}, so a block that was out
     * of reach once (mob in the way, path budget, ...) is not written off for the rest of the task.
     */
    private boolean banned(BlockPos p) {
        Long since = blacklist.get(p);
        if (since == null) {
            return false;
        }
        if (bot.tick - since > BLACKLIST_TICKS) {
            blacklist.remove(p);
            return false;
        }
        return true;
    }

    /**
     * True if breaking the block could hurt: lava touches it on any side (including below, where the bot would
     * drop into it after digging the block out), or water lies on top of it (the block is under water). Water
     * merely next to the block is harmless and no longer rules it out.
     */
    private boolean unsafe(BlockPos p) {
        for (Direction d : Direction.values()) {
            FluidState fs = bot.level().getBlockState(p.relative(d)).getFluidState();
            if (fs.isEmpty()) {
                continue;
            }
            if (fs.is(FluidTags.LAVA) || d == Direction.UP) {
                return true;
            }
        }
        return false;
    }

    /** Nothing to mine nearby: travel somewhere else so the next scan sees new terrain. */
    private void explore() {
        bot.setStatus("searching for " + what);
        if (hint != null && hintQuery == null) {
            hintQuery = hint.get();
            hintWait = 0;
        }
        if (exploreGoal == null || ++exploreTicks > 2400) {
            exploreTicks = 0;
            BlockPos f = bot.feet();
            BlockPos far = hintQuery != null && hintQuery.ready() ? hintQuery.result() : null;
            if (far != null && far.distSqr(f) > 900.0 && hintUses++ < 6) {
                exploreGoal = Goal.xz(far.getX(), far.getZ(), 12.0);
            } else {
                // Wait for a pending locate query before wandering off.
                if (hintQuery != null && !hintQuery.ready()) {
                    if (++hintWait <= HINT_WAIT_TICKS) {
                        return;
                    }
                    // The query never answered: stop asking and explore blindly instead of idling forever.
                    hint = null;
                    hintQuery = null;
                }
                if (heading < 0.0) {
                    // One of eight compass directions, derived from the starting position.
                    heading = Math.floorMod(f.getX() * 31 + f.getZ(), 8) * Math.PI / 4.0;
                }
                exploreGoal = Goal.xz(f.getX() + (int) (Math.cos(heading) * 96.0), f.getZ() + (int) (Math.sin(heading) * 96.0),
                        8.0);
            }
        }
        Nav.Status st = bot.nav.goTo(exploreGoal);
        if (st == Nav.Status.FAILED) {
            // Blocked: turn by a bit more than a quarter circle.
            heading += Math.PI / 2 + 0.4;
        }
        if (st != Nav.Status.RUNNING) {
            exploreGoal = null;
            hintQuery = null;
        }
        // Force a fresh scan while travelling.
        if (bot.tick - lastScan > 100L) {
            candidates.clear();
        }
    }
}
