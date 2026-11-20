package com.example.donutclient.bot.path;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Plans and follows paths toward a goal, replanning as the world reveals itself. */
public final class Nav {
    public enum Status { RUNNING, DONE, FAILED }

    private final Bot bot;
    private Goal goal;
    private Pathfinder search;
    private PathExecutor exec;
    private boolean execReachesGoal;
    private int failures;
    private int planTicks;
    private double bestH = Double.MAX_VALUE;
    private int noProgress;
    private int loadWait;
    private int noPillarPlans;
    public boolean allowBreak = true;
    /** While set, paths near this point prefer digging through solid over walking in the open. */
    public BlockPos coverCenter;
    public int coverRadius = 14;
    /** Open-air penalty in the End (the dragon only bothers what it can see). */
    public double endOpenCost = 22;
    public String info = "";

    public Nav(Bot bot) { this.bot = bot; }

    public void reset() {
        goal = null;
        search = null;
        exec = null;
        failures = 0;
        noProgress = 0;
        bestH = Double.MAX_VALUE;
        bot.act.stopBreaking();
    }

    public Goal goal() { return goal; }

    /** Call every tick with the same goal object until it stops returning RUNNING. */
    public Status goTo(Goal g) {
        if (g != goal) { reset(); goal = g; }
        LocalPlayer p = bot.player();
        BlockPos feet = startPos();
        if (exec == null && goal.at(feet.getX(), feet.getY(), feet.getZ()) && (p.onGround() || p.isInWater())) {
            return Status.DONE;
        }

        if (exec != null) {
            PathExecutor.Result r = exec.tick();
            if (r == PathExecutor.Result.RUNNING) return Status.RUNNING;
            if (r == PathExecutor.Result.FAILED) {
                info = "replan: " + exec.failReason;
                if (exec.failReason.startsWith("pillar")) noPillarPlans = 2;
                failures++;
            } else {
                failures = 0;
                if (execReachesGoal) { exec = null; return Status.DONE; }
            }
            exec = null;
            if (failures > 12) {
                bot.dbg("nav failed: " + info + " (" + goal + ")");
                return Status.FAILED;
            }
        }

        if (search == null) {
            if (!p.onGround() && !p.isInWater() && !p.getAbilities().flying) {
                if (++planTicks < 60) return Status.RUNNING; // wait to land
            }
            planTicks = 0;
            double h = goal.h(feet.getX(), feet.getY(), feet.getZ());
            if (h < bestH - 2) { bestH = h; noProgress = 0; } else if (++noProgress > 6) {
                bot.dbg("nav failed: no progress toward " + goal + " from " + feet.toShortString());
                return Status.FAILED;
            }
            search = new Pathfinder(bot.level(), feet, goal, bot.inv.tools(),
                    bot.inv.countThrowaway() > 2 && noPillarPlans-- <= 0, allowBreak, 40000);
            search.coverCenter = coverCenter;
            search.coverRadius = coverRadius;
            // The Nether's open spaces are full of lava and things that shoot; solid netherrack is safe.
            var dim = bot.level().dimension();
            search.openCost = dim == net.minecraft.world.level.Level.NETHER ? 16
                    : dim == net.minecraft.world.level.Level.END ? endOpenCost : 0;
        }
        if (bot.player().isInWater()) bot.input.jump = true; // tread water while thinking
        if (!search.step(12_000_000L)) return Status.RUNNING;

        List<BlockPos> path = search.path();
        execReachesGoal = search.reachedGoal();
        int expanded = search.expanded();
        search = null;
        if (path == null && expanded <= 4 && ++loadWait < 200) {
            return Status.RUNNING; // surroundings probably not loaded yet (just respawned / changed dimension)
        }
        loadWait = 0;
        if (path == null || path.size() < 2) {
            info = "no path to " + goal;
            if (++failures > 3) {
                bot.dbg("nav failed: no path from " + feet.toShortString() + " to " + goal);
                return Status.FAILED;
            }
            return Status.RUNNING;
        }
        exec = new PathExecutor(bot, path);
        return Status.RUNNING;
    }

    private BlockPos startPos() {
        BlockPos feet = bot.feet();
        int f = Blocks2.flags(bot.level(), feet, bot.level().getBlockState(feet));
        if ((f & (Blocks2.PASS | Blocks2.WATER)) == 0) {
            BlockPos up = feet.above();
            int fu = Blocks2.flags(bot.level(), up, bot.level().getBlockState(up));
            if ((fu & (Blocks2.PASS | Blocks2.WATER)) != 0) return up;
        }
        return feet;
    }
}
