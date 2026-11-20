package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the stronghold, walks to it, searches for the End portal room, fills the missing
 * portal frames with eyes of ender and finally jumps into the portal. Finishes once the
 * player is in the End (or when it runs out of eyes).
 */
public final class StrongholdTask extends Task {
    private enum S { LOCATE, TRAVEL, FIND_ROOM, FILL, ENTER }

    private S state = S.LOCATE;
    /** Pending asynchronous structure lookup. */
    private Locate.Query query;
    /** Approximate stronghold position (or the remembered portal position). */
    private BlockPos place;
    private Goal goal;
    /** Portal frames from the latest scan (all frames in FIND_ROOM, empty ones in FILL). */
    private List<BlockPos> frames;
    /** Centre of the 3x3 portal, the average of the 12 frame positions. */
    private BlockPos center;
    /** The empty frame currently being filled. */
    private BlockPos frame;
    /** Index of the next spiral search point around {@link #place}. */
    private int searchIdx;
    private int ticks;
    private int travelFails;
    /** Ticks to wait before asking the server for the stronghold again after a failed lookup. */
    private int locateWait;
    private int locateFails;
    /** True if {@link #place} is the remembered portal position rather than a fresh lookup. */
    private boolean fromMemory;
    /** True while {@link #goal} leads to a partly visible portal instead of a spiral search point. */
    private boolean towardFrames;
    /** Ticks spent in FILL with every frame filled but no portal block in the middle. */
    private int waitTicks;
    /** Ticks spent walking straight at the portal in ENTER without being teleported. */
    private int enterTicks;
    /** Alternates the ENTER approach: true = first climb onto the portal frame itself. */
    private boolean enterClose;

    public StrongholdTask(Bot bot) {
        super(bot);
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        state = S.LOCATE;
        query = null;
        goal = null;
    }

    @Override
    public boolean interruptible() {
        return state == S.TRAVEL || state == S.LOCATE;
    }

    private BlockState bs(BlockPos pos) {
        return bot.level().getBlockState(pos);
    }

    @Override
    public boolean tick() {
        if (bot.level().dimension() == Level.END) {
            return true;
        }
        ticks++;
        switch (state) {
            case LOCATE -> {
                bot.setStatus("locating the stronghold");
                if (bot.memory.strongholdPortal != null) {
                    // Already found the portal on an earlier run: go straight back to it.
                    place = bot.memory.strongholdPortal;
                    fromMemory = true;
                    state = S.TRAVEL;
                    goal = null;
                    return false;
                }
                if (locateWait > 0) {
                    // A lookup just failed: wait before asking again (see below).
                    locateWait--;
                    return false;
                }
                if (query == null) {
                    query = Locate.structure(bot, BuiltinStructures.STRONGHOLD, 150);
                }
                if (!query.ready()) {
                    return false;
                }
                place = query.result();
                query = null;
                if (place == null) {
                    // Retry only every 10 seconds and announce it once a minute, instead of
                    // starting a new server search and a chat line every single tick.
                    if (locateFails++ % 6 == 0) {
                        bot.say("Could not locate a stronghold, retrying.");
                    }
                    locateWait = 200;
                    return false;
                }
                locateFails = 0;
                fromMemory = false;
                bot.say("Stronghold is around " + place.getX() + ", " + place.getZ());
                state = S.TRAVEL;
                goal = null;
            }
            case TRAVEL -> {
                BlockPos feet = bot.feet();
                double dx = feet.getX() - place.getX();
                double dz = feet.getZ() - place.getZ();
                bot.setStatus("travelling to the stronghold (" + (int) Math.sqrt(dx * dx + dz * dz) / 25 * 25 + " blocks)");
                if (dx * dx + dz * dz < 400.0) {
                    state = S.FIND_ROOM;
                    goal = null;
                    return false;
                }
                if (goal == null) {
                    goal = Goal.xz(place.getX(), place.getZ(), 12.0);
                }
                Nav.Status status = bot.nav.goTo(goal);
                if (status == Nav.Status.FAILED) {
                    // Every other failure, detour to a point 48 blocks away in a rotating direction.
                    travelFails++;
                    double angle = travelFails * 1.3;
                    goal = travelFails % 2 == 1
                            ? Goal.xz(feet.getX() + (int) (Math.cos(angle) * 48.0), feet.getZ() + (int) (Math.sin(angle) * 48.0), 6.0)
                            : null;
                } else if (status == Nav.Status.DONE) {
                    goal = null;
                }
            }
            case FIND_ROOM -> {
                bot.setStatus("looking for the End portal room");
                if (ticks % 40 == 0 || frames == null) {
                    frames = bot.scan.find(state -> state.is(Blocks.END_PORTAL_FRAME), 8, 12);
                    if (frames.size() >= 12) {
                        long sumX = 0L;
                        long sumY = 0L;
                        long sumZ = 0L;
                        for (BlockPos pos : frames) {
                            sumX += pos.getX();
                            sumY += pos.getY();
                            sumZ += pos.getZ();
                        }
                        center = new BlockPos((int) Math.round(sumX / 12.0), (int) (sumY / 12L), (int) Math.round(sumZ / 12.0));
                        bot.memory.strongholdPortal = center;
                        bot.say("Found the End portal at " + center.toShortString());
                        state = S.FILL;
                        goal = null;
                        frame = null;
                        waitTicks = 0;
                        return false;
                    }
                    if (!frames.isEmpty() && !towardFrames) {
                        // Part of the portal is already visible (the rest is in chunks that are not
                        // loaded yet): head for it instead of continuing the blind spiral.
                        BlockPos seen = frames.get(0);
                        BlockPos feet = bot.feet();
                        double fx = feet.getX() - seen.getX();
                        double fz = feet.getZ() - seen.getZ();
                        if (fx * fx + fz * fz > 144.0) {
                            goal = Goal.xz(seen.getX(), seen.getZ(), 6.0);
                            towardFrames = true;
                        }
                    }
                }
                if (goal == null) {
                    towardFrames = false;
                    if (searchIdx >= 24) {
                        // Three full rings without finding the room: start the spiral over rather
                        // than walking ever further away. If the centre was a remembered portal
                        // position it is evidently wrong, so forget it and locate the stronghold anew.
                        searchIdx = 0;
                        if (fromMemory) {
                            fromMemory = false;
                            bot.memory.strongholdPortal = null;
                            frames = null;
                            state = S.LOCATE;
                            return false;
                        }
                    }
                    // Search outwards: 8 compass directions per ring, rings 48 blocks apart.
                    int ring = 1 + searchIdx / 8;
                    double angle = (searchIdx % 8) * Math.PI / 4.0;
                    goal = Goal.xz(place.getX() + (int) (Math.cos(angle) * 48.0 * ring), place.getZ() + (int) (Math.sin(angle) * 48.0 * ring), 8.0);
                    searchIdx++;
                }
                if (bot.nav.goTo(goal) != Nav.Status.RUNNING) {
                    goal = null;
                    towardFrames = false;
                }
            }
            case FILL -> {
                if (bs(center).is(Blocks.END_PORTAL)) {
                    bot.memory.portalFilled = true;
                    state = S.ENTER;
                    enterTicks = 0;
                    goal = null;
                    return false;
                }
                if (frame != null && (!bs(frame).is(Blocks.END_PORTAL_FRAME) || bs(frame).getValue(EndPortalFrameBlock.HAS_EYE))) {
                    frame = null;
                }
                if (frame == null) {
                    frames = bot.scan.find(state -> state.is(Blocks.END_PORTAL_FRAME) && !state.getValue(EndPortalFrameBlock.HAS_EYE), 3, 12);
                    frames.removeIf(pos -> pos.distSqr(center) > 25.0);
                    bot.memory.framesMissing = frames.size();
                    if (frames.isEmpty()) {
                        bot.setStatus("waiting for the portal to open");
                        // Do not wait forever: every 2 seconds look for portal blocks next to the
                        // bot (the computed centre may be off), and after 20 seconds search for the
                        // frames again, which recomputes the centre.
                        if (++waitTicks % 40 == 0) {
                            List<BlockPos> portal = bot.scan.find(state -> state.is(Blocks.END_PORTAL), 2, 1);
                            if (!portal.isEmpty()) {
                                center = portal.get(0);
                            } else if (waitTicks >= 400) {
                                waitTicks = 0;
                                frames = null;
                                goal = null;
                                state = S.FIND_ROOM;
                            }
                        }
                        return false;
                    }
                    waitTicks = 0;
                    if (bot.inv.count(Items.ENDER_EYE) == 0) {
                        bot.say("Out of eyes of ender: " + frames.size() + " more needed.");
                        return true;
                    }
                    frame = frames.get(0);
                    goal = Goal.reach(frame, 3.4);
                }
                bot.setStatus("placing eyes of ender (" + bot.memory.framesMissing + " left)");
                if (bot.act.eyeDist(frame) <= 4.0 && bot.player().onGround()) {
                    bot.nav.reset();
                    if (bot.inv.hold(Items.ENDER_EYE)) {
                        bot.act.useOn(frame, Direction.UP);
                    }
                    return false;
                }
                if (bot.nav.goTo(goal) == Nav.Status.FAILED) {
                    frame = null;
                }
            }
            case ENTER -> {
                if (!bs(center).is(Blocks.END_PORTAL)) {
                    // The portal block is gone again: go back to checking the frames.
                    state = S.FILL;
                    frame = null;
                    goal = null;
                    return false;
                }
                bot.setStatus("jumping into the End portal");
                Vec3 target = Vec3.atBottomCenterOf(center);
                double dx = bot.player().getX() - target.x;
                double dz = bot.player().getZ() - target.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > (enterClose ? 2.6 : 3.6) || Math.abs(bot.player().getY() - center.getY()) > 3.0) {
                    if (goal == null) {
                        goal = Goal.near(center.above(), enterClose ? 2.5 : 3.2);
                    }
                    Nav.Status status = bot.nav.goTo(goal);
                    if (status == Nav.Status.FAILED) {
                        goal = null;
                    }
                    if (status == Nav.Status.RUNNING) {
                        return false;
                    }
                }
                // Close enough: walk straight at the portal centre and hop over the frame.
                if (++enterTicks > 100) {
                    // Five seconds of pushing without being teleported (stuck against the frame or
                    // below it): alternate between the wide approach and first pathing onto the
                    // frame blocks themselves, and replan.
                    enterTicks = 0;
                    enterClose = !enterClose;
                    goal = null;
                }
                bot.nav.reset();
                bot.act.faceXZ(target.x, target.z);
                bot.input.forward = true;
                if (bot.player().onGround() && (bot.player().horizontalCollision || dist < 3.0)) {
                    bot.input.jump = true;
                }
            }
        }
        return false;
    }
}
