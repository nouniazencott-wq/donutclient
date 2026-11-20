package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fills an empty bucket with water or lava from a source block at the edge of a pool. */
public final class FetchFluidTask extends Task {
    /** A source block and a bank position to stand on next to it. */
    public record Spot(BlockPos source, BlockPos stand) {}

    private final TagKey<Fluid> fluid;
    private final Item filled;
    private final int startCount;
    /** If set, only sources near this point are used. */
    public BlockPos around;
    public int aroundRadius = 24;

    private final Set<BlockPos> bad = new HashSet<>();
    private Spot spot;
    private Goal goal;
    private int tries;
    private int ticks;
    private Goal explore;
    private double heading = -1;
    private long lastScan = -1000;
    private List<Spot> spots = new ArrayList<>();
    public boolean failed;

    public FetchFluidTask(Bot bot, boolean lava) {
        super(bot);
        this.fluid = lava ? FluidTags.LAVA : FluidTags.WATER;
        this.filled = lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        this.startCount = bot.inv.count(filled);
    }

    /** Pool-edge spots among the loaded chunks, nearest first. */
    public static List<Spot> findSpots(Bot bot, TagKey<Fluid> fluid, BlockPos around, int radius, int max) {
        List<BlockPos> sources = bot.scan.find(s -> s.getFluidState().isSource() && s.getFluidState().is(fluid)
                && s.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(), 8, 3000);
        List<Spot> out = new ArrayList<>();
        var level = bot.level();
        for (BlockPos src : sources) {
            if (around != null && src.distSqr(around) > (double) radius * radius) continue;
            if (!level.getBlockState(src.above()).isAir()) continue;
            // Any bank within arm's reach will do; line of sight is verified on arrival.
            BlockPos bestStand = null;
            double bestD = Double.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos bank = src.offset(dx, dy, dz);
                        double ex = dx, ey = dy + 1 + 1.62 - 0.5, ez = dz;
                        double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
                        if (d > 3.9 || d >= bestD) continue;
                        BlockState bks = level.getBlockState(bank);
                        if ((Blocks2.flags(level, bank, bks) & Blocks2.STAND) == 0) continue;
                        if (!level.getBlockState(bank.above()).isAir() || !level.getBlockState(bank.above(2)).isAir()) continue;
                        bestD = d;
                        bestStand = bank.above();
                    }
                }
            }
            if (bestStand != null) out.add(new Spot(src, bestStand));
            if (out.size() >= max) break;
        }
        return out;
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        spot = null;
        goal = null;
    }

    @Override
    public boolean tick() {
        if (bot.inv.count(filled) > startCount) { bot.nav.reset(); return true; }
        if (bot.inv.count(Items.BUCKET) == 0) { failed = true; return true; }
        String name = fluid == FluidTags.LAVA ? "lava" : "water";
        if (spot != null) {
            BlockState s = bot.level().getBlockState(spot.source);
            if (!s.getFluidState().isSource() || !s.getFluidState().is(fluid)) {
                bot.dbg("source gone at " + spot.source.toShortString() + ": " + s);
                spot = null;
            }
        }
        if (spot == null) {
            if (spots.isEmpty() && bot.tick - lastScan > 80) {
                lastScan = bot.tick;
                spots = findSpots(bot, fluid, around, aroundRadius, 40);
                spots.removeIf(sp -> bad.contains(sp.source) || bot.memory.dangerous(sp.stand));
            }
            if (spots.isEmpty()) {
                if (around != null) { failed = true; return true; }
                bot.setStatus("searching for " + name);
                BlockPos f = bot.feet();
                if (explore == null) {
                    if (heading < 0) heading = Math.floorMod(f.getX() * 13 + f.getZ() * 7, 8) * Math.PI / 4;
                    explore = Goal.xz(f.getX() + (int) (Math.cos(heading) * 96), f.getZ() + (int) (Math.sin(heading) * 96), 8);
                }
                Nav.Status st = bot.nav.goTo(explore);
                if (st == Nav.Status.FAILED) heading += Math.PI / 2 + 0.4;
                if (st != Nav.Status.RUNNING) explore = null;
                return false;
            }
            BlockPos f = bot.feet();
            spots.sort((a, b) -> Double.compare(a.stand.distSqr(f), b.stand.distSqr(f)));
            spot = spots.remove(0);
            goal = Goal.block(spot.stand);
            tries = 0;
            ticks = 0;
        }
        bot.setStatus("fetching " + name + " at " + spot.source.toShortString());
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) {
            bot.dbg("cannot reach " + spot.stand.toShortString());
            bad.add(spot.source);
            spot = null;
            return false;
        }
        if (st != Nav.Status.DONE) return false;
        if (!bot.inv.hold(Items.BUCKET)) return false;
        if (!bot.aim.canScoop(spot.source) || tries > 5) {
            bot.dbg("cannot scoop " + spot.source.toShortString() + " from " + spot.stand.toShortString() + " tries=" + tries);
            bad.add(spot.source);
            spot = null;
            return false;
        }
        bot.act.lookAt(Vec3.atCenterOf(spot.source));
        if (bot.act.useItem()) tries++;
        return false;
    }
}
