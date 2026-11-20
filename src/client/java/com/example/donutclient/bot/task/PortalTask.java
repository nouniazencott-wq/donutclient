package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gets the bot into the Nether: reuses a lit portal if one is nearby, otherwise
 * builds one next to a lava pool by casting obsidian in place (water + lava
 * buckets, the way speedrunners do it), lights it and walks in.
 *
 * Frame layout (looking north at it, bot works from the south side):
 * columns ox..ox+3 at z=oz; bottom row sunk into the floor at y=g-1.
 */
public final class PortalTask extends Task {
    private enum S { FIND_PORTAL, FIND_LAVA, CHOOSE_SITE, PREP, BUILD, CLEAN, LIGHT, ENTER }

    /** One obsidian block to cast: target, the helper block the water sits on, and where to stand. */
    private record Cast(BlockPos target, BlockPos helper, BlockPos stand) {}

    private S state = S.FIND_PORTAL;
    private int stateTicks;
    private BlockPos lake;
    private final Set<BlockPos> badLakes = new HashSet<>();
    private int ox, g, oz;
    private List<Cast> casts;
    private int castIdx;
    private int castState;
    private int castTicks;
    private int castFails;
    private BlockPos workTarget;
    private Goal workGoal;
    private Goal goal;
    private BlockPos portalBlock;
    private Goal explore;
    private double heading = -1;
    private long lastScan = -1000;
    private FetchFluidTask lavaFetch;

    public PortalTask(Bot bot) { super(bot); }

    private void go(S s) {
        state = s;
        stateTicks = 0;
        goal = null;
        workTarget = null;
        bot.nav.reset();
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        go(S.FIND_PORTAL);
    }

    @Override
    public boolean interruptible() { return state == S.FIND_PORTAL || state == S.FIND_LAVA; }

    private BlockState bs(BlockPos p) { return bot.level().getBlockState(p); }

    private int fl(BlockPos p) { return Blocks2.flags(bot.level(), p, bs(p)); }

    @Override
    public boolean tick() {
        if (bot.level().dimension() == Level.NETHER) {
            if (bot.memory.netherPortal == null) bot.memory.netherPortal = bot.feet();
            return true;
        }
        if (busy()) return false;
        stateTicks++;
        switch (state) {
            case FIND_PORTAL -> findPortal();
            case FIND_LAVA -> findLava();
            case CHOOSE_SITE -> chooseSite();
            case PREP -> prep();
            case BUILD -> build();
            case CLEAN -> clean();
            case LIGHT -> light();
            case ENTER -> enter();
        }
        return false;
    }

    // ---- existing portal --------------------------------------------------------------

    private void findPortal() {
        List<BlockPos> portals = bot.scan.find(s -> s.is(Blocks.NETHER_PORTAL), 8, 8);
        if (!portals.isEmpty()) {
            portalBlock = lowest(portals.get(0));
            go(S.ENTER);
        } else go(S.FIND_LAVA);
    }

    private BlockPos lowest(BlockPos p) {
        while (bs(p.below()).is(Blocks.NETHER_PORTAL)) p = p.below();
        return p;
    }

    // ---- lava ---------------------------------------------------------------------------

    private void findLava() {
        bot.setStatus("looking for a lava pool");
        if (bot.tick - lastScan > 100) {
            lastScan = bot.tick;
            List<FetchFluidTask.Spot> spots = FetchFluidTask.findSpots(bot, FluidTags.LAVA, null, 0, 300);
            BlockPos f = bot.feet();
            spots.sort((a, b) -> Double.compare(a.stand().distSqr(f), b.stand().distSqr(f)));
            for (FetchFluidTask.Spot sp : spots) {
                if (bot.memory.dangerous(sp.stand())) continue;
                boolean bad = false;
                for (BlockPos b : badLakes) if (b.distSqr(sp.source()) < 24 * 24) bad = true;
                if (bad) continue;
                if (poolSize(sp.source()) >= 18) {
                    lake = sp.source();
                    go(S.CHOOSE_SITE);
                    return;
                }
            }
        }
        BlockPos f = bot.feet();
        if (explore == null) {
            if (heading < 0) heading = Math.floorMod(f.getX() * 7 + f.getZ() * 3, 8) * Math.PI / 4;
            explore = Goal.xz(f.getX() + (int) (Math.cos(heading) * 96), f.getZ() + (int) (Math.sin(heading) * 96), 8);
        }
        Nav.Status st = bot.nav.goTo(explore);
        if (st == Nav.Status.FAILED) heading += Math.PI / 2 + 0.4;
        if (st != Nav.Status.RUNNING) explore = null;
    }

    private int poolSize(BlockPos c) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -1, -6), c.offset(6, 1, 6))) {
            var fs = bs(p).getFluidState();
            if (fs.isSource() && fs.is(FluidTags.LAVA) && bs(p.above()).isAir()) n++;
        }
        return n;
    }

    // ---- site ---------------------------------------------------------------------------

    private void chooseSite() {
        bot.setStatus("choosing a spot for the portal");
        double best = Double.MAX_VALUE;
        int bx = 0, bg = 0, bz = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = lake.getX() - 16; cx <= lake.getX() + 16; cx += 2) {
            for (int cz = lake.getZ() - 16; cz <= lake.getZ() + 16; cz += 2) {
                double dist = Math.sqrt(lake.distSqr(new BlockPos(cx + 1, lake.getY(), cz + 1)));
                if (dist < 7 || dist > 20) continue;
                next:
                // The pit floor sits below the lava surface: the bot's own tunnels to the pool then
                // run uphill, so the casting water can never drain into the lava.
                for (int cg = lake.getY() - 4; cg <= lake.getY() - 1; cg++) {
                    double cost = dist * 0.6 + Math.abs(cg - lake.getY());
                    for (int x = cx - 3; x <= cx + 6; x++) {
                        for (int z = cz - 2; z <= cz + 4; z++) {
                            boolean inner = x >= cx - 2 && x <= cx + 5 && z >= cz - 1 && z <= cz + 3;
                            for (int y = cg - 2; y <= cg + 5; y++) {
                                m.set(x, y, z);
                                BlockState s = bs(m);
                                if (!s.getFluidState().isEmpty()) continue next;
                                int f = Blocks2.flags(bot.level(), m, s);
                                if (!inner) {
                                    // The rim must be solid so the casting water cannot escape the pit.
                                    if ((y == cg || y == cg + 1) && (f & Blocks2.STAND) == 0) continue next;
                                    continue;
                                }
                                if ((f & (Blocks2.DANGER | Blocks2.UNLOADED)) != 0) continue next;
                                if (y >= cg && y <= cg + 4) {
                                    if ((f & Blocks2.PASS) == 0) {
                                        if ((f & Blocks2.BREAK) == 0) continue next;
                                        cost += 0.5;
                                    }
                                } else if (y == cg - 1) {
                                    if ((f & Blocks2.STAND) == 0) cost += 2;
                                    if (s.is(Blocks.BEDROCK)) continue next;
                                }
                            }
                        }
                    }
                    if (cost < best) { best = cost; bx = cx; bg = cg; bz = cz; }
                }
            }
        }
        if (best == Double.MAX_VALUE) {
            badLakes.add(lake);
            go(S.FIND_LAVA);
            return;
        }
        ox = bx; g = bg; oz = bz;
        bot.say("Building a nether portal at " + ox + ", " + g + ", " + oz);
        casts = new ArrayList<>();
        BlockPos wl = new BlockPos(ox + 1, g + 1, oz + 2), wr = new BlockPos(ox + 2, g + 1, oz + 2);
        // Bottom row, sunk into the floor; the helper is the floor block in front of each hole.
        casts.add(new Cast(new BlockPos(ox + 1, g - 1, oz), new BlockPos(ox + 1, g - 1, oz + 1), wl));
        casts.add(new Cast(new BlockPos(ox + 2, g - 1, oz), new BlockPos(ox + 2, g - 1, oz + 1), wr));
        // Side columns; helpers are temporary pillars just outside the frame.
        for (int k = 0; k < 3; k++) {
            int h = Math.max(1, k);
            casts.add(new Cast(new BlockPos(ox, g + k, oz), new BlockPos(ox - 1, g + k, oz), wl.above(h - 1)));
            casts.add(new Cast(new BlockPos(ox + 3, g + k, oz), new BlockPos(ox + 4, g + k, oz), wr.above(h - 1)));
        }
        // Top row; helpers are temporary corner blocks.
        casts.add(new Cast(new BlockPos(ox + 1, g + 3, oz), new BlockPos(ox, g + 3, oz), wl.above(2)));
        casts.add(new Cast(new BlockPos(ox + 2, g + 3, oz), new BlockPos(ox + 3, g + 3, oz), wr.above(2)));
        castIdx = 0;
        castState = 0;
        castFails = 0;
        go(S.PREP);
    }

    private boolean isFrame(BlockPos p) {
        if (casts == null) return false;
        for (Cast c : casts) if (c.target.equals(p) || c.helper.equals(p)) return true;
        return false;
    }

    /** Clears the work volume and levels its floor. */
    private void prep() {
        bot.setStatus("clearing the portal site");
        if (stateTicks > 6000) { badLakes.add(lake); go(S.FIND_LAVA); return; }
        if (workTarget != null && satisfied(workTarget)) workTarget = null;
        if (workTarget == null) {
            BlockPos f = bot.feet();
            double best = Double.MAX_VALUE;
            for (int x = ox - 2; x <= ox + 5; x++) {
                for (int z = oz - 1; z <= oz + 3; z++) {
                    for (int y = g + 4; y >= g - 1; y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (satisfied(p)) continue;
                        double d = p.distSqr(f) - (y - g) * 3;
                        if (d < best) { best = d; workTarget = p; }
                    }
                }
            }
            if (workTarget == null) { go(S.BUILD); return; }
            workGoal = Goal.reach(workTarget, 3.6);
        }
        workOn(workTarget, workTarget.getY() == g - 1);
    }

    private boolean satisfied(BlockPos p) {
        int f = fl(p);
        if (p.getY() == g - 1) return (f & Blocks2.STAND) != 0 || isFrame(p);
        return (f & Blocks2.PASS) != 0;
    }

    /** Break (or fill) one block, walking into reach first. */
    private void workOn(BlockPos p, boolean fill) {
        BlockPos f = bot.feet();
        boolean settled = bot.player().onGround();
        if (settled && bot.act.eyeDist(p) <= 4.2 && !(f.equals(p) || f.above().equals(p))) {
            bot.nav.reset();
            if (fill) {
                if ((fl(p) & Blocks2.PASS) == 0) bot.act.breakBlock(p); else bot.act.placeBlock(p);
            } else bot.act.breakBlock(p);
            return;
        }
        Nav.Status st = bot.nav.goTo(workGoal);
        if (st == Nav.Status.FAILED) { workTarget = null; }
    }

    // ---- casting --------------------------------------------------------------------------

    private boolean isWater(BlockPos p) { return bs(p).getFluidState().is(FluidTags.WATER); }

    private void build() {
        if (castIdx >= casts.size()) { go(S.CLEAN); return; }
        if (stateTicks > 20 * 60 * 25 || castFails > 8) {
            bot.say("Portal attempt failed here, trying another spot.");
            badLakes.add(lake);
            go(S.FIND_LAVA);
            return;
        }
        Cast c = casts.get(castIdx);
        bot.setStatus("casting obsidian " + (castIdx + 1) + "/" + casts.size());
        if (bs(c.target).is(Blocks.OBSIDIAN) && castState < 6) castState = 6;
        castTicks++;
        BlockPos q = c.helper.above();
        switch (castState) {
            case 0 -> { // buckets
                if (bot.inv.count(Items.LAVA_BUCKET) == 0) {
                    if (bot.inv.count(Items.BUCKET) == 0) {
                        // Both buckets hold water (or one was lost): pour one out is not possible; fail the attempt.
                        castFails++;
                        return;
                    }
                    FetchFluidTask t = new FetchFluidTask(bot, true);
                    // Prefer the pool next to the site; fall back to any lava the bot can find.
                    if (lavaFetch == null || !lavaFetch.failed) t.around = lake;
                    lavaFetch = t;
                    run(t);
                    return;
                }
                if (bot.inv.count(Items.WATER_BUCKET) == 0 && !bs(q).getFluidState().isSource()) {
                    if (bot.inv.count(Items.BUCKET) == 0) { castFails++; return; }
                    run(new FetchFluidTask(bot, false));
                    return;
                }
                next(1);
            }
            case 1 -> { // get into position
                if (goal == null) goal = Goal.block(c.stand);
                Nav.Status st = bot.nav.goTo(goal);
                if (st == Nav.Status.FAILED) { castFails++; goal = null; }
                if (st == Nav.Status.DONE) next(2);
            }
            case 2 -> { // helper block in place, target cell free of solids
                if (!bot.feet().equals(c.stand)) { next(1); return; }
                BlockState ts = bs(c.target);
                if (!ts.isAir() && ts.getFluidState().isEmpty()) { bot.act.breakBlock(c.target); return; }
                BlockState hs = bs(c.helper);
                if (hs.canBeReplaced()) { bot.act.placeBlock(c.helper); if (castTicks > 100) fail(); return; }
                BlockState qs = bs(q);
                if (!qs.isAir() && qs.getFluidState().isEmpty()) { bot.act.breakBlock(q); return; }
                next(3);
            }
            case 3 -> { // water on top of the helper
                if (bs(q).getFluidState().isSource() && isWater(q)) { next(4); return; }
                if (!bot.inv.hold(Items.WATER_BUCKET)) { next(0); return; }
                Vec3 pt = bot.aim.visiblePoint(bot.player().getEyePosition(), c.helper, Direction.UP);
                if (pt == null || castTicks > 60) { fail(); return; }
                bot.act.lookAt(pt);
                bot.act.useItem();
            }
            case 4 -> { // let it flow over the target
                if ((isWater(c.target) || isWater(c.target.above())) && castTicks > 6) next(5);
                else if (castTicks > 50) next(5);
            }
            case 5 -> { // lava into the wet cell
                BlockState ts = bs(c.target);
                if (ts.is(Blocks.OBSIDIAN)) { next(6); return; }
                if (ts.getFluidState().is(FluidTags.LAVA)) {
                    // It did not convert: take the lava back and try again.
                    if (castTicks > 12 && bot.inv.hold(Items.BUCKET)) {
                        bot.act.lookAt(Vec3.atCenterOf(c.target));
                        bot.act.useItem();
                    }
                    if (castTicks > 80) fail();
                    return;
                }
                if (!ts.isAir() && ts.getFluidState().isEmpty()) { next(2); return; } // cobble formed; redo
                if (bot.inv.count(Items.LAVA_BUCKET) == 0) { if (castTicks > 20) next(0); return; }
                if (!bot.inv.hold(Items.LAVA_BUCKET)) return;
                Vec3 pt = bot.aim.faceFor(c.target);
                if (pt == null || castTicks > 60) { fail(); return; }
                bot.act.lookAt(pt);
                bot.act.useItem();
            }
            case 6 -> { // take the water back
                if (!bs(q).getFluidState().isSource()) { next(7); return; }
                if (!bot.inv.hold(Items.BUCKET)) { next(7); return; }
                if (castTicks > 60) { next(7); return; }
                if (!bot.feet().equals(c.stand) && castTicks < 5) { castState = 1; goal = null; return; }
                bot.act.lookAt(Vec3.atCenterOf(q));
                bot.act.useItem();
            }
            default -> {
                castIdx++;
                castFails = 0;
                next(0);
            }
        }
    }

    private void next(int s) {
        castState = s;
        castTicks = 0;
        goal = null;
    }

    private void fail() {
        castFails++;
        next(0);
    }

    // ---- finishing ------------------------------------------------------------------------

    private void clean() {
        bot.setStatus("tidying the portal");
        // Pick the water back up if any source is still lying around the frame.
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(ox - 2, g, oz - 1), new BlockPos(ox + 5, g + 5, oz + 3))) {
            var fs = bs(p).getFluidState();
            if (fs.isSource() && fs.is(FluidTags.WATER) && bot.inv.count(Items.BUCKET) > 0 && stateTicks < 200) {
                if (bot.inv.hold(Items.BUCKET) && bot.act.eyeDist(p) < 4.3) {
                    bot.act.lookAt(Vec3.atCenterOf(p));
                    bot.act.useItem();
                }
                return;
            }
        }
        for (int x = ox + 1; x <= ox + 2; x++) {
            for (int y = g; y <= g + 2; y++) {
                BlockPos p = new BlockPos(x, y, oz);
                BlockState s = bs(p);
                if (s.is(Blocks.NETHER_PORTAL)) { portalBlock = new BlockPos(ox + 1, g, oz); go(S.ENTER); return; }
                if (!s.getFluidState().isEmpty()) { if (stateTicks > 400) go(S.LIGHT); return; }
                if (!s.isAir()) {
                    if (workGoal == null || !p.equals(workTarget)) { workTarget = p.immutable(); workGoal = Goal.reach(p, 3.6); }
                    workOn(workTarget, false);
                    return;
                }
            }
        }
        for (Cast c : casts) {
            if (!bs(c.target).is(Blocks.OBSIDIAN)) {
                castIdx = casts.indexOf(c);
                next(0);
                go(S.BUILD);
                return;
            }
        }
        go(S.LIGHT);
    }

    private void light() {
        bot.setStatus("lighting the portal");
        BlockPos inside = new BlockPos(ox + 1, g, oz);
        if (bs(inside).is(Blocks.NETHER_PORTAL)) { portalBlock = inside; go(S.ENTER); return; }
        if (stateTicks > 600) { go(S.CLEAN); return; }
        if (goal == null) goal = Goal.block(new BlockPos(ox + 1, g, oz + 1));
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) goal = null;
        if (st != Nav.Status.DONE) return;
        if (!bot.inv.hold(Items.FLINT_AND_STEEL)) return;
        bot.act.useOn(new BlockPos(ox + 1, g - 1, oz), Direction.UP);
    }

    private void enter() {
        bot.setStatus("entering the nether portal");
        if (!bs(portalBlock).is(Blocks.NETHER_PORTAL)) { go(S.FIND_PORTAL); return; }
        bot.memory.overworldPortal = portalBlock;
        Vec3 c = Vec3.atBottomCenterOf(portalBlock);
        double d = bot.player().position().distanceTo(c);
        if (d > 2.5) {
            if (goal == null) goal = Goal.near(portalBlock, 2.0);
            Nav.Status st = bot.nav.goTo(goal);
            if (st == Nav.Status.FAILED) goal = null;
            if (st == Nav.Status.RUNNING) return;
        }
        bot.nav.reset();
        if (d > 0.3) {
            bot.act.faceXZ(c.x, c.z);
            bot.input.forward = true;
            if (bot.player().horizontalCollision && bot.player().onGround()) bot.input.jump = true;
        }
        if (stateTicks > 600) go(S.FIND_PORTAL);
    }
}
