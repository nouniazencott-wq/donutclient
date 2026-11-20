package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Reflex;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Fights dangerous mobs from a small shelter instead of chasing them: walks to a spot, builds cover around
 * itself, hits anything that comes within reach, and steps out now and then to collect the drops.
 * <ul>
 *   <li>{@link Mode#BOX}: fully enclosed 1x1 cell (four walls two high plus a cap) so blaze fireballs cannot
 *       get in; attacks are sent straight at entities within 3 blocks.</li>
 *   <li>{@link Mode#ROOF}: a wall on the east plus a 3x3 roof two blocks up, so endermen (3 tall) cannot
 *       reach the bot while it stares them into attacking.</li>
 * </ul>
 */
public final class CampTask extends Task {
    /** Shape of the shelter. */
    public enum Mode {
        BOX,
        ROOF
    }

    private enum S {
        GOTO,
        BUILD,
        FIGHT,
        COLLECT
    }

    private final Mode mode;
    /** Human readable name of the prey, used in status lines. */
    private final String what;
    private final Predicate<Entity> prey;
    private final Predicate<ItemStack> loot;
    private final BooleanSupplier done;
    private final DropCollector drops;
    /** Feet position of the shelter. */
    public BlockPos spot;
    /** Ticks without landing a hit before the camp is given up as {@link #exhausted}. */
    public int patience = 3000;
    private S state = S.GOTO;
    private Goal goal;
    private int stateTicks;
    private int idleTicks;
    private int dropWait;
    private int buildFails;
    /** Set when the task ended without {@code done} being satisfied. */
    public boolean exhausted;

    public CampTask(Bot bot, Mode mode, String what, BlockPos spot, Predicate<Entity> prey, Predicate<ItemStack> loot,
            BooleanSupplier done) {
        super(bot);
        this.mode = mode;
        this.what = what;
        this.spot = spot;
        this.prey = prey;
        this.loot = loot;
        this.done = done;
        this.drops = new DropCollector(bot, loot);
        this.drops.range = 14.0;
    }

    private void go(S next) {
        state = next;
        stateTicks = 0;
        goal = null;
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        go(S.GOTO);
        bot.reflex.sheltered = false;
        bot.nav.coverCenter = null;
    }

    private BlockState bs(BlockPos pos) {
        return bot.level().getBlockState(pos);
    }

    /** Positions that must be solid for the shelter around {@code center} (the feet block) to be complete. */
    private List<BlockPos> structure(BlockPos center) {
        List<BlockPos> blocks = new ArrayList<>();
        if (mode == Mode.BOX) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                blocks.add(center.relative(dir));
                blocks.add(center.above().relative(dir));
            }
            blocks.add(center.above(2));
        } else {
            // East wall, three high.
            blocks.add(center.east());
            blocks.add(center.east().above());
            blocks.add(center.east().above(2));
            // 3x3 roof two blocks above the feet (the east-centre cell is the top of the wall).
            blocks.add(center.above(2));
            blocks.add(center.above(2).north());
            blocks.add(center.above(2).south());
            blocks.add(center.above(2).west());
            blocks.add(center.above(2).north().east());
            blocks.add(center.above(2).south().east());
            blocks.add(center.above(2).north().west());
            blocks.add(center.above(2).south().west());
        }
        return blocks;
    }

    private boolean built() {
        for (BlockPos pos : structure(spot)) {
            if (bs(pos).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean tick() {
        if (done.getAsBoolean()) {
            bot.reflex.sheltered = false;
            bot.nav.coverCenter = null;
            bot.nav.reset();
            return true;
        }
        LocalPlayer player = bot.player();
        stateTicks++;
        bot.reflex.sheltered = false;
        if (state != S.GOTO) {
            bot.nav.coverCenter = null;
        }
        switch (state) {
            case GOTO -> {
                bot.setStatus("setting up to fight " + what);
                if (mode == Mode.BOX) {
                    bot.nav.coverCenter = spot;
                }
                if (goal == null) {
                    goal = Goal.block(spot);
                }
                Nav.Status status = bot.nav.goTo(goal);
                if (status == Nav.Status.DONE) {
                    go(S.BUILD);
                } else if (status == Nav.Status.FAILED || stateTicks > 2400) {
                    exhausted = true;
                    bot.nav.coverCenter = null;
                    return true;
                }
            }
            case BUILD -> {
                bot.setStatus("fortifying against " + what);
                if (!bot.feet().equals(spot)) {
                    if (stateTicks > 60) {
                        go(S.GOTO);
                    }
                    return false;
                }
                // Centre on the block first so every wall position is placeable.
                double centerX = spot.getX() + 0.5;
                double centerZ = spot.getZ() + 0.5;
                if (Math.abs(player.getX() - centerX) > 0.18 || Math.abs(player.getZ() - centerZ) > 0.18) {
                    bot.act.faceXZ(centerX, centerZ);
                    bot.input.forward = true;
                    bot.input.sneak = true;
                    return false;
                }
                if (mode == Mode.ROOF) {
                    // Clear the head block so there is room to stand under the roof.
                    for (BlockPos pos : new BlockPos[] {spot.above(), spot.north().above(), spot.south().above(),
                            spot.west().above()}) {
                        BlockState blockState = bs(pos);
                        if (!blockState.isAir() && !blockState.canBeReplaced() && blockState.getFluidState().isEmpty()
                                && blockState.getDestroySpeed(bot.level(), pos) >= 0.0f && pos.equals(spot.above())) {
                            bot.act.breakBlock(pos);
                            return false;
                        }
                    }
                }
                for (BlockPos pos : structure(spot)) {
                    if (bs(pos).canBeReplaced()) {
                        if (bot.inv.countThrowaway() == 0 || stateTicks > 400) {
                            if (++buildFails > 2) {
                                exhausted = true;
                                return true;
                            }
                            go(S.GOTO);
                            return false;
                        }
                        bot.act.fastPlace = true;
                        bot.act.placeBlock(pos);
                        bot.act.fastPlace = false;
                        return false;
                    }
                }
                idleTicks = 0;
                go(S.FIGHT);
            }
            case FIGHT -> {
                if (!bot.feet().equals(spot) || !built()) {
                    go(bot.feet().equals(spot) ? S.BUILD : S.GOTO);
                    return false;
                }
                bot.reflex.sheltered = mode == Mode.BOX;
                bot.setStatus("fighting " + what + " from cover");
                // Standing still on purpose: keep the stuck watchdog quiet.
                bot.heartbeat = bot.tick;
                idleTicks++;

                Vec3 eye = player.getEyePosition();
                LivingEntity inReach = null;
                LivingEntity stareAt = null;
                double reachDist = 9.0;
                double stareDist = 2304.0;
                int preyNearby = 0;
                for (Entity entity : bot.level().entitiesForRendering()) {
                    if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                        continue;
                    }
                    boolean isPrey = prey.test(entity);
                    if (!isPrey && !Reflex.isHostile(entity)) {
                        continue;
                    }
                    double dist = living.getBoundingBox().distanceToSqr(eye);
                    if (isPrey && dist < 144.0) {
                        preyNearby++;
                    }
                    if (dist < reachDist) {
                        reachDist = dist;
                        inReach = living;
                    }
                    // Under a roof: provoke calm endermen by looking them in the eye so they come to us.
                    if (isPrey && mode == Mode.ROOF && entity instanceof EnderMan enderman && !enderman.isCreepy()
                            && dist < stareDist && player.hasLineOfSight(entity)) {
                        stareDist = dist;
                        stareAt = living;
                    }
                }
                if (inReach != null) {
                    bot.inv.holdWeapon();
                    bot.act.lookAt(inReach.getBoundingBox().getCenter());
                    if (bot.act.attackReady()) {
                        bot.act.attack(inReach);
                        idleTicks = 0;
                    }
                } else if (stareAt != null) {
                    bot.act.lookAt(stareAt.getEyePosition());
                }

                boolean dropsNearby = false;
                for (Entity entity : bot.level().entitiesForRendering()) {
                    if (entity instanceof ItemEntity item && loot.test(item.getItem()) && item.distanceToSqr(player) < 196.0) {
                        dropsNearby = true;
                    }
                }
                if (dropsNearby) {
                    dropWait++;
                    // Leave cover for the loot once the coast is clear, or after 45 s regardless.
                    if ((inReach == null && preyNearby == 0 && dropWait > 20) || dropWait > 900) {
                        dropWait = 0;
                        go(S.COLLECT);
                    }
                } else {
                    dropWait = 0;
                }
                if (idleTicks > patience) {
                    bot.reflex.sheltered = false;
                    exhausted = true;
                    return true;
                }
            }
            case COLLECT -> {
                bot.setStatus("collecting " + what + " drops");
                if (stateTicks > 400 || !drops.tick()) {
                    go(S.GOTO);
                }
            }
        }
        return false;
    }
}
