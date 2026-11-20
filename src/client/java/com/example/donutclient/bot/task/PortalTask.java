package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Gets the bot from the overworld into the Nether.
 *
 * <p>Uses an existing portal when one is nearby (or remembered); otherwise finds a lava pool, clears a flat site
 * next to it and casts a 4x5 obsidian frame block by block with a water bucket and a lava bucket (no diamond
 * pickaxe needed), lights it with flint and steel and walks in. The task is finished once the bot is in the Nether.
 *
 * <p>Frame layout (portal plane is {@code z == oz}, bottom row at {@code y == g - 1}):
 * <pre>
 *   y=g+3   .  O  O  .
 *   y=g+2   O        O
 *   y=g+1   O        O
 *   y=g     O        O
 *   y=g-1   .  O  O  .        (corners are left out)
 *   x=      ox +1 +2 +3
 * </pre>
 */
public final class PortalTask extends Task {
    private enum S { FIND_PORTAL, FIND_LAVA, CHOOSE_SITE, PREP, BUILD, CLEAN, LIGHT, ENTER }

    private S state = S.FIND_PORTAL;
    private int stateTicks;
    /** Lava source block of the pool we are building next to. */
    private BlockPos lake;
    /** Pools that turned out to be unusable (no site, or the build failed there). */
    private final Set<BlockPos> badLakes = new HashSet<>();

    /** Portal origin: {@code ox}/{@code oz} = left frame column, {@code g} = y of the lowest portal (inside) block. */
    private int ox, g, oz;
    private List<Cast> casts;
    private int castIdx;
    /** Step of the per-block casting routine, see {@link #build()}. */
    private int castState;
    private int castTicks;
    private int castFails;

    /** Block currently being cleared / filled by {@link #workOn}. */
    private BlockPos workTarget;
    private Goal workGoal;

    private Goal goal;
    private BlockPos portalBlock;

    private Goal explore;
    private double heading = -1.0;
    private long lastScan = -1000L;
    private FetchFluidTask lavaFetch;
    private int knownTries;
    /** Ticks spent on the current exploration leg, and the number of legs walked so far. */
    private int exploreTicks;
    private int legs;
    /** Set when the task ends early because an item is missing; the plan then gets a chance to make that item. */
    private boolean giveUp;
    /** Number of times lighting timed out at this site. */
    private int lightCycles;
    /** Ticks spent pushing into the portal without being taken through. */
    private int pushTicks;
    /** While non-null the bot first walks to this block on an open side of the portal, see {@link #enter()}. */
    private Goal approach;
    private int approachTicks;
    private int approachSide;
    private int resumeTries;

    /**
     * Origin (ox, g, oz) of a frame that was left unfinished because an item was missing, and the bot tick at which
     * that happened. Static because the plan creates a fresh PortalTask after it has made the item; that task picks
     * the frame up again instead of starting a new one.
     */
    private static BlockPos resumeSite;

    /** Forgets the unfinished frame; called when the player leaves the world, where it means nothing any more. */
    public static void forgetSite() {
        resumeSite = null;
    }
    private static long resumeTick;
    private static long lastNeedSay = -100000L;

    public PortalTask(Bot bot) {
        super(bot);
    }

    private void go(S s) {
        state = s;
        stateTicks = 0;
        goal = null;
        workTarget = null;
        approach = null;
        pushTicks = 0;
        bot.nav.reset();
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        go(S.FIND_PORTAL);
    }

    /** Only the searching phases may be interrupted; building must not be abandoned half-way. */
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
        if (giveUp) {
            bot.nav.reset();
            return true;
        }
        return false;
    }

    /** Gives up on the current pool / site and looks for another one. */
    private void abandonSite() {
        if (lake != null) badLakes.add(lake);   // lake is null when a frame from an earlier task was resumed
        lightCycles = 0;
        go(S.FIND_LAVA);
    }

    /**
     * Ends the task because {@code what} is missing and cannot be obtained here. The plan re-evaluates its stages
     * when a task finishes, so it can mine / craft the item and then start a new PortalTask, which continues with
     * the frame remembered in {@link #resumeSite}. Prevents waiting or blacklisting pools forever.
     */
    private void needItem(String what) {
        resumeSite = new BlockPos(ox, g, oz);
        resumeTick = bot.tick;
        if (bot.tick - lastNeedSay > 1200L) {
            lastNeedSay = bot.tick;
            bot.say("I need " + what + " to finish the portal - going to get one.");
        }
        giveUp = true;
    }

    // ------------------------------------------------------------------ existing portal

    /** Uses a loaded portal if there is one, else walks back to the remembered one, else starts building. */
    private void findPortal() {
        List<BlockPos> portals = bot.scan.find(s -> s.is(Blocks.NETHER_PORTAL), 8, 8);
        if (!portals.isEmpty()) {
            portalBlock = lowest(portals.get(0));
            go(S.ENTER);
            return;
        }
        // a frame an earlier PortalTask had to leave unfinished (missing bucket / flint and steel): carry on there
        BlockPos site = resumeSite;
        if (site != null) {
            double d = bot.feet().distSqr(site);
            if (bot.tick < resumeTick || bot.tick - resumeTick > 36000L || d > 160000.0 || resumeTries >= 3) {
                // too old, too far away (or another world), or unreachable: forget it
                resumeSite = null;
                goal = null;
            } else if (d > 576.0) {
                bot.setStatus("returning to my unfinished portal");
                if (goal == null) goal = Goal.near(site, 8.0);
                Nav.Status st = bot.nav.goTo(goal);
                if (st == Nav.Status.FAILED) { resumeTries++; goal = null; }
                return;
            } else {
                resumeSite = null;
                ox = site.getX(); g = site.getY(); oz = site.getZ();
                layout();
                int made = 0;
                for (Cast c : casts) if (bs(c.target).is(Blocks.OBSIDIAN)) made++;
                if (made > 0) {
                    // CLEAN re-checks every frame block and goes back to BUILD / on to LIGHT as needed
                    castIdx = 0;
                    castState = 0;
                    castFails = 0;
                    go(S.CLEAN);
                    return;
                }
                casts = null;   // nothing of the frame is there: start from scratch
                goal = null;
            }
        }
        // nothing loaded: head for the portal we used before if it is more than 48 blocks away
        BlockPos known = bot.memory.overworldPortal;
        if (known != null && bot.feet().distSqr(known) > 2304.0 && knownTries < 3) {
            bot.setStatus("returning to my nether portal");
            if (goal == null) goal = Goal.near(known, 6.0);
            Nav.Status st = bot.nav.goTo(goal);
            if (st == Nav.Status.FAILED) { knownTries++; goal = null; }
            return;
        }
        go(S.FIND_LAVA);
    }

    /** Bottom-most portal block of the column containing {@code p}. */
    private BlockPos lowest(BlockPos p) {
        while (bs(p.below()).is(Blocks.NETHER_PORTAL)) p = p.below();
        return p;
    }

    // ------------------------------------------------------------------ lava pool

    /** Rescans every 5 s for a big enough surface lava pool; wanders in 96-block legs until one is found. */
    private void findLava() {
        bot.setStatus("looking for a lava pool");
        // the frame needs 10 lava sources; after 5 minutes of searching settle for a smaller pool
        int minPool = stateTicks > 6000 ? 12 : 18;
        if (bot.tick - lastScan > 100L) {
            lastScan = bot.tick;
            List<FetchFluidTask.Spot> spots = FetchFluidTask.findSpots(bot, FluidTags.LAVA, null, 0, 300);
            BlockPos f = bot.feet();
            spots.sort((a, b) -> Double.compare(a.stand().distSqr(f), b.stand().distSqr(f)));
            for (FetchFluidTask.Spot sp : spots) {
                if (bot.memory.dangerous(sp.stand())) continue;
                boolean bad = false;
                for (BlockPos b : badLakes) if (b.distSqr(sp.source()) < 576.0) bad = true;   // within 24 blocks
                if (bad) continue;
                if (poolSize(sp.source()) >= minPool) {
                    lake = sp.source();
                    go(S.CHOOSE_SITE);
                    return;
                }
            }
        }
        BlockPos f = bot.feet();
        if (explore == null) {
            // initial heading is derived from the position so it is stable across restarts
            if (heading < 0.0) heading = Math.floorMod(f.getX() * 7 + f.getZ() * 3, 8) * Math.PI / 4.0;
            explore = Goal.xz(f.getX() + (int) (Math.cos(heading) * 96.0), f.getZ() + (int) (Math.sin(heading) * 96.0), 8.0);
            exploreTicks = 0;
        }
        Nav.Status st = bot.nav.goTo(explore);
        if (st == Nav.Status.FAILED) heading += Math.PI / 2 + 0.4;
        // every 5th leg bend the route by ~60 degrees so the search does not follow one line (across an ocean,
        // a desert, ...) for ever
        else if (st == Nav.Status.DONE && ++legs % 5 == 0) heading += 1.05;
        // a leg that takes more than 2.5 minutes (swimming, digging) is not worth finishing: turn away
        else if (st == Nav.Status.RUNNING && ++exploreTicks > 3000) {
            heading += Math.PI / 2 + 0.4;
            explore = null;
            bot.nav.reset();
        }
        if (st != Nav.Status.RUNNING) explore = null;
    }

    /** Number of exposed (air above) lava source blocks in a 13x3x13 box around {@code c}. */
    private int poolSize(BlockPos c) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -1, -6), c.offset(6, 1, 6))) {
            FluidState fs = bs(p).getFluidState();
            if (fs.isSource() && fs.is(FluidTags.LAVA) && bs(p.above()).isAir()) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ site selection

    /**
     * Scores candidate origins 7..20 blocks from the pool and keeps the cheapest: close to the pool, on its level,
     * solid floor, little to dig, no fluids / dangers anywhere in the work volume. Then lays out the cast list.
     */
    private void chooseSite() {
        bot.setStatus("choosing a spot for the portal");
        double best = Double.MAX_VALUE;
        int bx = 0, bg = 0, bz = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = lake.getX() - 16; cx <= lake.getX() + 16; cx += 2) {
            for (int cz = lake.getZ() - 16; cz <= lake.getZ() + 16; cz += 2) {
                double dist = Math.sqrt(lake.distSqr(new BlockPos(cx + 1, lake.getY(), cz + 1)));
                if (dist < 7.0 || dist > 20.0) continue;
                // candidate ground levels: 1..4 blocks below the lava surface
                site:
                for (int cg = lake.getY() - 4; cg <= lake.getY() - 1; cg++) {
                    double cost = dist * 0.6 + Math.abs(cg - lake.getY());
                    for (int x = cx - 3; x <= cx + 6; x++) {
                        for (int z = cz - 2; z <= cz + 4; z++) {
                            // inner = the volume that gets cleared; the rest is a one block rim around it
                            boolean inner = x >= cx - 2 && x <= cx + 5 && z >= cz - 1 && z <= cz + 3;
                            for (int y = cg - 2; y <= cg + 5; y++) {
                                m.set(x, y, z);
                                BlockState s = bs(m);
                                if (!s.getFluidState().isEmpty()) continue site;
                                int f = Blocks2.flags(bot.level(), m, s);
                                if (!inner) {
                                    // the rim must be solid at ground level and one above (keeps the cast water contained)
                                    if ((y == cg || y == cg + 1) && (f & Blocks2.STAND) == 0) continue site;
                                } else {
                                    if ((f & (Blocks2.DANGER | Blocks2.UNLOADED)) != 0) continue site;
                                    if (y >= cg && y <= cg + 4) {
                                        if ((f & Blocks2.PASS) == 0) {
                                            if ((f & Blocks2.BREAK) == 0) continue site;
                                            cost += 0.5;   // has to be dug out
                                        }
                                    } else if (y == cg - 1) {
                                        if ((f & Blocks2.STAND) == 0) cost += 2.0;   // floor has to be filled
                                        if (s.is(Blocks.BEDROCK)) continue site;
                                    }
                                }
                            }
                        }
                    }
                    if (cost < best) {
                        best = cost;
                        bx = cx;
                        bg = cg;
                        bz = cz;
                    }
                }
            }
        }
        if (best == Double.MAX_VALUE) {
            abandonSite();
            return;
        }
        ox = bx; g = bg; oz = bz;
        bot.say("Building a nether portal at " + ox + ", " + g + ", " + oz);
        layout();
        castIdx = 0;
        castState = 0;
        castFails = 0;
        lightCycles = 0;
        go(S.PREP);
    }

    /** Fills {@link #casts} with the ten frame blocks for the origin {@code ox, g, oz}, in building order. */
    private void layout() {
        casts = new ArrayList<>();
        // standing spots two blocks in front of the frame, one block above the cleared floor (the bot has to pillar up)
        BlockPos wl = new BlockPos(ox + 1, g + 1, oz + 2);
        BlockPos wr = new BlockPos(ox + 2, g + 1, oz + 2);
        // bottom row; helper = block in front of the target
        casts.add(new Cast(new BlockPos(ox + 1, g - 1, oz), new BlockPos(ox + 1, g - 1, oz + 1), wl));
        casts.add(new Cast(new BlockPos(ox + 2, g - 1, oz), new BlockPos(ox + 2, g - 1, oz + 1), wr));
        // side columns; helper = block outside the frame
        for (int k = 0; k < 3; k++) {
            int h = Math.max(1, k);
            casts.add(new Cast(new BlockPos(ox, g + k, oz), new BlockPos(ox - 1, g + k, oz), wl.above(h - 1)));
            casts.add(new Cast(new BlockPos(ox + 3, g + k, oz), new BlockPos(ox + 4, g + k, oz), wr.above(h - 1)));
        }
        // top row; helper = the (unused) top corner
        casts.add(new Cast(new BlockPos(ox + 1, g + 3, oz), new BlockPos(ox, g + 3, oz), wl.above(2)));
        casts.add(new Cast(new BlockPos(ox + 2, g + 3, oz), new BlockPos(ox + 3, g + 3, oz), wr.above(2)));
    }

    private boolean isFrame(BlockPos p) {
        if (casts == null) return false;
        for (Cast c : casts) if (c.target.equals(p) || c.helper.equals(p)) return true;
        return false;
    }

    // ------------------------------------------------------------------ site preparation

    /** Digs out the 8x5x5 work volume and fills holes in its floor, nearest / highest block first. */
    private void prep() {
        bot.setStatus("clearing the portal site");
        if (stateTicks > 6000) {
            abandonSite();
            return;
        }
        if (workTarget != null && satisfied(workTarget)) workTarget = null;
        if (workTarget == null) {
            BlockPos f = bot.feet();
            double best = Double.MAX_VALUE;
            for (int x = ox - 2; x <= ox + 5; x++) {
                for (int z = oz - 1; z <= oz + 3; z++) {
                    for (int y = g + 4; y >= g - 1; y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (satisfied(p)) continue;
                        double d = p.distSqr(f) - (y - g) * 3;   // prefer higher blocks (top-down clearing)
                        if (d < best) {
                            best = d;
                            workTarget = p;
                        }
                    }
                }
            }
            if (workTarget == null) {
                go(S.BUILD);
                return;
            }
            workGoal = Goal.reach(workTarget, 3.6);
        }
        workOn(workTarget, workTarget.getY() == g - 1);
    }

    /** Floor blocks must be standable (or part of the frame), everything above must be passable. */
    private boolean satisfied(BlockPos p) {
        int f = fl(p);
        if (p.getY() == g - 1) return (f & Blocks2.STAND) != 0 || isFrame(p);
        return (f & Blocks2.PASS) != 0;
    }

    /**
     * Walks into reach of {@code p} and breaks it; with {@code fill} a passable block is filled instead
     * (a non-passable, non-standable floor block is broken first).
     */
    private void workOn(BlockPos p, boolean fill) {
        BlockPos f = bot.feet();
        boolean settled = bot.player().onGround();
        if (settled && bot.act.eyeDist(p) <= 4.2 && !f.equals(p) && !f.above().equals(p)) {
            bot.nav.reset();
            if (fill) {
                if ((fl(p) & Blocks2.PASS) == 0) bot.act.breakBlock(p);
                else bot.act.placeBlock(p);
            } else bot.act.breakBlock(p);
            return;
        }
        Nav.Status st = bot.nav.goTo(workGoal);
        if (st == Nav.Status.FAILED) workTarget = null;
    }

    private boolean isWater(BlockPos p) { return bs(p).getFluidState().is(FluidTags.WATER); }

    // ------------------------------------------------------------------ casting

    /**
     * Casts the frame one block at a time. Steps ({@link #castState}):
     * 0 make sure we carry lava and water, 1 walk to the standing spot, 2 clear the target, place the helper block
     * and clear the space above it, 3 pour water on top of the helper, 4 wait for the water to flow over the target,
     * 5 pour lava into the target (it turns to obsidian), 6 scoop the water back up, 7 next block.
     */
    private void build() {
        if (castIdx >= casts.size()) {
            go(S.CLEAN);
            return;
        }
        if (stateTicks > 30000 || castFails > 8) {
            bot.say("Portal attempt failed here, trying another spot.");
            abandonSite();
            return;
        }
        Cast c = casts.get(castIdx);
        bot.setStatus("casting obsidian " + (castIdx + 1) + "/" + casts.size());
        if (bs(c.target).is(Blocks.OBSIDIAN) && castState < 6) castState = 6;
        castTicks++;
        BlockPos q = c.helper.above();   // where the water source goes
        switch (castState) {
            case 0 -> {
                if (bot.inv.count(Items.LAVA_BUCKET) == 0) {
                    if (bot.inv.count(Items.BUCKET) == 0) {
                        // no bucket to fetch lava with: nothing we can do here. Counting this as a cast failure
                        // would blacklist the pool within 9 ticks, so end the task and let the plan craft one.
                        if (castTicks > 100) needItem("a second bucket");
                        return;
                    }
                    FetchFluidTask t = new FetchFluidTask(bot, true);
                    // fetch from our own pool unless the previous fetch there failed
                    if (lavaFetch == null || !lavaFetch.failed) t.around = lake;
                    lavaFetch = t;
                    run(t);
                    return;
                }
                if (bot.inv.count(Items.WATER_BUCKET) == 0 && !bs(q).getFluidState().isSource()) {
                    if (bot.inv.count(Items.BUCKET) == 0) {
                        // the water is gone and there is no empty bucket to refill (see above)
                        if (castTicks > 100) needItem("a bucket for water");
                        return;
                    }
                    run(new FetchFluidTask(bot, false));
                    return;
                }
                next(1);
            }
            case 1 -> {
                if (goal == null) goal = Goal.block(c.stand);
                Nav.Status st = bot.nav.goTo(goal);
                if (st == Nav.Status.FAILED) { castFails++; goal = null; }
                if (st == Nav.Status.DONE) next(2);
            }
            case 2 -> {
                if (!bot.feet().equals(c.stand)) { next(1); return; }
                BlockState ts = bs(c.target);
                if (!ts.isAir() && ts.getFluidState().isEmpty()) { bot.act.breakBlock(c.target); return; }
                BlockState hs = bs(c.helper);
                if (hs.canBeReplaced()) {
                    bot.act.placeBlock(c.helper);
                    if (castTicks > 100) fail();
                    return;
                }
                BlockState qs = bs(q);
                if (!qs.isAir() && qs.getFluidState().isEmpty()) { bot.act.breakBlock(q); return; }
                next(3);
            }
            case 3 -> {
                if (bs(q).getFluidState().isSource() && isWater(q)) { next(4); return; }
                if (!bot.inv.hold(Items.WATER_BUCKET)) { next(0); return; }
                Vec3 pt = bot.aim.visiblePoint(bot.player().getEyePosition(), c.helper, Direction.UP);
                if (pt == null || castTicks > 60) { fail(); return; }
                bot.act.lookAt(pt);
                bot.act.useItem();
            }
            case 4 -> {
                if ((isWater(c.target) || isWater(c.target.above())) && castTicks > 6) next(5);
                else if (castTicks > 50) next(5);
            }
            case 5 -> {
                BlockState ts = bs(c.target);
                if (ts.is(Blocks.OBSIDIAN)) { next(6); return; }
                if (ts.getFluidState().is(FluidTags.LAVA)) {
                    // lava went in but did not convert: take it back out and retry
                    if (castTicks > 12 && bot.inv.hold(Items.BUCKET)) {
                        bot.act.lookAt(Vec3.atCenterOf(c.target));
                        bot.act.useItem();
                    }
                    if (castTicks > 80) fail();
                    return;
                }
                if (!ts.isAir() && ts.getFluidState().isEmpty()) { next(2); return; }
                if (bot.inv.count(Items.LAVA_BUCKET) == 0) { if (castTicks > 20) next(0); return; }
                if (!bot.inv.hold(Items.LAVA_BUCKET)) return;
                Vec3 pt = bot.aim.faceFor(c.target);
                if (pt == null || castTicks > 60) { fail(); return; }
                bot.act.lookAt(pt);
                bot.act.useItem();
            }
            case 6 -> {
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

    // ------------------------------------------------------------------ finishing

    /** Scoops up left-over water, empties the portal interior and re-checks that every frame block is obsidian. */
    private void clean() {
        bot.setStatus("tidying the portal");
        // something in here cannot be cleared (unreachable / unbreakable block): try to light anyway; light()
        // gives the site up after a few failed rounds
        if (stateTicks > 1200) { go(S.LIGHT); return; }

        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(ox - 2, g, oz - 1), new BlockPos(ox + 5, g + 5, oz + 3))) {
            FluidState fs = bs(p).getFluidState();
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
                if (!s.getFluidState().isEmpty()) { if (stateTicks > 400) go(S.LIGHT); return; }   // wait for it to drain
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

    /** Stands in front of the frame and uses flint and steel on the bottom obsidian. */
    private void light() {
        bot.setStatus("lighting the portal");
        BlockPos inside = new BlockPos(ox + 1, g, oz);
        if (bs(inside).is(Blocks.NETHER_PORTAL)) { portalBlock = inside; go(S.ENTER); return; }
        if (bot.inv.count(Items.FLINT_AND_STEEL) == 0) {
            // without flint and steel this state used to loop LIGHT -> CLEAN -> LIGHT for ever
            if (stateTicks > 100) needItem("flint and steel");
            return;
        }
        if (stateTicks > 600) {
            // five rounds of 30 s without a portal appearing: this frame is not going to work
            if (++lightCycles > 4) {
                bot.say("Could not light this portal, trying another spot.");
                abandonSite();
                return;
            }
            go(S.CLEAN);
            return;
        }
        if (goal == null) goal = Goal.block(new BlockPos(ox + 1, g, oz + 1));
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) goal = null;
        if (st != Nav.Status.DONE) return;
        if (!bot.inv.hold(Items.FLINT_AND_STEEL)) return;
        bot.act.useOn(new BlockPos(ox + 1, g - 1, oz), Direction.UP);
    }

    /** Walks into the portal block and keeps pushing until the dimension changes (handled in {@link #tick()}). */
    private void enter() {
        bot.setStatus("entering the nether portal");
        if (!bs(portalBlock).is(Blocks.NETHER_PORTAL)) { go(S.FIND_PORTAL); return; }
        bot.memory.overworldPortal = portalBlock;
        if (approach != null) {
            Nav.Status st = bot.nav.goTo(approach);
            if (st == Nav.Status.RUNNING && ++approachTicks < 200) return;
            approach = null;
            pushTicks = 0;
            return;
        }
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
        // 8 s of pushing and still here (the trip takes 4 s): we are wedged against the frame, or the portal is on
        // cooldown. Walk to the block in front of an open face and come in again from there.
        if (++pushTicks > 160) {
            approach = sideGoal(bot, portalBlock, approachSide++);
            approachTicks = 0;
            pushTicks = 0;
            return;
        }
        if (stateTicks > 600) go(S.FIND_PORTAL);
    }

    /**
     * Goal for standing directly in front of one of the two open faces of the portal block at {@code portal}
     * ({@code attempt} alternates between them; a blocked side is skipped). Also used by {@link NetherReturnTask}.
     */
    static Goal sideGoal(Bot bot, BlockPos portal, int attempt) {
        BlockState s = bot.level().getBlockState(portal);
        Direction.Axis axis = s.hasProperty(NetherPortalBlock.AXIS) ? s.getValue(NetherPortalBlock.AXIS) : Direction.Axis.X;
        // the portal plane runs along its axis, so the open faces look along the other horizontal axis
        Direction face = axis == Direction.Axis.X ? Direction.SOUTH : Direction.EAST;
        if (attempt % 2 != 0) face = face.getOpposite();
        BlockPos side = portal.relative(face);
        if ((Blocks2.flags(bot.level(), side, bot.level().getBlockState(side)) & Blocks2.PASS) == 0) {
            side = portal.relative(face.getOpposite());
        }
        return Goal.block(side);
    }

    /**
     * One frame block to cast: {@code target} becomes obsidian, {@code helper} is the adjacent solid block the
     * water is poured onto, {@code stand} is where the bot stands while doing it.
     */
    private record Cast(BlockPos target, BlockPos helper, BlockPos stand) {}
}
