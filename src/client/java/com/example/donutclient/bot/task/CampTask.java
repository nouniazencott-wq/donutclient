package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Fights from a fortified position instead of in the open.
 *
 * BOX: the bot walls itself in completely and strikes mobs that come within
 * arm's reach (used at blaze spawners: no line of sight means no fireballs).
 * ROOF: the bot stands under a low ceiling that tall mobs cannot get under,
 * stares at endermen to draw them in, and hits them at the ceiling's edge.
 */
public final class CampTask extends Task {
    public enum Mode { BOX, ROOF }

    private enum S { GOTO, BUILD, FIGHT, COLLECT }

    private final Mode mode;
    private final String what;
    private final Predicate<Entity> prey;
    private final Predicate<ItemStack> loot;
    private final BooleanSupplier done;
    private final DropCollector drops;
    /** Where to set up. */
    public BlockPos spot;
    /** Give up on this camp site after this long without a kill (ticks). */
    public int patience = 20 * 150;

    private S state = S.GOTO;
    private Goal goal;
    private int stateTicks;
    private int idleTicks;
    private int dropWait;
    private int buildFails;
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
        this.drops.range = 14;
    }

    private void go(S s) {
        state = s;
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

    private BlockState bs(BlockPos p) { return bot.level().getBlockState(p); }

    /** Blocks that make up the fortification around the given feet position. */
    private List<BlockPos> structure(BlockPos f) {
        List<BlockPos> cells = new ArrayList<>();
        if (mode == Mode.BOX) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                cells.add(f.relative(d));
                cells.add(f.above().relative(d));
            }
            cells.add(f.above(2));
        } else {
            // A two-block post on the east side carries a 3x3 ceiling two blocks up.
            cells.add(f.east());
            cells.add(f.east().above());
            cells.add(f.east().above(2));
            cells.add(f.above(2));
            cells.add(f.above(2).north());
            cells.add(f.above(2).south());
            cells.add(f.above(2).west());
            cells.add(f.above(2).north().east());
            cells.add(f.above(2).south().east());
            cells.add(f.above(2).north().west());
            cells.add(f.above(2).south().west());
        }
        return cells;
    }

    private boolean built() {
        for (BlockPos c : structure(spot)) if (bs(c).canBeReplaced()) return false;
        return true;
    }

    @Override
    public boolean tick() {
        if (done.getAsBoolean()) { bot.reflex.sheltered = false; bot.nav.coverCenter = null; bot.nav.reset(); return true; }
        LocalPlayer p = bot.player();
        stateTicks++;
        bot.reflex.sheltered = false;
        if (state != S.GOTO) bot.nav.coverCenter = null;
        switch (state) {
            case GOTO -> {
                bot.setStatus("setting up to fight " + what);
                if (mode == Mode.BOX) bot.nav.coverCenter = spot; // sneak in through the masonry
                if (goal == null) goal = Goal.block(spot);
                Nav.Status st = bot.nav.goTo(goal);
                if (st == Nav.Status.DONE) go(S.BUILD);
                else if (st == Nav.Status.FAILED || stateTicks > 2400) { exhausted = true; bot.nav.coverCenter = null; return true; }
            }
            case BUILD -> {
                bot.setStatus("fortifying against " + what);
                if (!bot.feet().equals(spot)) { if (stateTicks > 60) go(S.GOTO); return false; }
                double cx = spot.getX() + 0.5, cz = spot.getZ() + 0.5;
                if (Math.abs(p.getX() - cx) > 0.18 || Math.abs(p.getZ() - cz) > 0.18) {
                    bot.act.faceXZ(cx, cz);
                    bot.input.forward = true;
                    bot.input.sneak = true;
                    return false;
                }
                if (mode == Mode.ROOF) {
                    // Headroom for the bot itself must stay clear.
                    for (BlockPos c : new BlockPos[]{spot.above(), spot.north().above(), spot.south().above(), spot.west().above()}) {
                        BlockState s = bs(c);
                        if (!s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty()
                                && s.getDestroySpeed(bot.level(), c) >= 0 && c.equals(spot.above())) {
                            bot.act.breakBlock(c);
                            return false;
                        }
                    }
                }
                for (BlockPos c : structure(spot)) {
                    if (bs(c).canBeReplaced()) {
                        if (bot.inv.countThrowaway() == 0 || stateTicks > 400) {
                            if (++buildFails > 2) { exhausted = true; return true; }
                            go(S.GOTO);
                            return false;
                        }
                        bot.act.fastPlace = true;
                        bot.act.placeBlock(c);
                        bot.act.fastPlace = false;
                        return false;
                    }
                }
                idleTicks = 0;
                go(S.FIGHT);
            }
            case FIGHT -> {
                if (!bot.feet().equals(spot) || !built()) { go(bot.feet().equals(spot) ? S.BUILD : S.GOTO); return false; }
                bot.reflex.sheltered = mode == Mode.BOX;
                bot.setStatus("fighting " + what + " from cover");
                bot.heartbeat = bot.tick;
                idleTicks++;
                Vec3 eye = p.getEyePosition();
                LivingEntity target = null, lure = null;
                double best = 3.0 * 3.0, bestLure = 48 * 48;
                int nearby = 0;
                for (Entity e : bot.level().entitiesForRendering()) {
                    if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
                    boolean wanted = prey.test(e);
                    if (!wanted && !com.example.donutclient.bot.Reflex.isHostile(e)) continue;
                    double d = le.getBoundingBox().distanceToSqr(eye);
                    if (wanted && d < 12 * 12) nearby++;
                    if (d < best) { best = d; target = le; }
                    if (wanted && mode == Mode.ROOF && e instanceof EnderMan em && !em.isCreepy() && d < bestLure
                            && p.hasLineOfSight(e)) {
                        bestLure = d;
                        lure = le;
                    }
                }
                if (target != null) {
                    bot.inv.holdWeapon();
                    bot.act.lookAt(target.getBoundingBox().getCenter());
                    if (bot.act.attackReady()) { bot.act.attack(target); idleTicks = 0; }
                } else if (lure != null) {
                    // Eye contact makes an enderman come to us.
                    bot.act.lookAt(lure.getEyePosition());
                }
                // Go and get the loot when it is reasonably quiet (or it has been lying there a while).
                boolean lootAround = false;
                for (Entity e : bot.level().entitiesForRendering()) {
                    if (e instanceof ItemEntity ie && loot.test(ie.getItem()) && ie.distanceToSqr(p) < 14 * 14) lootAround = true;
                }
                if (lootAround) {
                    dropWait++;
                    if ((target == null && nearby == 0 && dropWait > 20) || dropWait > 20 * 45) { dropWait = 0; go(S.COLLECT); }
                } else dropWait = 0;
                if (idleTicks > patience) { bot.reflex.sheltered = false; exhausted = true; return true; }
            }
            case COLLECT -> {
                bot.setStatus("collecting " + what + " drops");
                if (stateTicks > 400 || !drops.tick()) go(S.GOTO);
            }
        }
        return false;
    }
}
