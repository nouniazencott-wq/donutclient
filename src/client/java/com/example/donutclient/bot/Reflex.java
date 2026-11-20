package com.example.donutclient.bot;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Survival reflexes that run before the current task every tick and may take over the controls:
 * water-bucket landings, escaping lava, creepers, emergency/night shelters, melee self-defence,
 * surfacing for air and eating.
 */
public final class Reflex {
    private final Bot bot;
    private final Minecraft mc = Minecraft.getInstance();
    /** 0 when not eating, otherwise the number of ticks the use key has been held to eat. */
    private int eatTicks;
    /** True while this class is holding the use key down (eating or blocking with the shield). */
    private boolean useHeld;
    /** Tasks may turn melee self-defence off. */
    public boolean combatEnabled = true;
    /** Set by tasks when the bot is walled in, so it may eat even with hostiles close by. */
    public boolean sheltered;
    /** Hostiles within this distance are fought. */
    public double guardRange = 4.5;
    private int engagedId = -1;
    private int engagedTicks;
    /** Entity id of a hostile the bot gave up on, ignored while {@link #ignoreTicks} runs. */
    private int ignoredId = -1;
    private int ignoreTicks;

    /** True if the block one step towards the target can be stood on, so charging will not walk off a ledge. */
    private boolean safeAhead(LivingEntity target) {
        LocalPlayer player = bot.player();
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            return false;
        }
        BlockPos ahead = BlockPos.containing(player.getX() + dx / dist, player.getY() + 0.2, player.getZ() + dz / dist);
        ClientLevel level = bot.level();
        int floor = Blocks2.flags(level, ahead.below(), level.getBlockState(ahead.below()));
        int body = Blocks2.flags(level, ahead, level.getBlockState(ahead));
        // Blocks2 flag bit 2 = can be stood on, bit 1 = can be walked through.
        return (floor & 2) != 0 && (body & 1) != 0;
    }

    Reflex(Bot bot) {
        this.bot = bot;
    }

    /** Lets go of the use key (stops eating / lowers the shield). */
    public void release() {
        if (useHeld) {
            mc.options.keyUse.setDown(false);
            useHeld = false;
        }
        eatTicks = 0;
    }

    /** Living monsters worth fighting: not zombified piglins or the dragon, endermen only once provoked. */
    public static boolean isHostile(Entity entity) {
        if (!(entity instanceof Enemy) || !(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (entity instanceof ZombifiedPiglin || entity instanceof EnderDragon) {
            return false;
        }
        if (entity instanceof EnderMan enderman) {
            return enderman.isCreepy();
        }
        return true;
    }

    /** Closest hostile whose hitbox is within {@code range} of the player's eyes, or null. */
    public LivingEntity nearestHostile(double range) {
        LocalPlayer player = bot.player();
        LivingEntity nearest = null;
        double bestSq = range * range;
        for (Entity entity : bot.level().entitiesForRendering()) {
            if (!isHostile(entity)) {
                continue;
            }
            double distSq = entity.getBoundingBox().distanceToSqr(player.getEyePosition());
            if (distSq < bestSq) {
                bestSq = distSq;
                nearest = (LivingEntity) entity;
            }
        }
        return nearest;
    }

    /** Where the bot is sheltering (its feet position when it started), or null. */
    private BlockPos shelterAt;
    private int shelterTicks;
    /** Ticks since the current shelter was started; unlike {@link #shelterTicks} never held back at night. */
    private int shelterTotal;
    private int shelterCooldown;
    /** True if the shelter was built to wait out the night rather than to heal. */
    private boolean shelterNight;

    /** The nine cells that enclose a player standing at {@code feet}: four walls two high, plus the roof. */
    private List<BlockPos> shelterCells(BlockPos feet) {
        List<BlockPos> cells = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            cells.add(feet.relative(side));
            cells.add(feet.above().relative(side));
        }
        cells.add(feet.above(2));
        return cells;
    }

    /**
     * Boxes the bot in when badly hurt with monsters around (or burning), or at nightfall in the overworld with
     * little armour, then eats and waits inside until healed / morning.
     *
     * @return true while the shelter reflex owns the controls
     */
    private boolean shelterTick() {
        LocalPlayer player = bot.player();
        if (shelterCooldown > 0) {
            shelterCooldown--;
        }
        ClientLevel level = bot.level();
        boolean overworld = level.dimension() == Level.OVERWORLD;
        boolean nightRisk = overworld && level.isDarkOutside() && player.getArmorValue() < 8
                && level.canSeeSky(bot.feet().above());
        if (shelterAt == null) {
            if (shelterCooldown > 0 || !player.onGround() || player.isInWater() || level.dimension() == Level.END
                    || bot.inv.countThrowaway() < 12 || !bot.inv.menuIsInventory()) {
                return false;
            }
            double limit = overworld ? 9.0 : 13.0;
            // Only hide to heal when healing is possible at all (food to eat or a full enough food bar);
            // otherwise the bot would box itself in again and again without ever recovering.
            boolean hurt = player.getHealth() <= limit && (nearestHostile(24.0) != null || player.isOnFire())
                    && canHeal();
            // Do not start a whole night of waiting while starving: nothing in the shelter would fix that.
            if (!hurt && (!nightRisk || starving())) {
                return false;
            }
            shelterNight = !hurt;
            shelterAt = bot.feet();
            shelterTicks = 0;
            shelterTotal = 0;
            release();
            bot.say(hurt ? "Taking cover to heal."
                    : "Night is falling and I have no armour: waiting it out in a shelter.");
        }
        if (shelterNight) {
            bot.setStatus("sheltering until morning");
            // While it is still dark keep the timer below the give-up limit.
            if (nightRisk || level.isDarkOutside()) {
                shelterTicks = Math.min(shelterTicks, 100);
            }
        }
        shelterTicks++;
        shelterTotal++;
        bot.heartbeat = bot.tick;
        bot.act.stopBreaking();
        bot.nav.reset();
        boolean healed = (player.getHealth() >= 17.0F
                || (player.getHealth() >= 12.0F && bot.inv.find(Reflex::isGoodFood) < 0))
                && (!shelterNight || !level.isDarkOutside());
        if (healed || shelterTicks > 900 || (!bot.feet().equals(shelterAt) && shelterTicks > 40)) {
            shelterAt = null;
            shelterCooldown = 200;
            release();
            return false;
        }
        // Waiting cannot go on for ever: leave when it can no longer help (nothing left to eat while hurt and
        // hungry, starving in a night shelter) or when a "night" lasts far longer than a real one (thunderstorm).
        // The long cooldown keeps the bot from walling itself straight back in.
        boolean pointless = shelterNight ? starving() : (shelterTicks > 200 && !canHeal());
        if (pointless || shelterTotal > 13000) {
            shelterAt = null;
            shelterCooldown = 2400;
            release();
            return false;
        }
        // Stand in the middle of the cell so the walls can be placed around the bot.
        double cx = shelterAt.getX() + 0.5;
        double cz = shelterAt.getZ() + 0.5;
        if (Math.abs(player.getX() - cx) > 0.18 || Math.abs(player.getZ() - cz) > 0.18) {
            bot.act.faceXZ(cx, cz);
            bot.input.forward = true;
            bot.input.sneak = true;
            return true;
        }
        for (BlockPos cell : shelterCells(shelterAt)) {
            if (bot.level().getBlockState(cell).canBeReplaced()) {
                if (eatTicks > 0) {
                    release();
                }
                // A shelter that cannot be closed (out of blocks, or a night shelter still open after 15 seconds)
                // protects nothing: give it up instead of standing in the open until morning.
                if (bot.inv.countThrowaway() == 0 || (shelterNight && shelterTotal > 300)) {
                    shelterAt = null;
                    shelterCooldown = 1200;
                    return false;
                }
                // Short pause in the building so eating gets a chance even if a cell cannot be closed.
                if (shelterTicks > 160 && shelterTicks < 170) {
                    break;
                }
                bot.act.fastPlace = true;
                bot.act.placeBlock(cell);
                bot.act.fastPlace = false;
                return true;
            }
        }
        if (eatTicks > 0) {
            eatTicks++;
            if ((!player.isUsingItem() && eatTicks > 8) || eatTicks > 60) {
                release();
            }
            return true;
        }
        // A golden apple when close to death (works with a full food bar too), otherwise normal food when hungry.
        int slot = emergencyAppleSlot();
        if (slot < 0 && player.getFoodData().getFoodLevel() < 20) {
            slot = bestFoodSlot();
        }
        if (slot >= 0 && bot.inv.hold(slot)) {
            player.setXRot(-30.0F);
            mc.options.keyUse.setDown(true);
            useHeld = true;
            eatTicks = 1;
        }
        return true;
    }

    /** True if resting can restore health: the food bar is full enough to regenerate, or there is something to eat. */
    private boolean canHeal() {
        return bot.player().getFoodData().getFoodLevel() >= 18 || bot.inv.find(Reflex::isGoodFood) >= 0
                || emergencyAppleSlot() >= 0;
    }

    /** True if the food bar is nearly empty and the bot carries nothing it would eat. */
    private boolean starving() {
        return bot.player().getFoodData().getFoodLevel() <= 6 && bot.inv.find(Reflex::isGoodFood) < 0;
    }

    /**
     * Inventory slot of a golden apple to eat right now, or -1. Only when health is at four hearts or less and the
     * regeneration of a previous apple has worn off, so they are not wasted.
     */
    private int emergencyAppleSlot() {
        LocalPlayer player = bot.player();
        if (player.getHealth() > 8.0F || player.hasEffect(MobEffects.REGENERATION)) {
            return -1;
        }
        int slot = bot.inv.find(s -> s.is(Items.GOLDEN_APPLE));
        return slot >= 0 ? slot : bot.inv.find(s -> s.is(Items.ENCHANTED_GOLDEN_APPLE));
    }

    /**
     * Inventory slot of the food to eat now, or -1 if there is none: the most filling food that does not overfill
     * the food bar (so cooked meat is not wasted on a small gap, and is preferred over raw when hungry), or failing
     * that the least filling one.
     */
    private int bestFoodSlot() {
        int missing = 20 - bot.player().getFoodData().getFoodLevel();
        int fitSlot = -1;
        int fitNutrition = -1;
        int smallSlot = -1;
        int smallNutrition = Integer.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = bot.inv.get(i);
            if (stack.isEmpty() || !isGoodFood(stack)) {
                continue;
            }
            FoodProperties props = stack.get(DataComponents.FOOD);
            int nutrition = props == null ? 0 : props.nutrition();
            if (nutrition <= missing && nutrition > fitNutrition) {
                fitNutrition = nutrition;
                fitSlot = i;
            }
            if (nutrition < smallNutrition) {
                smallNutrition = nutrition;
                smallSlot = i;
            }
        }
        return fitSlot >= 0 ? fitSlot : smallSlot;
    }

    /**
     * True if running two blocks in the given horizontal direction (unit vector) is harmless: no lava, fire or
     * other damaging block in the way or below, and no drop of more than three blocks.
     */
    private boolean fleeSafe(double dirX, double dirZ) {
        LocalPlayer player = bot.player();
        ClientLevel level = bot.level();
        for (int step = 1; step <= 2; step++) {
            BlockPos cell = BlockPos.containing(player.getX() + dirX * step, player.getY() + 0.2,
                    player.getZ() + dirZ * step);
            int body = Blocks2.flags(level, cell, level.getBlockState(cell));
            if ((body & (Blocks2.DANGER | Blocks2.LAVA)) != 0) {
                return false;
            }
            if ((body & (Blocks2.PASS | Blocks2.WATER)) == 0) {
                // A wall or step: the bot stops or jumps there, it cannot fall.
                return true;
            }
            boolean floor = false;
            for (int down = 1; down <= 3 && !floor; down++) {
                BlockPos below = cell.below(down);
                int f = Blocks2.flags(level, below, level.getBlockState(below));
                if ((f & (Blocks2.DANGER | Blocks2.LAVA)) != 0) {
                    return false;
                }
                floor = (f & Blocks2.PASS) == 0;
            }
            if (!floor) {
                return false;
            }
        }
        return true;
    }

    private boolean hasShield() {
        return bot.player().getOffhandItem().is(Items.SHIELD);
    }

    private void raiseShield() {
        if (hasShield() && !useHeld) {
            mc.options.keyUse.setDown(true);
            useHeld = true;
        }
    }

    /** Where a water bucket was emptied to break a fall; the water is scooped back up after landing. */
    private BlockPos waterLanding;
    private int waterLandingTicks;

    /**
     * Runs all reflexes in priority order.
     *
     * @return true if a reflex took the controls this tick and the current task must not act
     */
    public boolean tick() {
        LocalPlayer player = bot.player();
        if (ignoreTicks > 0 && --ignoreTicks == 0) {
            ignoredId = -1;
        }

        // Falling far: look straight down and place water just before landing (water evaporates in the nether).
        if (!player.onGround() && player.fallDistance > 4.0 && !player.isInWater() && bot.inv.menuIsInventory()
                && bot.level().dimension() != Level.NETHER && bot.inv.count(Items.WATER_BUCKET) > 0) {
            release();
            bot.act.stopBreaking();
            player.setXRot(90.0F);
            if (bot.inv.hold(Items.WATER_BUCKET)) {
                Vec3 eye = player.getEyePosition();
                BlockHitResult hit = bot.level().clip(new ClipContext(eye, eye.add(0.0, -4.4, 0.0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                    waterLanding = bot.feet();
                    waterLandingTicks = 0;
                }
            }
            return true;
        }

        // After a water landing: pick the water source back up with the empty bucket.
        if (waterLanding != null) {
            // Never while in lava: scooping water must not keep the lava escape below from running.
            if (++waterLandingTicks > 100 || bot.inv.count(Items.BUCKET) == 0 || player.isInLava()) {
                waterLanding = null;
            } else if (player.onGround() || player.isInWater()) {
                BlockPos src = null;
                for (BlockPos pos : BlockPos.betweenClosed(bot.feet().offset(-2, -2, -2), bot.feet().offset(2, 1, 2))) {
                    FluidState fluid = bot.level().getFluidState(pos);
                    if (fluid.isSource() && fluid.is(FluidTags.WATER)) {
                        src = pos.immutable();
                        break;
                    }
                }
                if (src == null) {
                    if (waterLandingTicks > 20) {
                        waterLanding = null;
                    }
                } else if (bot.inv.hold(Items.BUCKET)) {
                    bot.act.lookAt(Vec3.atCenterOf(src));
                    bot.act.useItem();
                }
                return true;
            }
        }

        // In lava: swim up and head for the nearest standable spot with two free blocks above it.
        if (player.isInLava()) {
            release();
            bot.act.stopBreaking();
            bot.nav.reset();
            bot.input.jump = true;
            BlockPos feet = bot.feet();
            BlockPos best = null;
            double bestCost = Double.MAX_VALUE;
            for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-6, -1, -6), feet.offset(6, 3, 6))) {
                ClientLevel level = bot.level();
                if ((Blocks2.flags(level, pos, level.getBlockState(pos)) & 2) == 0) {
                    continue;
                }
                if ((Blocks2.flags(level, pos.above(), level.getBlockState(pos.above())) & 1) == 0) {
                    continue;
                }
                if ((Blocks2.flags(level, pos.above(2), level.getBlockState(pos.above(2))) & 1) == 0) {
                    continue;
                }
                // Climbing out is harder than moving sideways.
                double cost = pos.distSqr(feet) + Math.max(0, pos.getY() - feet.getY()) * 4;
                if (cost < bestCost) {
                    bestCost = cost;
                    best = pos.immutable();
                }
            }
            if (best != null) {
                bot.act.faceXZ(best.getX() + 0.5, best.getZ() + 0.5);
                bot.input.forward = true;
            }
            // Empty the water bucket at the bot's feet: it puts out the fire and turns the lava around it to stone.
            // Only the head pitch changes, so the bot keeps moving towards the spot chosen above.
            // (Water evaporates in the nether.)
            if (bot.level().dimension() != Level.NETHER && bot.inv.menuIsInventory()
                    && bot.inv.count(Items.WATER_BUCKET) > 0 && bot.inv.hold(Items.WATER_BUCKET)) {
                player.setXRot(90.0F);
                bot.act.useItem();
            }
            return true;
        }

        // A creeper is about to blow: block with the shield, or sprint directly away from it.
        for (Entity entity : bot.level().entitiesForRendering()) {
            if (entity instanceof Creeper creeper && creeper.isAlive() && creeper.getSwellDir() > 0
                    && creeper.distanceToSqr(player) < 36.0) {
                bot.act.stopBreaking();
                if (hasShield() && eatTicks == 0) {
                    bot.act.lookAt(creeper.getBoundingBox().getCenter());
                    raiseShield();
                    return true;
                }
                release();
                double awayX = player.getX() - creeper.getX();
                double awayZ = player.getZ() - creeper.getZ();
                double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
                if (awayLen < 0.01) {
                    awayX = 1.0;
                    awayZ = 0.0;
                } else {
                    awayX /= awayLen;
                    awayZ /= awayLen;
                }
                // Do not run off a cliff or into lava: if straight away is unsafe try 45 and 90 degrees to either
                // side. With no safe direction at all the bot still runs straight away, as the blast is certain.
                double[] turns = {0.0, Math.PI / 4, -Math.PI / 4, Math.PI / 2, -Math.PI / 2};
                for (double turn : turns) {
                    double dirX = awayX * Math.cos(turn) - awayZ * Math.sin(turn);
                    double dirZ = awayX * Math.sin(turn) + awayZ * Math.cos(turn);
                    if (fleeSafe(dirX, dirZ)) {
                        awayX = dirX;
                        awayZ = dirZ;
                        break;
                    }
                }
                bot.act.faceXZ(player.getX() + awayX * 4.0, player.getZ() + awayZ * 4.0);
                bot.input.forward = true;
                bot.input.sprint = true;
                if (player.horizontalCollision && player.onGround()) {
                    bot.input.jump = true;
                }
                return true;
            }
        }

        if (shelterTick()) {
            return true;
        }

        // Melee self-defence against the nearest visible hostile.
        LivingEntity threat = combatEnabled ? nearestHostile(guardRange) : null;
        if (threat != null && (threat.getId() == ignoredId || !player.hasLineOfSight(threat))) {
            threat = null;
        }
        if (threat != null && bot.inv.menuIsInventory()) {
            if (eatTicks > 0) {
                release();
            }
            if (threat.getId() != engagedId) {
                engagedId = threat.getId();
                engagedTicks = 0;
            }
            if (threat.hurtTime > 0) {
                engagedTicks = 0;
            }
            // 12 seconds without landing a hit: it is out of reach, ignore it for a while.
            if (++engagedTicks > 240) {
                ignoredId = threat.getId();
                ignoreTicks = 300;
                release();
                return false;
            }
            bot.act.stopBreaking();
            bot.nav.reset();
            bot.inv.holdWeapon();
            bot.act.lookAt(threat.getBoundingBox().getCenter());
            double dist = Math.sqrt(threat.getBoundingBox().distanceToSqr(player.getEyePosition()));
            if (dist < 3.0 && bot.act.attackReady()) {
                // Lower the shield for the hit: a player holding the use key cannot attack, so a permanently
                // raised shield meant the bot never fought back. It goes up again on the next tick while the
                // attack recharges.
                release();
                if (player.isUsingItem()) {
                    mc.gameMode.releaseUsingItem(player);
                }
                bot.act.attack(threat);
            } else {
                raiseShield();
                if (dist > 2.4 && player.onGround() && safeAhead(threat)) {
                    bot.input.forward = true;
                }
            }
            if (player.isInWater()) {
                bot.input.jump = true;
            }
            return true;
        }
        engagedId = -1;
        // Lower the shield once the fight is over.
        if (useHeld && eatTicks == 0) {
            release();
        }

        // Running out of air: swim up.
        if (player.isUnderWater() && player.getAirSupply() < 140) {
            release();
            bot.act.stopBreaking();
            bot.input.jump = true;
            return true;
        }

        // Eating.
        int food = player.getFoodData().getFoodLevel();
        // Health only regenerates with the food bar at 18 or more, so when hurt top it up early.
        boolean hungry = food <= 12 || (player.getHealth() <= 16.0F && food < 18)
                || (player.getHealth() <= 12.0F && food < 20);
        // Close to death with a golden apple in the bag: eat it whatever the food bar says.
        int appleSlot = eatTicks == 0 && threat == null ? emergencyAppleSlot() : -1;
        // Nearly starving, or about to die: do not wait for monsters that merely lurk nearby (behind a wall, out of
        // reach) to go away - anything the bot can actually fight is a threat and is handled above.
        boolean urgent = food <= 6 || appleSlot >= 0;
        if (eatTicks > 0) {
            eatTicks++;
            boolean finished = !player.isUsingItem() && eatTicks > 8;
            if (finished || eatTicks > 60 || threat != null) {
                release();
                return false;
            }
            return true;
        }
        if ((hungry || appleSlot >= 0) && threat == null && (sheltered || urgent || nearestHostile(6.0) == null)
                && bot.inv.menuIsInventory() && !player.isInWater()) {
            int slot = appleSlot >= 0 ? appleSlot : bestFoodSlot();
            if (slot >= 0 && bot.inv.hold(slot)) {
                bot.act.stopBreaking();
                player.setXRot(-30.0F);
                mc.options.keyUse.setDown(true);
                useHeld = true;
                eatTicks = 1;
                return true;
            }
        }
        return false;
    }

    /** Food the bot will eat: no harmful or teleporting food, nothing too valuable, and not raw chicken. */
    public static boolean isGoodFood(ItemStack stack) {
        if (!stack.has(DataComponents.FOOD)) {
            return false;
        }
        Item item = stack.getItem();
        return item != Items.ROTTEN_FLESH && item != Items.SPIDER_EYE && item != Items.PUFFERFISH
                && item != Items.POISONOUS_POTATO && item != Items.CHORUS_FRUIT && item != Items.GOLDEN_APPLE
                && item != Items.ENCHANTED_GOLDEN_APPLE && item != Items.SUSPICIOUS_STEW && item != Items.CHICKEN;
    }
}
