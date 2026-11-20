package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Finds the stronghold, fills the End portal frame with eyes and jumps in. */
public final class StrongholdTask extends Task {
    private enum S { LOCATE, TRAVEL, FIND_ROOM, FILL, ENTER }

    private S state = S.LOCATE;
    private Locate.Query query;
    private BlockPos place;
    private Goal goal;
    private List<BlockPos> frames;
    private BlockPos center;
    private BlockPos frame;
    private int searchIdx;
    private int ticks;
    private int travelFails;

    public StrongholdTask(Bot bot) { super(bot); }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        state = S.LOCATE;
        query = null;
        goal = null;
    }

    @Override
    public boolean interruptible() { return state == S.TRAVEL || state == S.LOCATE; }

    private BlockState bs(BlockPos p) { return bot.level().getBlockState(p); }

    @Override
    public boolean tick() {
        if (bot.level().dimension() == Level.END) return true;
        ticks++;
        switch (state) {
            case LOCATE -> {
                bot.setStatus("locating the stronghold");
                if (bot.memory.strongholdPortal != null) { place = bot.memory.strongholdPortal; state = S.TRAVEL; goal = null; return false; }
                if (query == null) query = Locate.structure(bot, BuiltinStructures.STRONGHOLD, 150);
                if (!query.ready()) return false;
                place = query.result();
                query = null;
                if (place == null) { bot.say("Could not locate a stronghold."); return false; }
                bot.say("Stronghold is around " + place.getX() + ", " + place.getZ());
                state = S.TRAVEL;
                goal = null;
            }
            case TRAVEL -> {
                BlockPos f = bot.feet();
                double dx = f.getX() - place.getX(), dz = f.getZ() - place.getZ();
                bot.setStatus("travelling to the stronghold (" + (int) Math.sqrt(dx * dx + dz * dz) / 25 * 25 + " blocks)");
                if (dx * dx + dz * dz < 20 * 20) { state = S.FIND_ROOM; goal = null; return false; }
                if (goal == null) goal = Goal.xz(place.getX(), place.getZ(), 12);
                Nav.Status st = bot.nav.goTo(goal);
                if (st == Nav.Status.FAILED) {
                    travelFails++;
                    double ang = travelFails * 1.3;
                    goal = travelFails % 2 == 1
                            ? Goal.xz(f.getX() + (int) (Math.cos(ang) * 48), f.getZ() + (int) (Math.sin(ang) * 48), 6)
                            : null;
                } else if (st == Nav.Status.DONE) goal = null;
            }
            case FIND_ROOM -> {
                bot.setStatus("looking for the End portal room");
                if (ticks % 40 == 0 || frames == null) {
                    frames = bot.scan.find(s -> s.is(Blocks.END_PORTAL_FRAME), 8, 12);
                    if (frames.size() >= 12) {
                        long sx = 0, sy = 0, sz = 0;
                        for (BlockPos p : frames) { sx += p.getX(); sy += p.getY(); sz += p.getZ(); }
                        center = new BlockPos((int) Math.round(sx / 12.0), (int) (sy / 12), (int) Math.round(sz / 12.0));
                        bot.memory.strongholdPortal = center;
                        bot.say("Found the End portal at " + center.toShortString());
                        state = S.FILL;
                        goal = null;
                        return false;
                    }
                }
                if (goal == null) {
                    int ring = 1 + searchIdx / 8;
                    double ang = (searchIdx % 8) * Math.PI / 4;
                    goal = Goal.xz(place.getX() + (int) (Math.cos(ang) * 48 * ring), place.getZ() + (int) (Math.sin(ang) * 48 * ring), 8);
                    searchIdx++;
                }
                if (bot.nav.goTo(goal) != Nav.Status.RUNNING) goal = null;
            }
            case FILL -> {
                if (bs(center).is(Blocks.END_PORTAL)) { bot.memory.portalFilled = true; state = S.ENTER; goal = null; return false; }
                if (frame != null && (!bs(frame).is(Blocks.END_PORTAL_FRAME) || bs(frame).getValue(EndPortalFrameBlock.HAS_EYE))) frame = null;
                if (frame == null) {
                    frames = bot.scan.find(s -> s.is(Blocks.END_PORTAL_FRAME) && !s.getValue(EndPortalFrameBlock.HAS_EYE), 3, 12);
                    frames.removeIf(p -> p.distSqr(center) > 5 * 5);
                    bot.memory.framesMissing = frames.size();
                    if (frames.isEmpty()) { if (ticks % 40 == 0) bot.setStatus("waiting for the portal to open"); return false; }
                    if (bot.inv.count(Items.ENDER_EYE) == 0) {
                        bot.say("Out of eyes of ender: " + frames.size() + " more needed.");
                        return true; // the planner sends the bot for more
                    }
                    frame = frames.get(0);
                    goal = Goal.reach(frame, 3.4);
                }
                bot.setStatus("placing eyes of ender (" + bot.memory.framesMissing + " left)");
                if (bot.act.eyeDist(frame) <= 4.0 && bot.player().onGround()) {
                    bot.nav.reset();
                    if (bot.inv.hold(Items.ENDER_EYE)) bot.act.useOn(frame, Direction.UP);
                    return false;
                }
                if (bot.nav.goTo(goal) == Nav.Status.FAILED) { frame = null; }
            }
            case ENTER -> {
                bot.setStatus("jumping into the End portal");
                Vec3 c = Vec3.atBottomCenterOf(center);
                double dx = bot.player().getX() - c.x, dz = bot.player().getZ() - c.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > 3.6 || Math.abs(bot.player().getY() - center.getY()) > 3) {
                    if (goal == null) goal = Goal.near(center.above(), 3.2);
                    Nav.Status st = bot.nav.goTo(goal);
                    if (st == Nav.Status.FAILED) goal = null;
                    if (st == Nav.Status.RUNNING) return false;
                }
                bot.nav.reset();
                bot.act.faceXZ(c.x, c.z);
                bot.input.forward = true;
                if (bot.player().onGround() && (bot.player().horizontalCollision || d < 3.0)) bot.input.jump = true;
            }
        }
        return false;
    }
}
