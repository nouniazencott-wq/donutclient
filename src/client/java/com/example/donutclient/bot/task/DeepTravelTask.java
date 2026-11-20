package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;

/**
 * Long-distance travel in the Nether done underground: digs down to a level below the lava sea and then
 * tunnels horizontally towards the target column, taking sideways detours when the pathfinder fails.
 */
public final class DeepTravelTask extends Task {
    /** Y level to dig down to before tunnelling. */
    private static final int DEPTH = 11;

    private final int tx;
    private final int tz;
    private final double radius;
    private Goal goal;
    /**
     * 0 = digging down to {@link #DEPTH}, -1 = detour to find another way down,
     * 1 = tunnelling to the target, -2 = sideways detour while tunnelling.
     */
    private int phase;
    private int fails;
    private int ticks;
    /** Smallest distance to the target seen while tunnelling; used to forgive old failures once we get closer. */
    private int bestDist = Integer.MAX_VALUE;
    /** Set when the task gave up (too many failed paths or timed out). */
    public boolean failed;

    public DeepTravelTask(Bot bot, int x, int z, double radius) {
        super(bot);
        this.tx = x;
        this.tz = z;
        this.radius = radius;
    }

    /** Horizontal distance (blocks) left to the target column. */
    public int remaining() {
        BlockPos f = bot.feet();
        return (int) Math.sqrt((double) (f.getX() - tx) * (f.getX() - tx) + (double) (f.getZ() - tz) * (f.getZ() - tz));
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        goal = null;
    }

    @Override
    public boolean tick() {
        BlockPos f = bot.feet();
        int dist = remaining();
        if (dist <= radius) { bot.nav.reset(); return true; }
        if (++ticks > 48000) { failed = true; return true; }   // 40 minutes

        // already close, or already deep enough: skip the descent
        if (phase == 0 && (dist < 48 || f.getY() <= DEPTH + 3)) phase = 1;
        if (phase == 0) {
            bot.setStatus("digging down to travel under the lava sea");
            if (goal == null) goal = Goal.y(DEPTH);
            Nav.Status st = bot.nav.goTo(goal);
            if (st == Nav.Status.DONE) { phase = 1; goal = null; }
            else if (st == Nav.Status.FAILED) {
                goal = null;
                if (++fails > 6) { phase = 1; fails = 0; }
                else {
                    // walk 14 blocks in a rotating direction and try to descend from there
                    double ang = fails * 1.7;
                    goal = Goal.xz(f.getX() + (int) (Math.cos(ang) * 14.0), f.getZ() + (int) (Math.sin(ang) * 14.0), 2.0);
                    phase = -1;
                }
            }
            return false;
        }
        if (phase == -1) {
            // the detour goal is dropped when the task is interrupted; nav.goTo(null) would throw
            if (goal == null) { phase = 0; return false; }
            bot.setStatus("looking for a way down");
            Nav.Status st = bot.nav.goTo(goal);
            if (st != Nav.Status.RUNNING) { phase = 0; goal = null; }
            return false;
        }
        bot.setStatus("tunnelling (" + dist / 25 * 25 + " blocks to go)");
        if (goal == null) { goal = Goal.xz(tx, tz, radius); phase = 1; }
        // real progress (16 blocks closer than ever before) wipes the failure count, so a long trip is not
        // abandoned because of ten unrelated obstacles spread along the way
        if (dist < bestDist - 16) { bestDist = dist; fails = 0; }
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.DONE) {
            // finishing a detour only resumes the main tunnel
            if (phase == -2) { phase = 1; goal = null; return false; }
            return true;
        }
        if (st == Nav.Status.FAILED) {
            goal = null;
            if (++fails > 10) { failed = true; return true; }
            // detour 30 blocks at ~69 degrees left/right (alternating) of the direct heading
            double ang = Math.atan2(tz - f.getZ(), tx - f.getX()) + (fails % 2 == 0 ? 1.2 : -1.2);
            goal = Goal.xz(f.getX() + (int) (Math.cos(ang) * 30.0), f.getZ() + (int) (Math.sin(ang) * 30.0), 3.0);
            phase = -2;
        }
        return false;
    }
}
