package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The End: destroy every crystal (climbing each tower and popping the crystal
 * from behind a two-block wall so the blast cannot hurt), then melee the
 * dragon whenever it perches, then leave through the exit portal.
 */
public final class EndTask extends Task {
    private enum S { CRYSTALS, DRAGON, EXIT }
    private enum C { GOTO, WALL, HIT }

    private S state = S.CRYSTALS;
    private EndCrystal crystal;
    private C cstate;
    private int dirIdx;
    private BlockPos stand, wall0;
    private Goal goal;
    private int ticks, stateTicks;
    private Goal fleeGoal;
    private int fleeTicks;
    private Goal waitGoal;
    private Goal approach;
    private Goal inlandGoal;
    private boolean inland;
    private boolean wentDeep;
    private Goal deepGoal;
    private MineTask restock;
    private BlockPos exitBlock;
    private int noDragonTicks;

    public EndTask(Bot bot) { super(bot); }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        crystal = null;
        tower = null;
        goal = null;
        restock = null;
    }

    @Override
    public boolean interruptible() { return false; }

    private BlockState bs(BlockPos p) { return bot.level().getBlockState(p); }

    @Override
    public boolean tick() {
        if (bot.mc.gui.screen() instanceof WinScreen win) {
            win.onClose();
            return false;
        }
        if (bot.level().dimension() != Level.END) { bot.inv.preferBlock = null; return true; }
        ticks++;
        stateTicks++;
        checkDragonDead();

        // Stay out of dragon breath. The cloud hugs the ground, so two blocks up is out of it.
        LocalPlayer p = bot.player();
        if (fleeTicks > 0) {
            fleeTicks--;
            bot.setStatus("dodging dragon breath");
            if (bot.nav.goTo(fleeGoal) != Nav.Status.RUNNING) fleeTicks = 0;
            return false;
        }
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!(e instanceof AreaEffectCloud cloud)) continue;
            double r = cloud.getRadius() + 2.0;
            double dx = p.getX() - cloud.getX(), dz = p.getZ() - cloud.getZ();
            if (dx * dx + dz * dz >= r * r) continue;
            double dy = p.getY() - cloud.getY();
            if (dy < -1.5 || dy > 4) continue; // it is above a tunnel we are in, or far below
            if (underCover()) continue;
            if (!p.onGround()) continue; // climbing or falling: keep moving, it will be left behind
            bot.setStatus("dodging dragon breath");
            bot.nav.reset();
            // Run straight out of it; if the way out is a drop, hop up onto a quick pillar instead.
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.3) { dx = -p.getX(); dz = -p.getZ(); len = Math.max(1, Math.sqrt(dx * dx + dz * dz)); }
            boolean ran = false;
            for (double turn : new double[]{0, 0.8, -0.8, 1.6, -1.6}) {
                double ax = (dx * Math.cos(turn) - dz * Math.sin(turn)) / len, az = (dx * Math.sin(turn) + dz * Math.cos(turn)) / len;
                if (safeRun(p.getX() + ax * 1.2, p.getZ() + az * 1.2) && safeRun(p.getX() + ax * 2.4, p.getZ() + az * 2.4)) {
                    bot.act.faceXZ(p.getX() + ax * 5, p.getZ() + az * 5);
                    bot.input.forward = true;
                    bot.input.sprint = true;
                    if (p.horizontalCollision) bot.input.jump = true;
                    ran = true;
                    break;
                }
            }
            if (!ran) {
                bot.input.jump = true;
                if (p.getY() - Math.floor(p.getY()) > 0.9 || !p.onGround()) bot.act.placeBlock(bot.feet());
            }
            return false;
        }

        // First get off the arrival platform and well onto the island: near the rim a shove means the void.
        double r2 = p.getX() * p.getX() + p.getZ() * p.getZ();
        if (r2 < 52 * 52) inland = true;
        if (!inland && state == S.CRYSTALS && tower == null) {
            bot.setStatus("moving onto the main island");
            // Dragon fireballs put their cloud on anyone within a few blocks of the impact, even through
            // rock, so the tunnel has to run well below the surface.
            if (!wentDeep) {
                if (deepGoal == null) deepGoal = Goal.y(Math.max(36, bot.feet().getY() - 9));
                Nav.Status st = bot.nav.goTo(deepGoal);
                if (st != Nav.Status.RUNNING) wentDeep = true;
                return false;
            }
            if (inlandGoal == null) inlandGoal = Goal.xz(0, 0, 48);
            if (bot.nav.goTo(inlandGoal) == Nav.Status.FAILED) inlandGoal = null;
            restock = null;
            return false;
        }

        // Keep enough blocks for the towers.
        if (restock != null) {
            if (restock.tick()) restock = null;
            return false;
        }
        // End stone is the only handy block the dragon cannot smash, so the towers are climbed with it.
        bot.inv.preferBlock = Items.END_STONE;
        if (state == S.CRYSTALS && tower == null && bot.inv.count(Items.END_STONE) < 80) {
            int target = 200;
            restock = new MineTask(bot, "end stone", s -> s.is(Blocks.END_STONE), s -> s.is(Items.END_STONE),
                    () -> bot.inv.count(Items.END_STONE) >= target, true);
            restock.scanRadius = 3;
            return false;
        }

        switch (state) {
            case CRYSTALS -> crystals();
            case DRAGON -> dragon();
            case EXIT -> exit();
        }
        return false;
    }

    private void checkDragonDead() {
        if (bot.memory.dragonDead || ticks % 20 != 0) return;
        var server = bot.serverLevel();
        if (server != null && server.getDragonFight() != null && server.getDragonFight().hasPreviouslyKilledDragon()) {
            bot.memory.dragonDead = true;
            bot.say("The Ender Dragon is dead!");
            state = S.EXIT;
            stateTicks = 0;
            goal = null;
        }
    }

    // ---- crystals ---------------------------------------------------------------------------

    private static final Direction[] DIRS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    /** One of the ten obsidian towers. */
    private static final class Tower {
        final BlockPos crystalPos;
        int fails;

        Tower(BlockPos p) { crystalPos = p; }
    }

    private java.util.List<Tower> towers;
    private Tower tower;

    private EndCrystal crystalAt(BlockPos pos) {
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof EndCrystal ec && ec.isAlive() && ec.blockPosition().distSqr(pos) < 9) return ec;
        }
        return null;
    }

    private void crystals() {
        if (towers == null) {
            // The integrated server knows where the towers are.
            towers = new java.util.ArrayList<>();
            var server = bot.serverLevel();
            if (server != null) {
                for (var spike : net.minecraft.world.level.levelgen.feature.EndSpikeFeature.getSpikesForLevel(server)) {
                    towers.add(new Tower(new BlockPos(spike.getCenterX(), spike.getHeight() + 1, spike.getCenterZ())));
                }
            }
        }
        if (tower != null) {
            crystal = crystalAt(tower.crystalPos);
            double dx = bot.player().getX() - tower.crystalPos.getX(), dz = bot.player().getZ() - tower.crystalPos.getZ();
            if (crystal == null && dx * dx + dz * dz < 24 * 24) {
                // Close enough to see it, and it is not there: destroyed.
                bot.say("Crystal at " + tower.crystalPos.getX() + ", " + tower.crystalPos.getZ() + " is gone ("
                        + (towers.size() - 1) + " towers left).");
                towers.remove(tower);
                tower = null;
                goal = null;
            }
        }
        if (tower == null) {
            double best = Double.MAX_VALUE;
            for (Tower t : towers) {
                if (t.fails >= 4) continue;
                double d = t.crystalPos.distSqr(bot.feet());
                if (d < best) { best = d; tower = t; }
            }
            if (tower == null) {
                bot.say("All reachable crystals are gone. Time for the dragon.");
                state = S.DRAGON;
                stateTicks = 0;
                return;
            }
            bot.say("Going after the end crystal at " + tower.crystalPos.toShortString());
            dirIdx = pickDir();
            setupStand();
            return;
        }
        BlockPos cp = tower.crystalPos;
        bot.setStatus("destroying the crystal at " + cp.getX() + ", " + cp.getZ());
        if (stateTicks > 20 * 60 * 8) { failCrystal(); return; }
        switch (cstate) {
            case GOTO -> {
                // Come in underground and surface right at the foot of the tower.
                BlockPos f = bot.feet();
                double hd = Math.sqrt((double) (f.getX() - stand.getX()) * (f.getX() - stand.getX())
                        + (double) (f.getZ() - stand.getZ()) * (f.getZ() - stand.getZ()));
                if (hd > 6 && f.getY() < stand.getY() - 6) {
                    if (approach == null) approach = Goal.xz(stand.getX(), stand.getZ(), 3);
                    if (bot.nav.goTo(approach) == Nav.Status.FAILED) approach = null;
                    return;
                }
                approach = null;
                if (goal == null) goal = Goal.block(stand);
                Nav.Status st = bot.nav.goTo(goal);
                if (st == Nav.Status.DONE) { cstate = C.WALL; bot.nav.reset(); }
                else if (st == Nav.Status.FAILED) {
                    dirIdx = (dirIdx + 1) % 4;
                    if (++tower.fails >= 4) { tower = null; return; }
                    setupStand();
                }
            }
            case WALL -> {
                if (!bot.feet().equals(stand)) { cstate = C.GOTO; goal = null; return; }
                for (BlockPos w : new BlockPos[]{wall0, wall0.above()}) {
                    BlockState s = bs(w);
                    if (s.isCollisionShapeFullBlock(bot.level(), w)) continue;
                    bot.act.fastPlace = true;
                    if (!s.isAir() && !s.canBeReplaced()) bot.act.breakBlock(w);
                    else bot.act.placeBlock(w);
                    bot.act.fastPlace = false;
                    return;
                }
                cstate = C.HIT;
            }
            case HIT -> {
                if (!bot.feet().equals(stand)) { cstate = C.GOTO; goal = null; return; }
                BlockState a = bs(wall0), b = bs(wall0.above());
                if (!a.isCollisionShapeFullBlock(bot.level(), wall0) || !b.isCollisionShapeFullBlock(bot.level(), wall0.above())) {
                    cstate = C.WALL;
                    return;
                }
                if (crystal == null) return;
                bot.act.lookAt(crystal.getBoundingBox().getCenter());
                if (bot.act.attackReady()) bot.act.attack(crystal);
            }
        }
    }

    private void failCrystal() {
        if (tower != null) tower.fails = 4;
        tower = null;
        crystal = null;
        goal = null;
        stateTicks = 0;
    }

    private int pickDir() {
        BlockPos cp = tower.crystalPos;
        double dx = bot.player().getX() - cp.getX(), dz = bot.player().getZ() - cp.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 0 : 2;
        return dz > 0 ? 1 : 3;
    }

    /** Works out where to stand and where the blast wall goes for the current side of the tower. */
    private void setupStand() {
        BlockPos cp = tower.crystalPos;
        int h = cp.getY() - 1; // the bedrock block under the crystal; also the level the bot stands at
        Direction d = DIRS[dirIdx];
        BlockPos base = new BlockPos(cp.getX(), h, cp.getZ());
        // The crystal's own hitbox reaches one block out, so the wall goes two out and the bot three.
        // (On caged towers the wall replaces two of the iron bars.)
        int dist = 3;
        stand = base.relative(d, dist);
        wall0 = base.relative(d, dist - 1);
        cstate = C.GOTO;
        goal = null;
        stateTicks = 0;
    }

    // ---- dragon ------------------------------------------------------------------------------

    private EnderDragon findDragon() {
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof EnderDragon d && d.isAlive()) return d;
        }
        return null;
    }

    private void dragon() {
        EnderDragon dragon = findDragon();
        LocalPlayer p = bot.player();
        if (dragon == null) {
            bot.setStatus("waiting for the dragon");
            if (++noDragonTicks > 200) {
                // Maybe a crystal respawned or was missed; otherwise the fight is over.
                noDragonTicks = 0;
                state = S.CRYSTALS;
                if (towers != null) for (Tower t : towers) t.fails = 0;
            }
            waitNearFountain(null);
            return;
        }
        noDragonTicks = 0;
        // A crystal came into view that was skipped earlier? Deal with it between perches.
        int phase = dragon.getEntityData().get(EnderDragon.DATA_PHASE);
        boolean sitting = phase == 5 || phase == 6 || phase == 7 || phase == 3;
        bot.setStatus(sitting ? "attacking the perched dragon" : "waiting for the dragon to land");
        if (!sitting) { waitNearFountain(dragon); return; }

        // Hit whatever part is in reach, head first.
        EnderDragonPart best = null;
        double bestD = 3.0 * 3.0;
        Vec3 eye = p.getEyePosition();
        for (EnderDragonPart part : dragon.getSubEntities()) {
            double d = part.getBoundingBox().distanceToSqr(eye);
            if (part == dragon.head) d -= 2.0;
            if (d < bestD) { bestD = d; best = part; }
        }
        if (best != null) {
            bot.nav.reset();
            bot.inv.holdWeapon();
            bot.act.lookAt(best.getBoundingBox().getCenter());
            if (bot.act.attackReady()) bot.act.attack(best);
            return;
        }
        BlockPos hp = BlockPos.containing(dragon.head.getX(), dragon.head.getY(), dragon.head.getZ());
        if (goal == null || !goal.at(hp.getX(), hp.getY() - 1, hp.getZ())) goal = Goal.near(hp.below(), 2.6);
        bot.nav.goTo(goal);
    }

    /** Ground to run on at this spot: something to stand on within two blocks down and room for the body. */
    private boolean safeRun(double x, double z) {
        BlockPos f = BlockPos.containing(x, bot.player().getY() + 0.2, z);
        if (!bs(f).getCollisionShape(bot.level(), f).isEmpty() && !bs(f.above()).getCollisionShape(bot.level(), f.above()).isEmpty()) return false;
        for (int d = 1; d <= 3; d++) {
            BlockPos b = f.below(d);
            if (!bs(b).getCollisionShape(bot.level(), b).isEmpty()) return true;
        }
        return false;
    }

    /** Any nearby spot with a solid block overhead that is not inside a breath cloud. */
    private Goal coverGoal() {
        java.util.List<AreaEffectCloud> clouds = new java.util.ArrayList<>();
        for (Entity e : bot.level().entitiesForRendering()) if (e instanceof AreaEffectCloud c) clouds.add(c);
        return new Goal() {
            public boolean at(int x, int y, int z) {
                BlockState up = bs(new BlockPos(x, y + 2, z));
                if (up.isAir() || !up.getFluidState().isEmpty()) return false;
                for (AreaEffectCloud c : clouds) {
                    double dx = x + 0.5 - c.getX(), dz = z + 0.5 - c.getZ(), r = c.getRadius() + 2.5;
                    if (dx * dx + dz * dz < r * r && y - c.getY() > -1.5 && y - c.getY() < 1.5) return false;
                }
                return true;
            }

            public double h(int x, int y, int z) { return 0; }

            public String toString() { return "cover"; }
        };
    }

    private boolean underCover() {
        BlockPos up = bot.feet().above(2);
        return !bs(up).isAir() && bs(up).getFluidState().isEmpty();
    }

    private void waitNearFountain(EnderDragon dragon) {
        if (waitGoal == null) {
            // A burrow a few blocks from the fountain, just under the surface.
            int y = 90;
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(8, y, 0);
            while (y > 30 && bs(m.set(8, y, 0)).isAir()) y--;
            waitGoal = Goal.block(new BlockPos(8, y - 3, 0));
        }
        Nav.Status st = bot.nav.goTo(waitGoal);
        if (st == Nav.Status.DONE && dragon != null) {
            bot.inv.holdWeapon();
            bot.act.lookAt(dragon.getBoundingBox().getCenter());
        }
        if (st == Nav.Status.FAILED) waitGoal = null;
    }

    // ---- exit --------------------------------------------------------------------------------

    private void exit() {
        bot.setStatus("leaving through the exit portal");
        if (exitBlock == null || !bs(exitBlock).is(Blocks.END_PORTAL)) {
            exitBlock = null;
            if (ticks % 20 == 0) {
                List<BlockPos> portal = bot.scan.find(s -> s.is(Blocks.END_PORTAL), 6, 30);
                portal.removeIf(b -> Math.abs(b.getX()) > 4 || Math.abs(b.getZ()) > 4);
                if (!portal.isEmpty()) exitBlock = portal.get(0);
            }
            if (exitBlock == null) { waitNearFountain(null); return; }
            goal = null;
        }
        Vec3 c = Vec3.atBottomCenterOf(exitBlock);
        double dx = bot.player().getX() - c.x, dz = bot.player().getZ() - c.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 3.5 || bot.player().getY() < exitBlock.getY() - 1) {
            if (goal == null) goal = Goal.near(exitBlock.above(), 3.0);
            Nav.Status st = bot.nav.goTo(goal);
            if (st == Nav.Status.FAILED) goal = null;
            if (st == Nav.Status.RUNNING) return;
        }
        bot.nav.reset();
        bot.act.faceXZ(c.x, c.z);
        bot.input.forward = true;
        if (bot.player().onGround() && bot.player().horizontalCollision) bot.input.jump = true;
    }
}
