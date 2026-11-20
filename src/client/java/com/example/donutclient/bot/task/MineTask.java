package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Gathers blocks: finds matching blocks in loaded chunks, travels to them,
 * breaks them and picks up the drops, exploring when none are around.
 */
public final class MineTask extends Task {
    private final String what;
    private final Predicate<BlockState> block;
    private final Predicate<ItemStack> drop;
    private final BooleanSupplier done;
    /** If true the bot digs its way into the block; otherwise it breaks it from arm's reach. */
    private final boolean walkInto;
    public int scanRadius = 8;
    public int minY = Integer.MIN_VALUE, maxY = Integer.MAX_VALUE;
    /** Optional extra filter on candidate positions. */
    public Predicate<BlockPos> where = p -> true;

    private List<BlockPos> candidates = new ArrayList<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private BlockPos target;
    private Goal goal;
    private long lastScan = -1000;
    private Goal exploreGoal;
    private double heading = -1;
    /** Optional long-range lookup used when nothing is in sight. */
    public java.util.function.Supplier<Locate.Query> hint;
    private Locate.Query hintQuery;
    private int hintUses;
    private int exploreTicks;
    private Goal dropGoal;
    private int dropTicks;
    private int breakTicks;
    private int targetTicks;

    public MineTask(Bot bot, String what, Predicate<BlockState> block, Predicate<ItemStack> drop,
                    BooleanSupplier done, boolean walkInto) {
        super(bot);
        this.what = what;
        this.block = block;
        this.drop = drop;
        this.done = done;
        this.walkInto = walkInto;
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        target = null;
        goal = null;
        exploreGoal = null;
        dropGoal = null;
    }

    @Override
    public boolean tick() {
        if (done.getAsBoolean()) { bot.nav.reset(); return true; }

        // Pick up drops first.
        if (target == null || !bot.act.breaking) {
            ItemEntity item = nearestDrop();
            if (item != null && dropTicks < 200) {
                dropTicks++;
                BlockPos ip = item.blockPosition();
                if (dropGoal == null || !dropGoal.at(ip.getX(), ip.getY(), ip.getZ())) dropGoal = Goal.near(ip, 0.9);
                bot.setStatus("picking up " + what);
                Nav.Status st = bot.nav.goTo(dropGoal);
                if (st == Nav.Status.FAILED) dropTicks = 200;
                if (st != Nav.Status.DONE) return false;
            } else if (item == null) dropTicks = 0;
        }

        if (target != null && !block.test(bot.level().getBlockState(target))) { target = null; goal = null; }
        if (target == null) {
            target = pickTarget();
            targetTicks = 0;
            breakTicks = 0;
            if (target == null) { explore(); return false; }
            exploreGoal = null;
            goal = walkInto
                    ? Goal.any(List.of(Goal.block(target), Goal.block(target.below())))
                    : Goal.reach(target, 4.0);
        }
        bot.setStatus("mining " + what + " at " + target.toShortString());
        if (++targetTicks > 2400) { giveUp(); return false; }

        if (!walkInto) {
            BlockPos f = bot.feet();
            boolean settled = bot.player().onGround() || bot.player().isInWater();
            if (settled && goal.at(f.getX(), f.getY(), f.getZ()) && bot.act.inReach(target)) {
                bot.nav.reset();
                if (++breakTicks > 400) { giveUp(); return false; }
                bot.act.breakBlock(target);
                return false;
            }
        }
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) giveUp();
        else if (st == Nav.Status.DONE && walkInto) {
            // Standing in/under it: finish it off if it is somehow still there.
            if (block.test(bot.level().getBlockState(target))) {
                if (bot.act.inReach(target)) bot.act.breakBlock(target); else giveUp();
            }
        }
        return false;
    }

    private void giveUp() {
        if (target != null) blacklist.add(target);
        target = null;
        goal = null;
        bot.nav.reset();
    }

    private ItemEntity nearestDrop() {
        ItemEntity best = null;
        double bestD = 10 * 10;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof ItemEntity ie && drop.test(ie.getItem())) {
                double d = ie.distanceToSqr(bot.player());
                if (d < bestD) { bestD = d; best = ie; }
            }
        }
        return best;
    }

    private BlockPos pickTarget() {
        candidates.removeIf(p -> blacklist.contains(p) || !block.test(bot.level().getBlockState(p)));
        if (candidates.isEmpty() && bot.tick - lastScan > 60) {
            lastScan = bot.tick;
            candidates = bot.scan.find(s -> block.test(s), scanRadius, 64, minY, maxY);
            candidates.removeIf(p -> blacklist.contains(p) || unsafe(p) || bot.memory.dangerous(p) || !where.test(p));
        }
        if (candidates.isEmpty()) return null;
        BlockPos f = bot.feet();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : candidates) {
            // Vertical distance is more work than horizontal distance.
            double d = Math.abs(p.getX() - f.getX()) + Math.abs(p.getZ() - f.getZ()) + 2.0 * Math.abs(p.getY() - f.getY());
            if (d < bestD) { bestD = d; best = p; }
        }
        candidates.remove(best);
        return best;
    }

    private boolean unsafe(BlockPos p) {
        for (Direction d : Direction.values()) {
            if (d == Direction.DOWN) continue;
            if (!bot.level().getBlockState(p.relative(d)).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private void explore() {
        bot.setStatus("searching for " + what);
        if (hint != null && hintQuery == null) hintQuery = hint.get();
        if (exploreGoal == null || ++exploreTicks > 2400) {
            exploreTicks = 0;
            BlockPos f = bot.feet();
            BlockPos far = hintQuery != null && hintQuery.ready() ? hintQuery.result() : null;
            if (far != null && far.distSqr(f) > 30 * 30 && hintUses++ < 6) {
                exploreGoal = Goal.xz(far.getX(), far.getZ(), 12);
            } else {
                if (hintQuery != null && !hintQuery.ready()) return; // wait for the lookup
                if (heading < 0) heading = Math.floorMod(f.getX() * 31 + f.getZ(), 8) * Math.PI / 4;
                exploreGoal = Goal.xz(f.getX() + (int) (Math.cos(heading) * 96), f.getZ() + (int) (Math.sin(heading) * 96), 8);
            }
        }
        Nav.Status st = bot.nav.goTo(exploreGoal);
        if (st == Nav.Status.FAILED) heading += Math.PI / 2 + 0.4;
        if (st != Nav.Status.RUNNING) { exploreGoal = null; hintQuery = null; }
        if (bot.tick - lastScan > 100) candidates.clear();
    }
}
