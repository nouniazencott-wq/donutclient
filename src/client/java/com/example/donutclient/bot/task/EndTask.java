package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.phys.Vec3;

/**
 * The End fight: walk onto the main island, stock up on end stone, destroy the end crystals
 * (pillaring up beside each tower and punching the crystal from behind a two-block wall),
 * melee the dragon whenever it perches on the fountain, then leave through the exit portal.
 * Finishes once the player is no longer in the End.
 */
public final class EndTask extends Task {
    private enum S { CRYSTALS, DRAGON, EXIT }

    /** Sub-state while working on one crystal tower. */
    private enum C { GOTO, WALL, HIT }

    private S state = S.CRYSTALS;
    /** The live crystal entity of the current tower, or null once it is gone. */
    private EndCrystal crystal;
    private C cstate;
    /** Index into {@link #DIRS}: the side of the tower the bot stands on. */
    private int dirIdx;
    /** Block to stand in, level with the bedrock under the crystal, 3 blocks out from it. */
    private BlockPos stand;
    /** Lower block of the blast wall between {@link #stand} and the crystal. */
    private BlockPos wall0;
    private Goal goal;
    private int ticks;
    /** Ticks since the current stage / tower attempt started. */
    private int stateTicks;
    private Goal waitGoal;
    /** Fallback goal towards the fountain while its column is not loaded yet. */
    private Goal fountainGoal;
    /** Ticks spent hitting the current crystal from the current side. */
    private int hitTicks;
    /** Ticks spent in {@link #exit()} since the last replan. */
    private int exitTicks;
    /** True while the bot sits out a perch in its burrow to regenerate health. */
    private boolean recovering;
    /** Coarse ground-level goal used to get near a tower before pillaring up. */
    private Goal approach;
    private Goal inlandGoal;
    /** Set once the bot has been within 52 blocks of the island centre. */
    private boolean inland;
    private boolean wentDeep;
    private Goal deepGoal;
    /** Running end-stone mining sub-task, if any. */
    private MineTask restock;
    private BlockPos exitBlock;
    private int noDragonTicks;

    private static final Direction[] DIRS = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};

    /** One obsidian pillar and how often the bot failed to reach it. */
    private static final class Tower {
        final BlockPos crystalPos;
        int fails;

        Tower(BlockPos crystalPos) {
            this.crystalPos = crystalPos;
        }
    }

    private List<Tower> towers;
    private Tower tower;

    public EndTask(Bot bot) {
        super(bot);
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        crystal = null;
        tower = null;
        goal = null;
        restock = null;
    }

    @Override
    public boolean interruptible() {
        return false;
    }

    private BlockState bs(BlockPos pos) {
        return bot.level().getBlockState(pos);
    }

    @Override
    public boolean tick() {
        // Skip the credits when they come up.
        if (bot.mc.gui.screen() instanceof WinScreen win) {
            win.onClose();
            return false;
        }
        if (bot.level().dimension() != Level.END) {
            bot.inv.preferBlock = null;
            return true;
        }
        ticks++;
        stateTicks++;
        checkDragonDead();
        LocalPlayer player = bot.player();

        // Standing in (or just above) a dragon-breath cloud: run straight out of it.
        for (Entity entity : bot.level().entitiesForRendering()) {
            if (!(entity instanceof AreaEffectCloud cloud)) {
                continue;
            }
            double radius = cloud.getRadius() + 2.0;
            double dx = player.getX() - cloud.getX();
            double dz = player.getZ() - cloud.getZ();
            if (dx * dx + dz * dz >= radius * radius) {
                continue;
            }
            double dy = player.getY() - cloud.getY();
            if (dy < -1.5 || dy > 4.0) {
                continue;
            }
            if (underCover()) {
                continue;
            }
            // (Also while airborne: a hop over an obstacle must not hand control back to the
            // normal logic in the middle of a cloud.)
            bot.setStatus("dodging dragon breath");
            bot.nav.reset();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.3) {
                // Dead centre of the cloud: head for the island centre instead.
                dx = -player.getX();
                dz = -player.getZ();
                len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
            }
            boolean running = false;
            // Try straight away from the cloud first, then rotated left/right. The first pass only
            // accepts directions that do not lead into another cloud; the second takes any.
            for (int pass = 0; pass < 2 && !running; pass++) {
                for (double angle : new double[] {0.0, 0.8, -0.8, 1.6, -1.6}) {
                    double ux = (dx * Math.cos(angle) - dz * Math.sin(angle)) / len;
                    double uz = (dx * Math.sin(angle) + dz * Math.cos(angle)) / len;
                    if (!safeRun(player.getX() + ux * 1.2, player.getZ() + uz * 1.2)
                            || !safeRun(player.getX() + ux * 2.4, player.getZ() + uz * 2.4)) {
                        continue;
                    }
                    if (pass == 0 && (inOtherCloud(cloud, player.getX() + ux * 2.4, player.getZ() + uz * 2.4)
                            || inOtherCloud(cloud, player.getX() + ux * 4.8, player.getZ() + uz * 4.8))) {
                        continue;
                    }
                    bot.act.faceXZ(player.getX() + ux * 5.0, player.getZ() + uz * 5.0);
                    bot.input.forward = true;
                    bot.input.sprint = true;
                    if (player.horizontalCollision) {
                        bot.input.jump = true;
                    }
                    running = true;
                    break;
                }
            }
            if (!running) {
                // Nowhere to run: pillar up out of the cloud.
                bot.input.jump = true;
                if (player.getY() - Math.floor(player.getY()) > 0.9 || !player.onGround()) {
                    bot.act.placeBlock(bot.feet());
                }
            }
            return false;
        }

        double centerDistSq = player.getX() * player.getX() + player.getZ() * player.getZ();
        if (centerDistSq < 2704.0) {
            inland = true;
        }
        if (!inland && state == S.CRYSTALS && tower == null) {
            // Arrived on the obsidian platform: dig down a little, then walk to the island.
            bot.setStatus("moving onto the main island");
            if (!wentDeep) {
                if (deepGoal == null) {
                    deepGoal = Goal.y(Math.max(36, bot.feet().getY() - 9));
                }
                Nav.Status status = bot.nav.goTo(deepGoal);
                if (status != Nav.Status.RUNNING) {
                    wentDeep = true;
                }
                return false;
            }
            if (inlandGoal == null) {
                inlandGoal = Goal.xz(0, 0, 48.0);
            }
            if (bot.nav.goTo(inlandGoal) == Nav.Status.FAILED) {
                inlandGoal = null;
            }
            restock = null;
            return false;
        }

        if (restock != null && state != S.CRYSTALS) {
            // The dragon died (or the stage changed) while mining end stone: the blocks are no
            // longer needed, so do not let the mining hold up the fight or the exit.
            restock.onInterrupted();
            restock = null;
            bot.nav.reset();
        }
        if (restock != null) {
            if (restock.tick()) {
                restock = null;
            }
            return false;
        }
        bot.inv.preferBlock = Items.END_STONE;
        if (state == S.CRYSTALS && tower == null && bot.inv.count(Items.END_STONE) < 80) {
            int want = 200;
            restock = new MineTask(bot, "end stone", block -> block.is(Blocks.END_STONE), stack -> stack.is(Items.END_STONE),
                    () -> bot.inv.count(Items.END_STONE) >= want, true);
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

    /** Once a second, asks the integrated server whether the dragon has been killed. */
    private void checkDragonDead() {
        if (bot.memory.dragonDead || ticks % 20 != 0) {
            return;
        }
        ServerLevel server = bot.serverLevel();
        if (server != null && server.getDragonFight() != null && server.getDragonFight().hasPreviouslyKilledDragon()) {
            bot.memory.dragonDead = true;
            bot.say("The Ender Dragon is dead!");
            state = S.EXIT;
            stateTicks = 0;
            goal = null;
        }
    }

    /** The live end crystal within 3 blocks of the given position, or null. */
    private EndCrystal crystalAt(BlockPos pos) {
        for (Entity entity : bot.level().entitiesForRendering()) {
            if (entity instanceof EndCrystal found && found.isAlive() && found.blockPosition().distSqr(pos) < 9.0) {
                return found;
            }
        }
        return null;
    }

    /** Destroys the end crystals one tower at a time. */
    private void crystals() {
        if (towers == null) {
            towers = new ArrayList<>();
            ServerLevel server = bot.serverLevel();
            if (server != null) {
                for (EndSpikeFeature.EndSpike spike : EndSpikeFeature.getSpikesForLevel(server)) {
                    towers.add(new Tower(new BlockPos(spike.getCenterX(), spike.getHeight() + 1, spike.getCenterZ())));
                }
            }
        }
        if (tower != null) {
            crystal = crystalAt(tower.crystalPos);
            double dx = bot.player().getX() - tower.crystalPos.getX();
            double dz = bot.player().getZ() - tower.crystalPos.getZ();
            // Only trust "no crystal entity" when close enough for it to be loaded.
            if (crystal == null && dx * dx + dz * dz < 576.0) {
                bot.say("Crystal at " + tower.crystalPos.getX() + ", " + tower.crystalPos.getZ() + " is gone (" + (towers.size() - 1) + " towers left).");
                towers.remove(tower);
                tower = null;
                goal = null;
            }
        }
        if (tower == null) {
            double best = Double.MAX_VALUE;
            for (Tower candidate : towers) {
                if (candidate.fails >= 4) {
                    continue;
                }
                double dist = candidate.crystalPos.distSqr(bot.feet());
                if (dist < best) {
                    best = dist;
                    tower = candidate;
                }
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

        BlockPos crystalPos = tower.crystalPos;
        bot.setStatus("destroying the crystal at " + crystalPos.getX() + ", " + crystalPos.getZ());
        if (stateTicks > 9600) {
            failCrystal();
            return;
        }
        if (cstate != C.HIT && bot.inv.countThrowaway() < (cstate == C.GOTO ? 8 : 1)) {
            // Running out of building blocks half-way up: let go of the tower without counting a
            // failure, so that tick() mines more end stone (it does that only between towers)
            // instead of the pillar path failing until the tower is given up.
            tower = null;
            crystal = null;
            goal = null;
            approach = null;
            bot.nav.reset();
            return;
        }
        switch (cstate) {
            case GOTO -> {
                BlockPos feet = bot.feet();
                double horiz = Math.sqrt((double) (feet.getX() - stand.getX()) * (feet.getX() - stand.getX())
                        + (double) (feet.getZ() - stand.getZ()) * (feet.getZ() - stand.getZ()));
                if (horiz > 6.0 && feet.getY() < stand.getY() - 6) {
                    // Far below and away: first walk to the foot of the tower.
                    if (approach == null) {
                        approach = Goal.xz(stand.getX(), stand.getZ(), 3.0);
                    }
                    if (bot.nav.goTo(approach) == Nav.Status.FAILED) {
                        approach = null;
                    }
                    return;
                }
                approach = null;
                if (goal == null) {
                    goal = Goal.block(stand);
                }
                Nav.Status status = bot.nav.goTo(goal);
                if (status == Nav.Status.DONE) {
                    cstate = C.WALL;
                    bot.nav.reset();
                } else if (status == Nav.Status.FAILED) {
                    // Try the next side of the tower; give the tower up after 4 failures.
                    dirIdx = (dirIdx + 1) % 4;
                    if (++tower.fails >= 4) {
                        tower = null;
                        return;
                    }
                    setupStand();
                }
            }
            case WALL -> {
                if (!bot.feet().equals(stand)) {
                    cstate = C.GOTO;
                    goal = null;
                    return;
                }
                // Build a two-high wall towards the crystal to shield against the explosion.
                for (BlockPos pos : new BlockPos[] {wall0, wall0.above()}) {
                    BlockState block = bs(pos);
                    if (block.isCollisionShapeFullBlock(bot.level(), pos)) {
                        continue;
                    }
                    bot.act.fastPlace = true;
                    if (!block.isAir() && !block.canBeReplaced()) {
                        bot.act.breakBlock(pos);
                    } else {
                        bot.act.placeBlock(pos);
                    }
                    bot.act.fastPlace = false;
                    return;
                }
                cstate = C.HIT;
            }
            case HIT -> {
                if (!bot.feet().equals(stand)) {
                    cstate = C.GOTO;
                    goal = null;
                    return;
                }
                BlockState lower = bs(wall0);
                BlockState upper = bs(wall0.above());
                if (!lower.isCollisionShapeFullBlock(bot.level(), wall0) || !upper.isCollisionShapeFullBlock(bot.level(), wall0.above())) {
                    cstate = C.WALL;
                    return;
                }
                if (crystal == null) {
                    return;
                }
                if (++hitTicks > 400) {
                    // 20 seconds of punching without the crystal blowing up (out of reach from
                    // here?): try the next side, like a failed approach.
                    dirIdx = (dirIdx + 1) % 4;
                    if (++tower.fails >= 4) {
                        tower = null;
                        return;
                    }
                    setupStand();
                    return;
                }
                bot.act.lookAt(crystal.getBoundingBox().getCenter());
                if (bot.act.attackReady()) {
                    bot.act.attack(crystal);
                }
            }
        }
    }

    /** Gives up on the current tower for now (marks it as failed 4 times; see the retry in dragon()). */
    private void failCrystal() {
        if (tower != null) {
            tower.fails = 4;
        }
        tower = null;
        crystal = null;
        goal = null;
        stateTicks = 0;
    }

    /** Index into {@link #DIRS} of the tower side facing the player. */
    private int pickDir() {
        BlockPos crystalPos = tower.crystalPos;
        double dx = bot.player().getX() - crystalPos.getX();
        double dz = bot.player().getZ() - crystalPos.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0.0 ? 0 : 2;
        }
        return dz > 0.0 ? 1 : 3;
    }

    /** Computes the standing spot and wall position for the current tower and side. */
    private void setupStand() {
        BlockPos crystalPos = tower.crystalPos;
        int y = crystalPos.getY() - 1;
        Direction dir = DIRS[dirIdx];
        BlockPos top = new BlockPos(crystalPos.getX(), y, crystalPos.getZ());
        int dist = 3;
        stand = top.relative(dir, dist);
        wall0 = top.relative(dir, dist - 1);
        cstate = C.GOTO;
        goal = null;
        approach = null;
        stateTicks = 0;
        hitTicks = 0;
    }

    private EnderDragon findDragon() {
        for (Entity entity : bot.level().entitiesForRendering()) {
            if (entity instanceof EnderDragon dragon && dragon.isAlive()) {
                return dragon;
            }
        }
        return null;
    }

    /** Waits by the fountain and melees the dragon while it is perched. */
    private void dragon() {
        EnderDragon dragon = findDragon();
        LocalPlayer player = bot.player();
        if (dragon == null) {
            bot.setStatus("waiting for the dragon");
            if (++noDragonTicks > 200) {
                // No dragon in sight for 10s: re-check the towers (maybe crystals were missed).
                noDragonTicks = 0;
                state = S.CRYSTALS;
                if (towers != null) {
                    for (Tower t : towers) {
                        t.fails = 0;
                    }
                }
            }
            waitNearFountain(null);
            return;
        }
        noDragonTicks = 0;
        int phase = dragon.getEntityData().get(EnderDragon.DATA_PHASE);
        // Phase ids: 3 = landing, 5/6/7 = sitting (flaming / scanning / attacking).
        boolean perched = phase == 5 || phase == 6 || phase == 7 || phase == 3;
        bot.setStatus(perched ? "attacking the perched dragon" : "waiting for the dragon to land");
        if (!perched) {
            recovering = false;
            if (stateTicks > 3600 && towers != null && !towers.isEmpty()) {
                // Crystals that could not be reached earlier keep healing the dragon: every three
                // minutes give the abandoned towers another try instead of writing them off for good.
                bot.say("Trying the remaining " + towers.size() + " end crystal(s) again.");
                for (Tower t : towers) {
                    t.fails = 0;
                }
                state = S.CRYSTALS;
                stateTicks = 0;
                tower = null;
                crystal = null;
                goal = null;
                return;
            }
            waitNearFountain(dragon);
            return;
        }
        // Badly hurt but fed well enough to regenerate: sit this perch out in the burrow rather
        // than walking into the dragon's breath and wings (hysteresis: back in at 7 hearts).
        boolean canRegen = player.getFoodData().getFoodLevel() >= 18;
        if (player.getHealth() < 8.0F && canRegen) {
            recovering = true;
        } else if (player.getHealth() >= 14.0F || !canRegen) {
            recovering = false;
        }
        if (recovering) {
            bot.setStatus("recovering health before attacking the dragon");
            waitNearFountain(dragon);
            return;
        }
        // Closest dragon part within 3 blocks of the eyes, with a bias towards the head.
        EnderDragonPart target = null;
        double best = 9.0;
        Vec3 eye = player.getEyePosition();
        for (EnderDragonPart part : dragon.getSubEntities()) {
            double dist = part.getBoundingBox().distanceToSqr(eye);
            if (part == dragon.head) {
                dist -= 2.0;
            }
            if (dist < best) {
                best = dist;
                target = part;
            }
        }
        if (target != null) {
            bot.nav.reset();
            bot.inv.holdWeapon();
            bot.act.lookAt(target.getBoundingBox().getCenter());
            if (bot.act.attackReady()) {
                bot.act.attack(target);
            }
            return;
        }
        BlockPos head = BlockPos.containing(dragon.head.getX(), dragon.head.getY(), dragon.head.getZ());
        if (goal == null || !goal.at(head.getX(), head.getY() - 1, head.getZ())) {
            goal = Goal.near(head.below(), 2.6);
        }
        bot.nav.goTo(goal);
    }

    /** True if the bot could run to (x, z): not walled off, and with ground within 3 blocks below. */
    private boolean safeRun(double x, double z) {
        BlockPos pos = BlockPos.containing(x, bot.player().getY() + 0.2, z);
        if (!bs(pos).getCollisionShape(bot.level(), pos).isEmpty()
                && !bs(pos.above()).getCollisionShape(bot.level(), pos.above()).isEmpty()) {
            return false;
        }
        for (int down = 1; down <= 3; down++) {
            BlockPos below = pos.below(down);
            if (!bs(below).getCollisionShape(bot.level(), below).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** True if (x, z) at the bot's height lies inside a breath cloud other than {@code current}. */
    private boolean inOtherCloud(AreaEffectCloud current, double x, double z) {
        double y = bot.player().getY();
        for (Entity entity : bot.level().entitiesForRendering()) {
            if (!(entity instanceof AreaEffectCloud cloud) || cloud == current) {
                continue;
            }
            double dx = x - cloud.getX();
            double dz = z - cloud.getZ();
            double radius = cloud.getRadius() + 1.0;
            if (dx * dx + dz * dz < radius * radius && y - cloud.getY() > -1.5 && y - cloud.getY() < 4.0) {
                return true;
            }
        }
        return false;
    }

    /** True if there is a solid, non-fluid block directly above the bot's head. */
    private boolean underCover() {
        BlockPos above = bot.feet().above(2);
        return !bs(above).isAir() && bs(above).getFluidState().isEmpty();
    }

    /** Stands 3 blocks below the top of the fountain column at (8, 0), watching the dragon if given. */
    private void waitNearFountain(EnderDragon dragon) {
        if (waitGoal == null) {
            int y = 90;
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(8, y, 0);
            while (y > 30 && bs(probe.set(8, y, 0)).isAir()) {
                y--;
            }
            if (y <= 30) {
                // The fountain column is not loaded (or missing): a goal derived from it would lie
                // in the void under the island. Just walk towards the fountain and look again.
                if (fountainGoal == null) {
                    fountainGoal = Goal.xz(8, 0, 6.0);
                }
                if (bot.nav.goTo(fountainGoal) == Nav.Status.FAILED) {
                    fountainGoal = null;
                }
                return;
            }
            waitGoal = Goal.block(new BlockPos(8, y - 3, 0));
        }
        Nav.Status status = bot.nav.goTo(waitGoal);
        if (status == Nav.Status.DONE) {
            // Standing here is intended, not a stall: keep the plan's stuck detector quiet.
            bot.heartbeat = bot.tick;
        }
        if (status == Nav.Status.DONE && dragon != null) {
            bot.inv.holdWeapon();
            bot.act.lookAt(dragon.getBoundingBox().getCenter());
        }
        if (status == Nav.Status.FAILED) {
            waitGoal = null;
        }
    }

    /** Finds the exit portal in the fountain and walks into it. */
    private void exit() {
        bot.setStatus("leaving through the exit portal");
        if (++exitTicks > 600) {
            // Still in the End after 30 seconds: forget the chosen portal block and every goal,
            // then look and plan afresh instead of pushing against the same obstacle forever.
            exitTicks = 0;
            exitBlock = null;
            goal = null;
            waitGoal = null;
            bot.nav.reset();
        }
        if (exitBlock == null || !bs(exitBlock).is(Blocks.END_PORTAL)) {
            exitBlock = null;
            if (ticks % 20 == 0) {
                List<BlockPos> portals = bot.scan.find(block -> block.is(Blocks.END_PORTAL), 6, 30);
                // Only the fountain portal at the origin counts (not gateways elsewhere).
                portals.removeIf(pos -> Math.abs(pos.getX()) > 4 || Math.abs(pos.getZ()) > 4);
                if (!portals.isEmpty()) {
                    exitBlock = portals.get(0);
                }
            }
            if (exitBlock == null) {
                waitNearFountain(null);
                return;
            }
            goal = null;
        }
        Vec3 target = Vec3.atBottomCenterOf(exitBlock);
        double dx = bot.player().getX() - target.x;
        double dz = bot.player().getZ() - target.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 3.5 || bot.player().getY() < exitBlock.getY() - 1) {
            if (goal == null) {
                goal = Goal.near(exitBlock.above(), 3.0);
            }
            Nav.Status status = bot.nav.goTo(goal);
            if (status == Nav.Status.FAILED) {
                goal = null;
            }
            if (status == Nav.Status.RUNNING) {
                return;
            }
        }
        bot.nav.reset();
        bot.act.faceXZ(target.x, target.z);
        bot.input.forward = true;
        if (bot.player().onGround() && bot.player().horizontalCollision) {
            bot.input.jump = true;
        }
    }
}
