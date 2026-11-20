package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;

/** After a death: walk back to where it happened and pick the dropped items up again. */
public final class RecoverItemsTask extends Task {
    private Goal goal;
    private final DropCollector drops;
    private int idle;
    private int ticks;

    public RecoverItemsTask(Bot bot) {
        super(bot);
        drops = new DropCollector(bot, s -> true);
        drops.range = 14;
    }

    /** Items lie around for five minutes of loaded-chunk time; give up after a while or in a death trap. */
    public static boolean pending(Bot bot) {
        var m = bot.memory;
        if (m.deathPos == null) return false;
        if (m.deathDim != bot.level().dimension() || m.deathsNearby >= 2 || bot.tick - m.deathTick > 20 * 60 * 6) {
            return false;
        }
        return true;
    }

    @Override
    public boolean tick() {
        var m = bot.memory;
        if (!pending(bot) || ++ticks > 20 * 60 * 6) { m.deathPos = null; return true; }
        bot.setStatus("going back for my items at " + m.deathPos.toShortString());
        if (bot.feet().distSqr(m.deathPos) < 8 * 8) {
            if (drops.tick()) { idle = 0; return false; }
            if (++idle > 60) { m.deathPos = null; return true; }
            if (bot.feet().distSqr(m.deathPos) < 3 * 3) return false;
        }
        if (goal == null) goal = Goal.near(m.deathPos, 2);
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) { m.deathPos = null; return true; }
        return false;
    }
}
