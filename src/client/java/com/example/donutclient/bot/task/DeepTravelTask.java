package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;

/**
 * Long-distance travel through the Nether: dig down below the lava sea and bore a
 * tunnel through solid netherrack to the destination column. Slow, but nothing
 * down there can see, shoot or shove the bot, and lava is never dug into.
 */
public final class DeepTravelTask extends Task {
    private static final int DEPTH = 11;

    private final int tx, tz;
    private final double radius;
    private Goal goal;
    private int phase;
    private int fails;
    private int ticks;
    public boolean failed;

    public DeepTravelTask(Bot bot, int x, int z, double radius) {
        super(bot);
        this.tx = x;
        this.tz = z;
        this.radius = radius;
    }

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
        if (++ticks > 20 * 60 * 40) { failed = true; return true; }
        // Short hops are not worth the descent.
        if (phase == 0 && (dist < 48 || f.getY() <= DEPTH + 3)) phase = 1;
        if (phase == 0) {
            bot.setStatus("digging down to travel under the lava sea");
            if (goal == null) goal = Goal.y(DEPTH);
            Nav.Status st = bot.nav.goTo(goal);
            if (st == Nav.Status.DONE) { phase = 1; goal = null; }
            else if (st == Nav.Status.FAILED) {
                // Blocked right here (lava below?): shift sideways and try again.
                goal = null;
                if (++fails > 6) { phase = 1; fails = 0; }
                else {
                    double ang = fails * 1.7;
                    goal = Goal.xz(f.getX() + (int) (Math.cos(ang) * 14), f.getZ() + (int) (Math.sin(ang) * 14), 2);
                    phase = -1;
                }
            }
            return false;
        }
        if (phase == -1) {
            bot.setStatus("looking for a way down");
            Nav.Status st = bot.nav.goTo(goal);
            if (st != Nav.Status.RUNNING) { phase = 0; goal = null; }
            return false;
        }
        bot.setStatus("tunnelling (" + dist / 25 * 25 + " blocks to go)");
        if (goal == null) { goal = Goal.xz(tx, tz, radius); phase = 1; }
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.DONE) {
            if (phase == -2) { phase = 1; goal = null; return false; }
            return true;
        }
        if (st == Nav.Status.FAILED) {
            goal = null;
            if (++fails > 10) { failed = true; return true; }
            // Detour: aim off to one side for a bit.
            double ang = Math.atan2(tz - f.getZ(), tx - f.getX()) + (fails % 2 == 0 ? 1.2 : -1.2);
            goal = Goal.xz(f.getX() + (int) (Math.cos(ang) * 30), f.getZ() + (int) (Math.sin(ang) * 30), 3);
            phase = -2;
        }
        return false;
    }
}
