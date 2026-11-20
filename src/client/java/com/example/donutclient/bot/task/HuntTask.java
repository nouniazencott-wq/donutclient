package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Reflex;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Hunts a kind of mob for its drops: finds the nearest matching entity, chases and melees it, collects the
 * loot, and wanders in search of more until {@code done} says we have enough. Retreats when health is low.
 */
public final class HuntTask extends Task {
    /** Human readable name of the prey, used in status lines. */
    private final String what;
    private final Predicate<Entity> prey;
    private final Predicate<ItemStack> loot;
    private final BooleanSupplier done;
    private LivingEntity target;
    private Goal goal;
    /** Position {@link #goal} was built for, so the goal is only rebuilt when the target moved. */
    private BlockPos goalPos;
    private Goal explore;
    /** Exploration heading in radians; negative until first chosen. */
    private double heading = -1.0;
    private int chaseTicks;
    private int exploreTicks;
    private final DropCollector drops;
    /** Optional anchor: when set, prey further than {@link #leash} from it is ignored and exploring circles it. */
    public BlockPos home;
    public int leash = 48;
    /** Ticks spent retreating; 0 when not retreating. */
    private int retreat;
    private Goal retreatGoal;
    /** While positive, low health does not start another retreat (the last one timed out without healing). */
    private int retreatCooldown;
    /** Entity ids of prey that could not be reached or caught; skipped by {@link #pick()} until cleared. */
    private final Set<Integer> skipped = new HashSet<>();
    /** Bot tick at which {@link #skipped} was last cleared. */
    private long skippedSince;

    public HuntTask(Bot bot, String what, Predicate<Entity> prey, Predicate<ItemStack> loot, BooleanSupplier done) {
        super(bot);
        this.what = what;
        this.prey = prey;
        this.loot = loot;
        this.done = done;
        this.drops = new DropCollector(bot, loot);
        this.drops.range = 12.0;
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        target = null;
        goal = null;
        explore = null;
    }

    @Override
    public boolean tick() {
        if (done.getAsBoolean()) {
            bot.nav.reset();
            return true;
        }

        if (retreatCooldown > 0) {
            retreatCooldown--;
        }
        // Low health: run 20 blocks directly away from the target until healed (or 45 s passed). Only when
        // something can actually hurt us - the prey itself or a monster nearby. Harmless prey is hunted on:
        // a starving bot cannot heal, and running from the animals that are its food would starve it.
        boolean low = retreat == 0 && retreatCooldown == 0 && bot.player().getHealth() <= 7.0f;
        boolean threatened = low
                && ((target != null && Reflex.isHostile(target)) || bot.reflex.nearestHostile(16.0) != null);
        if (retreat > 0 || threatened) {
            if (retreat == 0) {
                retreat = 1;
                BlockPos feet = bot.feet();
                double awayX = feet.getX();
                double awayZ = feet.getZ();
                if (target != null) {
                    // Mirror the target through our own position.
                    awayX = feet.getX() * 2 - target.getX();
                    awayZ = feet.getZ() * 2 - target.getZ();
                }
                double len = Math.max(1.0, Math.sqrt((awayX - feet.getX()) * (awayX - feet.getX())
                        + (awayZ - feet.getZ()) * (awayZ - feet.getZ())));
                retreatGoal = Goal.xz(feet.getX() + (int) ((awayX - feet.getX()) / len * 20.0),
                        feet.getZ() + (int) ((awayZ - feet.getZ()) / len * 20.0), 3.0);
            }
            bot.setStatus("retreating to recover");
            retreat++;
            if (bot.player().getHealth() >= 15.0f || retreat > 900) {
                if (retreat > 900) {
                    // Not healing (no food): do not start the next retreat right away, or the bot would
                    // do nothing but retreat in 20-block hops for ever. Carry on for a minute first.
                    retreatCooldown = 1200;
                }
                retreat = 0;
                target = null;
                goal = null;
                return false;
            }
            // Arrived (or no way there): stand still rather than re-planning every tick.
            if (retreatGoal != null && bot.nav.goTo(retreatGoal) != Nav.Status.RUNNING) {
                retreatGoal = null;
            }
            return false;
        }

        // Between kills, pick up whatever dropped.
        if ((target == null || !target.isAlive()) && drops.tick()) {
            bot.setStatus("collecting " + what + " drops");
            return false;
        }

        // Give skipped prey another chance every 2 minutes (it moves around).
        if (bot.tick - skippedSince > 2400L) {
            skippedSince = bot.tick;
            skipped.clear();
        }
        if (target == null || !target.isAlive() || target.isRemoved() || ++chaseTicks > 900
                || target.distanceToSqr(bot.player()) > 4900.0) {
            if (target != null && target.isAlive() && chaseTicks > 900) {
                // 45 s of chasing without a kill: do not pick the very same animal again straight away
                skipped.add(target.getId());
            }
            target = pick();
            chaseTicks = 0;
            goal = null;
        }

        if (target == null) {
            bot.setStatus("looking for " + what);
            BlockPos feet = bot.feet();
            if (explore == null || ++exploreTicks > 2400) {
                exploreTicks = 0;
                if (heading < 0.0) {
                    // Pseudo-random initial heading (one of 8 compass directions) derived from the position.
                    heading = Math.floorMod(feet.getX() * 17 + feet.getZ() * 5, 8) * Math.PI / 4.0;
                }
                if (home != null) {
                    // Leashed: hop around a circle of half the leash radius around home.
                    heading += 2.2;
                    int radius = leash / 2;
                    explore = Goal.xz(home.getX() + (int) (Math.cos(heading) * radius),
                            home.getZ() + (int) (Math.sin(heading) * radius), 4.0);
                } else {
                    explore = Goal.xz(feet.getX() + (int) (Math.cos(heading) * 80.0),
                            feet.getZ() + (int) (Math.sin(heading) * 80.0), 8.0);
                }
            }
            Nav.Status status = bot.nav.goTo(explore);
            if (status == Nav.Status.FAILED) {
                heading += Math.PI / 2 + 0.4;
            }
            if (status != Nav.Status.RUNNING) {
                explore = null;
            }
            return false;
        }

        explore = null;
        bot.setStatus("hunting " + what);
        double dist = Math.sqrt(target.getBoundingBox().distanceToSqr(bot.player().getEyePosition()));
        if (dist < 2.9) {
            bot.act.lookAt(target.getBoundingBox().getCenter());
            if (bot.act.attackReady()) {
                bot.inv.holdWeapon();
                bot.act.attack(target);
            }
            if (dist > 2.0) {
                moveTo(target.blockPosition(), 1.5);
            }
            return false;
        }
        moveTo(target.blockPosition(), 1.5);
        return false;
    }

    /** Paths towards {@code pos}, rebuilding the goal only when the target moved noticeably; drops the target if unreachable. */
    private void moveTo(BlockPos pos, double radius) {
        if (goal == null || goalPos == null || goalPos.distSqr(pos) > 2.5) {
            goalPos = pos;
            goal = Goal.near(pos, radius);
        }
        Nav.Status status = bot.nav.goTo(goal);
        if (status == Nav.Status.FAILED) {
            // unreachable: remember it, otherwise pick() hands the same nearest animal back every time
            if (target != null) {
                skipped.add(target.getId());
            }
            target = null;
            goal = null;
        }
    }

    /** Nearest live adult prey within 64 blocks that is not in a remembered danger spot and within the leash. */
    private LivingEntity pick() {
        LivingEntity best = null;
        double bestDist = 4096.0;
        for (Entity entity : bot.level().entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isBaby() || !prey.test(entity)) {
                continue;
            }
            if (skipped.contains(entity.getId())) {
                continue;
            }
            if (bot.memory.dangerous(entity.blockPosition())) {
                continue;
            }
            if (home != null && entity.blockPosition().distSqr(home) > (double) leash * (double) leash) {
                continue;
            }
            double dist = entity.distanceToSqr(bot.player());
            if (dist < bestDist) {
                bestDist = dist;
                best = living;
            }
        }
        return best;
    }
}
