package com.example.donutclient.bot.path;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static com.example.donutclient.bot.Blocks2.*;

/** Walks a path produced by {@link Pathfinder}, breaking and placing blocks as needed. */
public final class PathExecutor {
    public enum Result { RUNNING, DONE, FAILED }

    private final Bot bot;
    private final List<BlockPos> path;
    private int idx;
    private int offPath;
    private int stuck;
    private int moveTicks;
    private int placeFails;
    private Vec3 lastPos;
    public String failReason = "";

    public PathExecutor(Bot bot, List<BlockPos> path) {
        this.bot = bot;
        this.path = path;
    }

    public BlockPos end() { return path.get(path.size() - 1); }

    private int flags(BlockPos p) {
        return Blocks2.flags(bot.level(), p, bot.level().getBlockState(p));
    }

    private boolean matches(BlockPos feet, BlockPos node, boolean inWater) {
        if (feet.equals(node)) return true;
        return inWater && feet.getX() == node.getX() && feet.getZ() == node.getZ()
                && Math.abs(feet.getY() - node.getY()) <= 1;
    }

    private Result fail(String why) {
        failReason = why;
        if (idx + 1 < path.size()) {
            bot.dbg("path step failed (" + why + ") " + path.get(idx).toShortString() + " -> " + path.get(idx + 1).toShortString()
                    + " at " + bot.feet().toShortString());
        }
        bot.act.stopBreaking();
        return Result.FAILED;
    }

    public Result tick() {
        LocalPlayer p = bot.player();
        BlockPos feet = bot.feet();
        boolean inWater = p.isInWater();
        boolean settled = p.onGround() || inWater;

        if (settled) {
            for (int i = Math.min(path.size() - 1, idx + 4); i > idx; i--) {
                if (matches(feet, path.get(i), inWater)) { idx = i; moveTicks = 0; placeFails = 0; break; }
            }
        }
        if (idx >= path.size() - 1) { bot.act.stopBreaking(); return Result.DONE; }

        BlockPos a = path.get(idx), b = path.get(idx + 1);
        if (settled && !matches(feet, a, inWater) && !matches(feet, b, inWater)) {
            if (++offPath > 15) {
                for (int i = 0; i < path.size(); i++) {
                    if (matches(feet, path.get(i), inWater)) { idx = i; offPath = 0; return Result.RUNNING; }
                }
                return fail("off path");
            }
        } else offPath = 0;

        if (++moveTicks > 600) return fail("move timeout");

        int dx = b.getX() - a.getX(), dy = b.getY() - a.getY(), dz = b.getZ() - a.getZ();
        boolean vertical = dx == 0 && dz == 0;

        // ---- blocks that must be cleared for this move ----
        List<BlockPos> clear = new ArrayList<>(4);
        if (vertical) {
            if (dy > 0) clear.add(a.above(2));
            else for (int y = a.getY() - 1; y >= b.getY(); y--) clear.add(new BlockPos(a.getX(), y, a.getZ()));
        } else if (dy > 0) {
            clear.add(a.above(2));
            clear.add(b.above());
            clear.add(b);
        } else if (dy == 0) {
            clear.add(b.above());
            clear.add(b);
        } else {
            clear.add(new BlockPos(b.getX(), a.getY() + 1, b.getZ()));
            for (int y = a.getY(); y >= b.getY(); y--) clear.add(new BlockPos(b.getX(), y, b.getZ()));
        }
        for (BlockPos c : clear) {
            int f = flags(c);
            if ((f & (PASS | WATER)) != 0) continue;
            if ((f & DANGER) != 0) return fail("danger in path");
            if ((f & BREAK) == 0) return fail("unbreakable in path");
            if (liquidNextTo(c)) return fail("liquid next to block");
            if (!bot.act.inReach(c)) break; // walk closer first
            // Digging straight down: stand in the middle of the block first.
            if (vertical && dy < 0 && !centered(a, 0.2)) { walkTo(a, false); return Result.RUNNING; }
            if (inWater) bot.input.jump = true;
            bot.act.breakBlock(c);
            stuck = 0;
            return Result.RUNNING;
        }
        bot.act.stopBreaking();

        // ---- pillar up ----
        if (vertical && dy > 0 && !inWater) {
            if ((flags(a) & WATER) != 0 || (flags(b) & WATER) != 0) { bot.input.jump = true; return Result.RUNNING; }
            if (bot.inv.countThrowaway() == 0) return fail("no blocks to pillar");
            if (!centered(a, 0.25) && p.onGround()) { walkTo(a, false); return Result.RUNNING; }
            if (moveTicks > 120) return fail("pillar stuck");
            if (p.onGround()) bot.input.jump = true;
            if (p.getY() >= a.getY() + 1.0) {
                bot.act.placeBlock(a);
            } else bot.act.lookAt(Vec3.atCenterOf(a.below()));
            return Result.RUNNING;
        }
        if (vertical) {
            // Swimming up/down or waiting to fall after digging.
            if (inWater && dy > 0) bot.input.jump = true;
            return checkStuck(p) ? fail("stuck") : Result.RUNNING;
        }

        // ---- bridging ----
        if (dy == 0) {
            BlockPos floor = b.below();
            int ff = flags(floor);
            if ((ff & STAND) == 0 && (flags(b) & WATER) == 0 && (ff & PASS) != 0) {
                if (bot.inv.countThrowaway() == 0) return fail("no blocks to bridge");
                if (!bot.act.placeBlock(floor)) {
                    if (++placeFails > 60) return fail("cannot place");
                    // Shuffle toward the edge so the target is in reach, without walking off.
                    bot.input.sneak = true;
                    if (!centered(a, 0.35)) walkTo(a, false);
                    else { bot.act.faceXZ(b.getX() + 0.5, b.getZ() + 0.5); bot.input.forward = true; bot.input.sneak = true; }
                    return Result.RUNNING;
                }
            }
        }

        // ---- move ----
        if (lavaAround(b, 1, 0)) return fail("lava moved into the path");
        boolean exposed = lavaAround(b, 1, 5) || lavaAround(a, 1, 5);
        boolean diagonal = dx != 0 && dz != 0;
        boolean sprint = dy == 0 && !inWater && p.getFoodData().getFoodLevel() > 6;
        if (dy < 0 || (idx + 2 < path.size() && path.get(idx + 2).getY() < b.getY())) sprint = false;
        walkTo(b, sprint);
        if (inWater) {
            if (dy >= 0 || p.getY() < b.getY() + 0.2) bot.input.jump = true;
        } else if (dy > 0 && p.onGround()) {
            bot.input.jump = true;
        }
        if (diagonal) bot.input.sprint = sprint;
        if (exposed && dy == 0 && !inWater) {
            // Edge of a drop into lava: creep, so the bot cannot walk off.
            bot.input.sprint = false;
            bot.input.sneak = true;
        }
        if (checkStuck(p)) {
            return fail("stuck");
        }
        return Result.RUNNING;
    }

    private boolean checkStuck(LocalPlayer p) {
        Vec3 pos = p.position();
        if (lastPos == null || pos.distanceToSqr(lastPos) > 0.04) { lastPos = pos; stuck = 0; return false; }
        stuck++;
        if (stuck > 25 && stuck % 10 < 3 && p.onGround()) bot.input.jump = true;
        return stuck > 80;
    }

    private boolean centered(BlockPos pos, double tol) {
        LocalPlayer p = bot.player();
        return Math.abs(p.getX() - (pos.getX() + 0.5)) < tol && Math.abs(p.getZ() - (pos.getZ() + 0.5)) < tol;
    }

    private void walkTo(BlockPos pos, boolean sprint) {
        bot.act.faceXZ(pos.getX() + 0.5, pos.getZ() + 0.5);
        bot.input.forward = true;
        bot.input.sprint = sprint;
    }

    /**
     * Lava next to the given feet position: at feet/head level when depth is 0,
     * otherwise also anywhere down to depth blocks below the neighbouring columns.
     */
    private boolean lavaAround(BlockPos pos, int r, int depth) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 1; dy >= -depth; dy--) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    BlockState s = bot.level().getBlockState(m);
                    if (s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return true;
                    if (dy < 0 && !s.isAir() && s.getFluidState().isEmpty()) break; // solid: nothing below matters
                }
            }
        }
        return false;
    }

    private boolean liquidNextTo(BlockPos c) {
        for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockState s = bot.level().getBlockState(c.relative(d));
            if (!s.getFluidState().isEmpty()) return true;
        }
        return false;
    }
}
