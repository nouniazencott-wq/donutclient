package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.ArrayList;
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
    /** Ticks spent at the remembered position without a portal block in sight. */
    private int atFrameTicks;
    /** Whether a portal frame (obsidian) was seen at the remembered position. */
    private boolean frameSeen;
    /** Set once the remembered position turned out to have no portal at all: walk rings around it and look. */
    private boolean searching;
    private int searchIdx;
    private Goal searchGoal;
    /** Ticks spent pushing into the portal without being taken through. */
    private int pushTicks;
    /** While non-null the bot first walks to this block on an open side of the portal. */
    private Goal approach;
    private int approachTicks;
    private int approachSide;

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
                    approach = null;
                    pushTicks = 0;
                }
            }
        }

        if (portal == null) {
            BlockPos known = bot.memory.netherPortal;
            if (known == null) {
                // No position remembered (used to stand here "lost" for ever): search around where we are.
                known = bot.feet();
                bot.memory.netherPortal = known;
            }
            if (searching) {
                // The remembered spot has neither a portal nor a frame, so it was wrong. Visit points on
                // growing rings around it (8 per ring, 48 blocks apart); the scan above keeps looking for
                // portal blocks and takes over as soon as one is in view.
                bot.setStatus("searching for a nether portal");
                if (searchGoal == null) {
                    int ring = 1 + searchIdx / 8;
                    double angle = (searchIdx % 8) * Math.PI / 4.0;
                    searchGoal = Goal.xz(known.getX() + (int) (Math.cos(angle) * 48.0 * ring),
                            known.getZ() + (int) (Math.sin(angle) * 48.0 * ring), 8.0);
                    searchIdx++;
                }
                if (bot.nav.goTo(searchGoal) != Nav.Status.RUNNING) {
                    searchGoal = null;
                }
                return false;
            }
            bot.setStatus("returning to the nether portal (" + (int) Math.sqrt(bot.feet().distSqr(known)) + " blocks)");
            if (bot.feet().distSqr(known) < 36.0) {
                // We are at the frame but there is no portal block: it went out, so re-light it.
                // Scan and click once every 12 ticks only.
                if (atFrameTicks++ % 12 == 0) {
                    List<BlockPos> obsidian = bot.scan.find(state -> state.is(Blocks.OBSIDIAN), 1, 40);
                    if (!obsidian.isEmpty()) {
                        frameSeen = true;
                    }
                    if (bot.inv.count(Items.FLINT_AND_STEEL) > 0 && bot.inv.hold(Items.FLINT_AND_STEEL)) {
                        // Only fire on top of the bottom row lights the portal, and the nearest block with air
                        // above it may just as well be the top of the frame: try every candidate in turn.
                        List<BlockPos> candidates = new ArrayList<>();
                        for (BlockPos pos : obsidian) {
                            if (bot.level().getBlockState(pos.above()).isAir() && bot.act.eyeDist(pos) < 4.2) {
                                candidates.add(pos);
                            }
                        }
                        if (!candidates.isEmpty()) {
                            bot.act.useOn(candidates.get(lightTries++ % candidates.size()), Direction.UP);
                        }
                    }
                }
                // 10 s here and not a single obsidian block around: there never was a portal at this spot.
                if (atFrameTicks > 200 && !frameSeen) {
                    searching = true;
                    bot.nav.reset();
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
        if (approach != null) {
            Nav.Status status = bot.nav.goTo(approach);
            if (status == Nav.Status.RUNNING && ++approachTicks < 200) {
                return false;
            }
            approach = null;
            pushTicks = 0;
            return false;
        }
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
        // 8 s of pushing and still in the Nether (the trip takes 4 s): we are wedged against the frame, or the
        // portal is on cooldown. Walk to the block in front of an open face and come in again from there.
        if (++pushTicks > 160) {
            approach = PortalTask.sideGoal(bot, portal, approachSide++);
            approachTicks = 0;
            pushTicks = 0;
        }
        return false;
    }
}
