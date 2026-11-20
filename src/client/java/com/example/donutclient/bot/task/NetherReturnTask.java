package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Walks back to the nether-side portal and steps through to the Overworld. */
public final class NetherReturnTask extends Task {
    private Goal goal;
    private BlockPos portal;
    private int ticks;
    private int lightTries;
    private DeepTravelTask travel;
    private boolean travelled;

    public NetherReturnTask(Bot bot) { super(bot); }

    @Override
    public boolean tick() {
        if (bot.level().dimension() != Level.NETHER) return true;
        ticks++;
        if (portal == null || !bot.level().getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
            portal = null;
            if (ticks % 20 == 1) {
                List<BlockPos> found = bot.scan.find(s -> s.is(Blocks.NETHER_PORTAL), 8, 4);
                if (!found.isEmpty()) {
                    portal = found.get(0);
                    while (bot.level().getBlockState(portal.below()).is(Blocks.NETHER_PORTAL)) portal = portal.below();
                    goal = null;
                }
            }
        }
        if (portal == null) {
            BlockPos home = bot.memory.netherPortal;
            if (home == null) { bot.setStatus("lost: no portal known in the nether"); return false; }
            bot.setStatus("returning to the nether portal (" + (int) Math.sqrt(bot.feet().distSqr(home)) + " blocks)");
            if (bot.feet().distSqr(home) < 6 * 6) {
                // The portal went out (ghast?). Relight it.
                if (bot.inv.hold(Items.FLINT_AND_STEEL)) {
                    List<BlockPos> obs = bot.scan.find(s -> s.is(Blocks.OBSIDIAN), 1, 40);
                    for (BlockPos o : obs) {
                        if (bot.level().getBlockState(o.above()).isAir() && bot.act.eyeDist(o) < 4.2) {
                            if (lightTries++ % 12 == 0) bot.act.useOn(o, Direction.UP);
                            break;
                        }
                    }
                }
                return false;
            }
            if (travel == null && !travelled) travel = new DeepTravelTask(bot, home.getX(), home.getZ(), 10);
            if (travel != null) {
                if (travel.tick()) { travel = null; travelled = true; }
                return false;
            }
            if (goal == null) goal = Goal.near(home, 3);
            if (bot.nav.goTo(goal) == Nav.Status.FAILED) { goal = null; travelled = false; }
            return false;
        }
        bot.setStatus("going home through the portal");
        Vec3 c = Vec3.atBottomCenterOf(portal);
        double d = bot.player().position().distanceTo(c);
        if (d > 2.5) {
            if (goal == null) goal = Goal.near(portal, 2.0);
            Nav.Status st = bot.nav.goTo(goal);
            if (st == Nav.Status.FAILED) goal = null;
            if (st == Nav.Status.RUNNING) return false;
        }
        bot.nav.reset();
        if (d > 0.3) {
            bot.act.faceXZ(c.x, c.z);
            bot.input.forward = true;
            if (bot.player().horizontalCollision && bot.player().onGround()) bot.input.jump = true;
        }
        return false;
    }
}
