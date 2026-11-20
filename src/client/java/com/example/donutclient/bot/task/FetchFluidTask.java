package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

/**
 * Fills one empty bucket with water or lava: finds an exposed source block with a safe place to stand next
 * to it, walks there and scoops it. Explores in a straight line when no source is in view. Finishes when the
 * filled-bucket count went up, or sets {@link #failed} when it cannot be done.
 */
public final class FetchFluidTask extends Task {
    private final TagKey<Fluid> fluid;
    /** The bucket item we expect to gain. */
    private final Item filled;
    private final int startCount;
    /** Optional restriction: only sources within {@link #aroundRadius} of this position are used (no exploring). */
    public BlockPos around;
    public int aroundRadius = 24;
    /** Sources that turned out to be unreachable or unscoopable. */
    private final Set<BlockPos> bad = new HashSet<>();
    private Spot spot;
    private Goal goal;
    private int tries;
    private int ticks;
    private Goal explore;
    /** Exploration heading in radians; negative until first chosen. */
    private double heading = -1.0;
    private long lastScan = -1000L;
    private List<Spot> spots = new ArrayList<>();
    public boolean failed;

    /** A fluid source block together with the feet position to scoop it from. */
    public record Spot(BlockPos source, BlockPos stand) {
    }

    public FetchFluidTask(Bot bot, boolean lava) {
        super(bot);
        this.fluid = lava ? FluidTags.LAVA : FluidTags.WATER;
        this.filled = lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        this.startCount = bot.inv.count(filled);
    }

    /**
     * Scans loaded chunks for source blocks of {@code fluid} that are open to the air and have a standable
     * block within reach, returning up to {@code max} of them (optionally only within {@code radius} of
     * {@code around}).
     */
    public static List<Spot> findSpots(Bot bot, TagKey<Fluid> fluid, BlockPos around, int radius, int max) {
        List<BlockPos> sources = bot.scan.find(state -> state.getFluidState().isSource()
                && state.getFluidState().is(fluid)
                && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty(), 8, 3000);
        List<Spot> result = new ArrayList<>();
        ClientLevel level = bot.level();
        for (BlockPos source : sources) {
            if (around != null && source.distSqr(around) > (double) radius * (double) radius) {
                continue;
            }
            if (!level.getBlockState(source.above()).isAir()) {
                continue;
            }
            BlockPos stand = null;
            double bestCost = Double.MAX_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos floor = source.offset(dx, dy, dz);
                        // Eye position relative to the centre of the source block.
                        double ex = dx;
                        double ey = (dy + 1) + 1.62 - 0.5;
                        double ez = dz;
                        double cost = Math.sqrt(ex * ex + ey * ey + ez * ez);
                        if (Math.abs(dx) + Math.abs(dz) > 1) {
                            // Strongly prefer standing directly beside the source.
                            cost += 3.0;
                        }
                        if (cost > 6.9 || cost >= bestCost) {
                            continue;
                        }
                        BlockState floorState = level.getBlockState(floor);
                        if ((Blocks2.flags(level, floor, floorState) & 2) == 0) {
                            continue;
                        }
                        if (!level.getBlockState(floor.above()).isAir() || !level.getBlockState(floor.above(2)).isAir()) {
                            continue;
                        }
                        bestCost = cost;
                        stand = floor.above();
                    }
                }
            }
            if (stand != null) {
                result.add(new Spot(source, stand));
            }
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        spot = null;
        goal = null;
    }

    @Override
    public boolean tick() {
        if (bot.inv.count(filled) > startCount) {
            bot.nav.reset();
            return true;
        }
        if (bot.inv.count(Items.BUCKET) == 0) {
            failed = true;
            return true;
        }
        String name = fluid == FluidTags.LAVA ? "lava" : "water";

        // Drop the current spot if its source disappeared (someone else took it, or it flowed away).
        if (spot != null) {
            BlockState state = bot.level().getBlockState(spot.source);
            if (!state.getFluidState().isSource() || !state.getFluidState().is(fluid)) {
                bot.dbg("source gone at " + spot.source.toShortString() + ": " + state);
                spot = null;
            }
        }

        if (spot == null) {
            if (spots.isEmpty() && bot.tick - lastScan > 80L) {
                lastScan = bot.tick;
                spots = findSpots(bot, fluid, around, aroundRadius, 40);
                spots.removeIf(s -> bad.contains(s.source) || bot.memory.dangerous(s.stand));
            }
            if (spots.isEmpty()) {
                if (around != null) {
                    failed = true;
                    return true;
                }
                // Nothing in view: walk 96 blocks along a heading, turning when the path fails.
                bot.setStatus("searching for " + name);
                BlockPos feet = bot.feet();
                if (explore == null) {
                    if (heading < 0.0) {
                        heading = Math.floorMod(feet.getX() * 13 + feet.getZ() * 7, 8) * Math.PI / 4.0;
                    }
                    explore = Goal.xz(feet.getX() + (int) (Math.cos(heading) * 96.0),
                            feet.getZ() + (int) (Math.sin(heading) * 96.0), 8.0);
                }
                Nav.Status status = bot.nav.goTo(explore);
                if (status == Nav.Status.FAILED) {
                    heading += Math.PI / 2 + 0.4;
                }
                if (status != Nav.Status.RUNNING) {
                    explore = null;
                }
                return false;
            }
            BlockPos feet = bot.feet();
            spots.sort((a, b) -> Double.compare(a.stand.distSqr(feet), b.stand.distSqr(feet)));
            spot = spots.remove(0);
            goal = Goal.block(spot.stand);
            tries = 0;
            ticks = 0;
        }

        bot.setStatus("fetching " + name + " at " + spot.source.toShortString());
        Nav.Status status = bot.nav.goTo(goal);
        if (status == Nav.Status.FAILED) {
            bot.dbg("cannot reach " + spot.stand.toShortString());
            bad.add(spot.source);
            spot = null;
            return false;
        }
        if (status != Nav.Status.DONE) {
            return false;
        }
        if (!bot.inv.hold(Items.BUCKET)) {
            return false;
        }
        if (!bot.aim.canScoop(spot.source) || tries > 5) {
            bot.dbg("cannot scoop " + spot.source.toShortString() + " from " + spot.stand.toShortString() + " tries=" + tries);
            bad.add(spot.source);
            spot = null;
            return false;
        }
        bot.act.lookAt(Vec3.atCenterOf(spot.source));
        if (bot.act.useItem()) {
            tries++;
        }
        return false;
    }
}
