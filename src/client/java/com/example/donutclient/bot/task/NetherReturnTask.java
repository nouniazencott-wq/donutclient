package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Brings the bot back from the Nether: travels to the remembered portal (re-lighting it if it went out),
 * then walks into the portal block and waits for the dimension change.
 */
public final class NetherReturnTask extends Task {
    private Goal goal;
    /** Lowest portal block of the column we are going to step into, once one is in view. */
    private BlockPos portal;
    private int ticks;
    private int lightTries;
    /** Long-range leg towards the remembered portal. */
    private DeepTravelTask travel;
    private boolean travelled;

    public NetherReturnTask(Bot bot) {
        super(bot);
    }

    @Override
    public boolean tick() {
        if (bot.level().dimension() != Level.NETHER) {
            return true;
        }
        ticks++;
        if (portal == null || !bot.level().getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
            portal = null;
            // Rescan for a portal block once a second.
            if (ticks % 20 == 1) {
                List<BlockPos> found = bot.scan.find(state -> state.is(Blocks.NETHER_PORTAL), 8, 4);
                if (!found.isEmpty()) {
                    portal = found.get(0);
                    // Slide down to the bottom block of the portal column.
                    while (bot.level().getBlockState(portal.below()).is(Blocks.NETHER_PORTAL)) {
                        portal = portal.below();
                    }
                    goal = null;
                }
            }
        }

        if (portal == null) {
            BlockPos known = bot.memory.netherPortal;
            if (known == null) {
                bot.setStatus("lost: no portal known in the nether");
                return false;
            }
            bot.setStatus("returning to the nether portal (" + (int) Math.sqrt(bot.feet().distSqr(known)) + " blocks)");
            if (bot.feet().distSqr(known) < 36.0) {
                // We are at the frame but there is no portal block: it went out, so re-light it.
                if (bot.inv.hold(Items.FLINT_AND_STEEL)) {
                    List<BlockPos> obsidian = bot.scan.find(state -> state.is(Blocks.OBSIDIAN), 1, 40);
                    for (BlockPos pos : obsidian) {
                        if (bot.level().getBlockState(pos.above()).isAir() && bot.act.eyeDist(pos) < 4.2) {
                            if (lightTries++ % 12 == 0) {
                                bot.act.useOn(pos, Direction.UP);
                            }
                            break;
                        }
                    }
                }
                return false;
            }
            if (travel == null && !travelled) {
                travel = new DeepTravelTask(bot, known.getX(), known.getZ(), 10.0);
            }
            if (travel != null) {
                if (travel.tick()) {
                    travel = null;
                    travelled = true;
                }
                return false;
            }
            if (goal == null) {
                goal = Goal.near(known, 3.0);
            }
            if (bot.nav.goTo(goal) == Nav.Status.FAILED) {
                goal = null;
                travelled = false;
            }
            return false;
        }

        bot.setStatus("going home through the portal");
        Vec3 centre = Vec3.atBottomCenterOf(portal);
        double dist = bot.player().position().distanceTo(centre);
        if (dist > 2.5) {
            if (goal == null) {
                goal = Goal.near(portal, 2.0);
            }
            Nav.Status status = bot.nav.goTo(goal);
            if (status == Nav.Status.FAILED) {
                goal = null;
            }
            if (status == Nav.Status.RUNNING) {
                return false;
            }
        }
        // Close enough: walk straight into the portal block and stand in it.
        bot.nav.reset();
        if (dist > 0.3) {
            bot.act.faceXZ(centre.x, centre.z);
            bot.input.forward = true;
            if (bot.player().horizontalCollision && bot.player().onGround()) {
                bot.input.jump = true;
            }
        }
        return false;
    }
}
