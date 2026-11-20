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
    public boolean allowBreak = true;
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
                failures++;
            } else {
                failures = 0;
                if (execReachesGoal) { exec = null; return Status.DONE; }
            }
            exec = null;
            if (failures > 12) return Status.FAILED;
        }

        if (search == null) {
            if (!p.onGround() && !p.isInWater() && !p.getAbilities().flying) {
                if (++planTicks < 60) return Status.RUNNING; // wait to land
            }
            planTicks = 0;
            double h = goal.h(feet.getX(), feet.getY(), feet.getZ());
            if (h < bestH - 2) { bestH = h; noProgress = 0; } else if (++noProgress > 6) return Status.FAILED;
            search = new Pathfinder(bot.level(), feet, goal, bot.inv.tools(),
                    bot.inv.countThrowaway() > 2, allowBreak, 40000);
        }
        if (bot.player().isInWater()) bot.input.jump = true; // tread water while thinking
        if (!search.step(12_000_000L)) return Status.RUNNING;

        List<BlockPos> path = search.path();
        execReachesGoal = search.reachedGoal();
        search = null;
        if (path == null || path.size() < 2) {
            info = "no path to " + goal;
            return ++failures > 3 ? Status.FAILED : Status.RUNNING;
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
