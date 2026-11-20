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
    /** Set by a task while the bot is walled in: nothing can reach it, so eating is safe. */
    public boolean sheltered;
    /** Hostiles inside this range (with line of sight) are fought before anything else. */
    public double guardRange = 4.5;
    private int engagedId = -1, engagedTicks, ignoredId = -1, ignoreTicks;

    /** True if walking one step toward the entity keeps the bot on solid ground. */
    private boolean safeAhead(LivingEntity e) {
        LocalPlayer p = bot.player();
        double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return false;
        net.minecraft.core.BlockPos ahead = net.minecraft.core.BlockPos.containing(p.getX() + dx / len, p.getY() + 0.2, p.getZ() + dz / len);
        var level = bot.level();
        int floor = Blocks2.flags(level, ahead.below(), level.getBlockState(ahead.below()));
        int body = Blocks2.flags(level, ahead, level.getBlockState(ahead));
        return (floor & Blocks2.STAND) != 0 && (body & Blocks2.PASS) != 0;
    }

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

    private net.minecraft.core.BlockPos shelterAt;
    private int shelterTicks, shelterCooldown;
    private boolean shelterNight;
    private net.minecraft.core.BlockPos waterLanding;
    private int waterLandingTicks;

    private java.util.List<net.minecraft.core.BlockPos> shelterCells(net.minecraft.core.BlockPos f) {
        java.util.List<net.minecraft.core.BlockPos> cells = new java.util.ArrayList<>();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            cells.add(f.relative(d));
            cells.add(f.above().relative(d));
        }
        cells.add(f.above(2));
        return cells;
    }

    /** Emergency shelter: surround the bot with blocks until it has recovered. */
    private boolean shelterTick() {
        LocalPlayer p = bot.player();
        if (shelterCooldown > 0) shelterCooldown--;
        var level = bot.level();
        boolean overworld = level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
        // Unarmoured on the surface at night is how runs die; sit it out in a hut.
        boolean nightRisk = overworld && level.isDarkOutside() && p.getArmorValue() < 8 && level.canSeeSky(bot.feet().above());
        if (shelterAt == null) {
            if (shelterCooldown > 0 || !p.onGround() || p.isInWater() || level.dimension() == net.minecraft.world.level.Level.END
                    || bot.inv.countThrowaway() < 12 || !bot.inv.menuIsInventory()) return false;
            double limit = overworld ? 9 : 13;
            boolean hurt = p.getHealth() <= limit && (nearestHostile(24) != null || p.isOnFire());
            if (!hurt && !nightRisk) return false;
            shelterNight = !hurt;
            shelterAt = bot.feet();
            shelterTicks = 0;
            release();
            bot.say(hurt ? "Taking cover to heal." : "Night is falling and I have no armour: waiting it out in a shelter.");
        }
        if (shelterNight) {
            bot.setStatus("sheltering until morning");
            if (nightRisk || level.isDarkOutside()) shelterTicks = Math.min(shelterTicks, 100);
        }
        shelterTicks++;
        bot.heartbeat = bot.tick;
        bot.act.stopBreaking();
        bot.nav.reset();
        boolean healed = (p.getHealth() >= 17 || (p.getHealth() >= 12 && bot.inv.find(Reflex::isGoodFood) < 0))
                && !(shelterNight && level.isDarkOutside());
        if (healed || shelterTicks > 900 || !bot.feet().equals(shelterAt) && shelterTicks > 40) {
            shelterAt = null;
            shelterCooldown = 200;
            release();
            return false;
        }
        // Centre on the block so the walls fit around the hitbox.
        double cx = shelterAt.getX() + 0.5, cz = shelterAt.getZ() + 0.5;
        if (Math.abs(p.getX() - cx) > 0.18 || Math.abs(p.getZ() - cz) > 0.18) {
            bot.act.faceXZ(cx, cz);
            bot.input.forward = true;
            bot.input.sneak = true;
            return true;
        }
        for (net.minecraft.core.BlockPos c : shelterCells(shelterAt)) {
            if (bot.level().getBlockState(c).canBeReplaced()) {
                if (eatTicks > 0) release();
                if (shelterTicks > 160 && shelterTicks < 170) break; // something is in the way; make do
                bot.act.fastPlace = true;
                bot.act.placeBlock(c);
                bot.act.fastPlace = false;
                return true;
            }
        }
        // Sealed in (or as good as it gets): eat and wait.
        if (eatTicks > 0) {
            eatTicks++;
            if ((!p.isUsingItem() && eatTicks > 8) || eatTicks > 60) release();
            return true;
        }
        if (p.getFoodData().getFoodLevel() < 20) {
            int slot = bot.inv.find(Reflex::isGoodFood);
            if (slot >= 0 && bot.inv.hold(slot)) {
                p.setXRot(-30f);
                mc.options.keyUse.setDown(true);
                useHeld = true;
                eatTicks = 1;
            }
        }
        return true;
    }

    private boolean hasShield() { return bot.player().getOffhandItem().is(Items.SHIELD); }

    private void raiseShield() {
        if (hasShield() && !useHeld) { mc.options.keyUse.setDown(true); useHeld = true; }
    }

    /** Returns true if the reflex needs the whole tick (tasks should not run). */
    public boolean tick() {
        LocalPlayer p = bot.player();
        if (ignoreTicks > 0 && --ignoreTicks == 0) ignoredId = -1;

        // Long fall: break it with the water bucket.
        if (!p.onGround() && p.fallDistance > 4 && !p.isInWater() && bot.inv.menuIsInventory()
                && bot.level().dimension() != net.minecraft.world.level.Level.NETHER && bot.inv.count(Items.WATER_BUCKET) > 0) {
            release();
            bot.act.stopBreaking();
            p.setXRot(90f);
            if (bot.inv.hold(Items.WATER_BUCKET)) {
                net.minecraft.world.phys.Vec3 eye = p.getEyePosition();
                var hit = bot.level().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(0, -4.4, 0),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY, p));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    mc.gameMode.useItem(p, net.minecraft.world.InteractionHand.MAIN_HAND);
                    waterLanding = bot.feet();
                    waterLandingTicks = 0;
                }
            }
            return true;
        }
        // ...and take the water back afterwards.
        if (waterLanding != null) {
            if (++waterLandingTicks > 100 || bot.inv.count(Items.BUCKET) == 0) waterLanding = null;
            else if (p.onGround() || p.isInWater()) {
                net.minecraft.core.BlockPos src = null;
                for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(bot.feet().offset(-2, -2, -2), bot.feet().offset(2, 1, 2))) {
                    var fs = bot.level().getFluidState(q);
                    if (fs.isSource() && fs.is(net.minecraft.tags.FluidTags.WATER)) { src = q.immutable(); break; }
                }
                if (src == null) { if (waterLandingTicks > 20) waterLanding = null; }
                else if (bot.inv.hold(Items.BUCKET)) {
                    bot.act.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(src));
                    bot.act.useItem();
                }
                return true;
            }
        }

        // In lava: swim for the nearest bank.
        if (p.isInLava()) {
            release();
            bot.act.stopBreaking();
            bot.nav.reset();
            bot.input.jump = true;
            net.minecraft.core.BlockPos f = bot.feet(), best = null;
            double bestD = Double.MAX_VALUE;
            for (net.minecraft.core.BlockPos q : net.minecraft.core.BlockPos.betweenClosed(f.offset(-6, -1, -6), f.offset(6, 3, 6))) {
                var level = bot.level();
                if ((Blocks2.flags(level, q, level.getBlockState(q)) & Blocks2.STAND) == 0) continue;
                if ((Blocks2.flags(level, q.above(), level.getBlockState(q.above())) & Blocks2.PASS) == 0) continue;
                if ((Blocks2.flags(level, q.above(2), level.getBlockState(q.above(2))) & Blocks2.PASS) == 0) continue;
                double d = q.distSqr(f) + Math.max(0, q.getY() - f.getY()) * 4;
                if (d < bestD) { bestD = d; best = q.immutable(); }
            }
            if (best != null) {
                bot.act.faceXZ(best.getX() + 0.5, best.getZ() + 0.5);
                bot.input.forward = true;
            }
            return true;
        }

        // Creepers about to blow: hide behind the shield, or run if there is none.
        for (Entity e : bot.level().entitiesForRendering()) {
            if (e instanceof Creeper c && c.isAlive() && c.getSwellDir() > 0 && c.distanceToSqr(p) < 6 * 6) {
                bot.act.stopBreaking();
                if (hasShield() && eatTicks == 0) {
                    bot.act.lookAt(c.getBoundingBox().getCenter());
                    raiseShield();
                    return true;
                }
                release();
                double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
                bot.act.faceXZ(p.getX() + dx * 4, p.getZ() + dz * 4);
                bot.input.forward = true;
                bot.input.sprint = true;
                if (p.horizontalCollision && p.onGround()) bot.input.jump = true;
                return true;
            }
        }

        // Badly hurt and under attack: wall in, eat, heal.
        if (shelterTick()) return true;

        // Stand and fight anything hostile that gets close.
        LivingEntity threat = combatEnabled ? nearestHostile(guardRange) : null;
        if (threat != null && (threat.getId() == ignoredId || !p.hasLineOfSight(threat))) threat = null;
        if (threat != null && bot.inv.menuIsInventory()) {
            if (eatTicks > 0) release();
            if (threat.getId() != engagedId) { engagedId = threat.getId(); engagedTicks = 0; }
            if (threat.hurtTime > 0) engagedTicks = 0;
            if (++engagedTicks > 240) { ignoredId = threat.getId(); ignoreTicks = 300; release(); return false; }
            bot.act.stopBreaking();
            bot.nav.reset();
            bot.inv.holdWeapon();
            bot.act.lookAt(threat.getBoundingBox().getCenter());
            raiseShield();
            double dist = Math.sqrt(threat.getBoundingBox().distanceToSqr(p.getEyePosition()));
            if (dist < 3.0 && bot.act.attackReady()) bot.act.attack(threat);
            else if (dist > 2.4 && p.onGround() && safeAhead(threat)) bot.input.forward = true;
            if (p.isInWater()) bot.input.jump = true;
            return true;
        }
        engagedId = -1;
        if (useHeld && eatTicks == 0) release();

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
        if (hungry && threat == null && (sheltered || nearestHostile(6.0) == null) && bot.inv.menuIsInventory() && !p.isInWater()) {
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
