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
    private final Map<Integer, Integer> crystalFails = new HashMap<>();
    private Goal fleeGoal;
    private int fleeTicks;
    private Goal waitGoal;
    private Goal inlandGoal;
    private MineTask restock;
    private BlockPos exitBlock;
    private int noDragonTicks;

    public EndTask(Bot bot) { super(bot); }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        crystal = null;
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

        // Stay out of dragon breath.
        LocalPlayer p = bot.player();
        if (fleeTicks > 0) {
            fleeTicks--;
            bot.setStatus("dodging dragon breath");
            if (bot.nav.goTo(fleeGoal) != Nav.Status.RUNNING) fleeTicks = 0;
            return false;
        }
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof AreaEffectCloud cloud) {
                double r = cloud.getRadius() + 2.0;
                double dx = p.getX() - cloud.getX(), dz = p.getZ() - cloud.getZ();
                if (dx * dx + dz * dz < r * r && Math.abs(p.getY() - cloud.getY()) < 4) {
                    double len = Math.sqrt(dx * dx + dz * dz);
                    if (len < 0.2) { dx = -p.getX(); dz = -p.getZ(); len = Math.max(1, Math.sqrt(dx * dx + dz * dz)); }
                    // Away from the cloud, leaning toward the middle of the island rather than its rim.
                    double ax = dx / len * 9 - Math.signum(p.getX()) * 3, az = dz / len * 9 - Math.signum(p.getZ()) * 3;
                    fleeGoal = Goal.xz((int) Math.floor(p.getX() + ax), (int) Math.floor(p.getZ() + az), 2);
                    fleeTicks = 60;
                    bot.nav.reset();
                    return false;
                }
            }
        }

        // First get off the arrival platform and well onto the island: near the rim a shove means the void.
        double r2 = p.getX() * p.getX() + p.getZ() * p.getZ();
        if (r2 > 62 * 62 && state == S.CRYSTALS && crystal == null) {
            bot.setStatus("moving onto the main island");
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
        if (state == S.CRYSTALS && crystal == null && bot.inv.count(Items.END_STONE) < 80) {
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

    private void crystals() {
        if (crystal != null && (!crystal.isAlive() || crystal.isRemoved())) { crystal = null; goal = null; }
        if (crystal == null) {
            double best = Double.MAX_VALUE;
            int count = 0;
            for (Entity e : bot.level().entitiesForRendering()) {
                if (!(e instanceof EndCrystal ec) || !ec.isAlive()) continue;
                if (ec.getY() < 70) continue; // not a tower crystal
                if (crystalFails.getOrDefault(ec.getId(), 0) >= 4) continue;
                count++;
                double d = ec.distanceToSqr(bot.player());
                if (d < best) { best = d; crystal = ec; }
            }
            if (crystal == null) {
                bot.say("All reachable crystals are gone. Time for the dragon.");
                state = S.DRAGON;
                stateTicks = 0;
                return;
            }
            bot.say("Going after an end crystal at " + crystal.blockPosition().toShortString() + " (" + count + " left)");
            dirIdx = pickDir();
            setupStand();
        }
        BlockPos cp = crystal.blockPosition();
        bot.setStatus("destroying the crystal at " + cp.getX() + ", " + cp.getZ());
        if (stateTicks > 20 * 60 * 6) { failCrystal(); return; }
        switch (cstate) {
            case GOTO -> {
                if (goal == null) goal = Goal.block(stand);
                Nav.Status st = bot.nav.goTo(goal);
                if (st == Nav.Status.DONE) { cstate = C.WALL; bot.nav.reset(); }
                else if (st == Nav.Status.FAILED) {
                    dirIdx = (dirIdx + 1) % 4;
                    crystalFails.merge(crystal.getId(), 1, Integer::sum);
                    if (crystalFails.get(crystal.getId()) >= 4) { crystal = null; return; }
                    setupStand();
                }
            }
            case WALL -> {
                if (!bot.feet().equals(stand)) { cstate = C.GOTO; goal = null; return; }
                for (BlockPos w : new BlockPos[]{wall0, wall0.above()}) {
                    BlockState s = bs(w);
                    if (s.isCollisionShapeFullBlock(bot.level(), w)) continue;
                    if (!s.isAir() && !s.canBeReplaced()) bot.act.breakBlock(w);
                    else bot.act.placeBlock(w);
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
                bot.act.lookAt(crystal.getBoundingBox().getCenter());
                if (bot.act.attackReady()) bot.act.attack(crystal);
            }
        }
    }

    private void failCrystal() {
        crystalFails.merge(crystal.getId(), 4, Integer::sum);
        crystal = null;
        goal = null;
        stateTicks = 0;
    }

    private int pickDir() {
        BlockPos cp = crystal.blockPosition();
        double dx = bot.player().getX() - cp.getX(), dz = bot.player().getZ() - cp.getZ();
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? 0 : 2;
        return dz > 0 ? 1 : 3;
    }

    /** Works out where to stand and where the blast wall goes for the current side of the tower. */
    private void setupStand() {
        BlockPos cp = crystal.blockPosition();
        int h = cp.getY() - 1; // the bedrock block under the crystal; also the level the bot stands at
        Direction d = DIRS[dirIdx];
        BlockPos base = new BlockPos(cp.getX(), h, cp.getZ());
        boolean caged = bs(base.relative(d, 2)).is(Blocks.IRON_BARS) || bs(base.relative(d, 2).above()).is(Blocks.IRON_BARS);
        int dist = caged ? 3 : 2;
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
                crystalFails.clear();
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

    private void waitNearFountain(EnderDragon dragon) {
        if (waitGoal == null) waitGoal = Goal.xz(5, 0, 1.5);
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
