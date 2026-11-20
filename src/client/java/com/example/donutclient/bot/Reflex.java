package com.example.donutclient.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Always-on survival behaviour: fight back and eat. */
public final class Reflex {
    private final Bot bot;
    private final Minecraft mc = Minecraft.getInstance();
    private int eatTicks;
    private boolean useHeld;
    /** Tasks can switch the melee reflex off (e.g. while deliberately baiting). */
    public boolean combatEnabled = true;

    Reflex(Bot bot) { this.bot = bot; }

    public void release() {
        if (useHeld) { mc.options.keyUse.setDown(false); useHeld = false; }
        eatTicks = 0;
    }

    public static boolean isHostile(Entity e) {
        if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || !le.isAlive()) return false;
        if (e instanceof ZombifiedPiglin || e instanceof EnderDragon) return false;
        if (e instanceof EnderMan em) return em.isCreepy();
        return true;
    }

    public LivingEntity nearestHostile(double range) {
        LocalPlayer p = bot.player();
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : bot.level().entitiesForRendering()) {
            if (!isHostile(e)) continue;
            double d = e.getBoundingBox().distanceToSqr(p.getEyePosition());
            if (d < bestD) { bestD = d; best = (LivingEntity) e; }
        }
        return best;
    }

    /** Returns true if the reflex needs the whole tick (tasks should not run). */
    public boolean tick() {
        LocalPlayer p = bot.player();
        LivingEntity threat = combatEnabled ? nearestHostile(3.0) : null;
        if (threat != null && bot.act.attackReady() && bot.inv.menuIsInventory()) {
            release();
            bot.inv.holdWeapon();
            bot.act.attack(threat);
        }

        // Creepers about to blow: get out of the blast.
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof Creeper c && c.isAlive() && c.getSwellDir() > 0 && c.distanceToSqr(p) < 6 * 6) {
                release();
                bot.act.stopBreaking();
                double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
                bot.act.faceXZ(p.getX() + dx * 4, p.getZ() + dz * 4);
                bot.input.forward = true;
                bot.input.sprint = true;
                if (p.horizontalCollision && p.onGround()) bot.input.jump = true;
                return true;
            }
        }

        // Running out of breath: surface.
        if (p.isUnderWater() && p.getAirSupply() < 140) {
            release();
            bot.act.stopBreaking();
            bot.input.jump = true;
            return true;
        }

        // Eating.
        int food = p.getFoodData().getFoodLevel();
        boolean hungry = food <= 12 || (p.getHealth() <= 12 && food < 20);
        if (eatTicks > 0) {
            eatTicks++;
            boolean finished = !p.isUsingItem() && eatTicks > 8;
            if (finished || eatTicks > 60 || threat != null) { release(); return false; }
            return true;
        }
        if (hungry && threat == null && nearestHostile(6.0) == null && bot.inv.menuIsInventory() && !p.isInWater()) {
            int slot = bot.inv.find(Reflex::isGoodFood);
            if (slot >= 0 && bot.inv.hold(slot)) {
                bot.act.stopBreaking();
                p.setXRot(-30f);
                mc.options.keyUse.setDown(true);
                useHeld = true;
                eatTicks = 1;
                return true;
            }
        }
        return false;
    }

    public static boolean isGoodFood(ItemStack s) {
        if (!s.has(DataComponents.FOOD)) return false;
        var i = s.getItem();
        return i != Items.ROTTEN_FLESH && i != Items.SPIDER_EYE && i != Items.PUFFERFISH && i != Items.POISONOUS_POTATO
                && i != Items.CHORUS_FRUIT && i != Items.GOLDEN_APPLE && i != Items.ENCHANTED_GOLDEN_APPLE
                && i != Items.SUSPICIOUS_STEW && i != Items.CHICKEN;
    }
}
