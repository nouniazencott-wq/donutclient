package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Chases and kills mobs of a kind, collecting what they drop. */
public final class HuntTask extends Task {
    private final String what;
    private final Predicate<Entity> prey;
    private final Predicate<ItemStack> loot;
    private final BooleanSupplier done;
    private LivingEntity target;
    private Goal goal;
    private BlockPos goalPos;
    private Goal explore;
    private double heading = -1;
    private int chaseTicks;
    private int exploreTicks;
    private final DropCollector drops;

    public HuntTask(Bot bot, String what, Predicate<Entity> prey, Predicate<ItemStack> loot, BooleanSupplier done) {
        super(bot);
        this.what = what;
        this.prey = prey;
        this.loot = loot;
        this.done = done;
        this.drops = new DropCollector(bot, loot);
        this.drops.range = 12;
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
        if (done.getAsBoolean()) { bot.nav.reset(); return true; }

        if ((target == null || !target.isAlive()) && drops.tick()) {
            bot.setStatus("collecting " + what + " drops");
            return false;
        }

        if (target == null || !target.isAlive() || target.isRemoved() || ++chaseTicks > 900
                || target.distanceToSqr(bot.player()) > 70 * 70) {
            target = pick();
            chaseTicks = 0;
            goal = null;
        }
        if (target == null) {
            bot.setStatus("looking for " + what);
            BlockPos f = bot.feet();
            if (explore == null || ++exploreTicks > 2400) {
                exploreTicks = 0;
                if (heading < 0) heading = Math.floorMod(f.getX() * 17 + f.getZ() * 5, 8) * Math.PI / 4;
                explore = Goal.xz(f.getX() + (int) (Math.cos(heading) * 80), f.getZ() + (int) (Math.sin(heading) * 80), 8);
            }
            Nav.Status st = bot.nav.goTo(explore);
            if (st == Nav.Status.FAILED) heading += Math.PI / 2 + 0.4;
            if (st != Nav.Status.RUNNING) explore = null;
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
            if (dist > 2.0) moveTo(target.blockPosition(), 1.5);
            return false;
        }
        moveTo(target.blockPosition(), 1.5);
        return false;
    }

    private void moveTo(BlockPos pos, double r) {
        if (goal == null || goalPos == null || goalPos.distSqr(pos) > 2.5) {
            goalPos = pos;
            goal = Goal.near(pos, r);
        }
        Nav.Status st = bot.nav.goTo(goal);
        if (st == Nav.Status.FAILED) { target = null; goal = null; }
    }

    private LivingEntity pick() {
        LivingEntity best = null;
        double bestD = 64 * 64;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || le.isBaby() || !prey.test(e)) continue;
            if (bot.memory.dangerous(e.blockPosition())) continue;
            double d = e.distanceToSqr(bot.player());
            if (d < bestD) { bestD = d; best = le; }
        }
        return best;
    }
}
