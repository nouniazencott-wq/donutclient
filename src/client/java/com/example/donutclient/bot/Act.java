package com.example.donutclient.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * World interactions of the bot: looking, breaking and placing blocks, using items and attacking.
 * Everything goes through the normal client game mode, exactly as a player's clicks would.
 */
public final class Act {
    private final Bot bot;
    private final Minecraft mc = Minecraft.getInstance();
    private long lastPlaceTick = -100L;
    private long lastUseTick = -100L;
    /** True while a block-breaking action is in progress on the client. */
    public boolean breaking;
    /** Set whenever {@link #breakBlock} is called; the owner clears it to detect that breaking stopped being asked. */
    public boolean breakingRequested;
    /** Shortens the delay between block placements from 4 ticks to 2. */
    public boolean fastPlace;

    Act(Bot bot) {
        this.bot = bot;
    }

    private LocalPlayer p() {
        return mc.player;
    }

    /** Turns the player's head so it looks at the given point. */
    public void lookAt(Vec3 target) {
        Vec3 eye = p().getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 1.0E-4 || Math.abs(dy) > 1.0E-4) {
            if (horizontal > 1.0E-4) {
                p().setYRot((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
            }
            p().setXRot(Mth.clamp((float) (-(Mth.atan2(dy, horizontal) * (180.0 / Math.PI))), -90.0F, 90.0F));
        }
    }

    public void lookAt(BlockPos pos) {
        lookAt(Vec3.atCenterOf(pos));
    }

    /** Faces a horizontal position, with the head tilted slightly down (walking posture). */
    public void faceXZ(double x, double z) {
        double dx = x - p().getX();
        double dz = z - p().getZ();
        if (dx * dx + dz * dz > 1.0E-6) {
            p().setYRot((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
        }
        p().setXRot(10.0F);
    }

    /** Distance from the player's eyes to the centre of the block. */
    public double eyeDist(BlockPos pos) {
        return Math.sqrt(p().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
    }

    public boolean inReach(BlockPos pos) {
        return eyeDist(pos) <= 4.4;
    }

    /**
     * Continues breaking the block (call every tick), picking the best tool first.
     *
     * @return true once the position is air or liquid, i.e. there is nothing left to break
     */
    public boolean breakBlock(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (state.liquid()) {
            return true;
        }
        // Bedrock, portal frames and the like can never be broken: do not switch tools and swing at them for
        // nothing; the caller's own timeout deals with the block staying there.
        if (state.getDestroySpeed(mc.level, pos) < 0.0F) {
            stopBreaking();
            breakingRequested = true;
            return false;
        }
        bot.inv.holdBestTool(state);
        lookAt(pos);
        breaking = true;
        breakingRequested = true;
        // Hit the face that points towards the player.
        Direction face = Direction.getApproximateNearest(p().getEyePosition().subtract(Vec3.atCenterOf(pos)));
        mc.gameMode.continueDestroyBlock(pos, face);
        p().swing(InteractionHand.MAIN_HAND);
        return false;
    }

    public void stopBreaking() {
        if (breaking) {
            breaking = false;
            mc.gameMode.stopDestroyBlock();
        }
    }

    /** Places a throwaway block at the position. See {@link #placeHeld}. */
    public boolean placeBlock(BlockPos target) {
        return placeHeld(target, true);
    }

    /**
     * Places a block at the position by clicking a solid neighbour (call every tick until it returns true).
     *
     * @param throwaway true to switch to a throwaway block first, false to place whatever is in hand
     * @return true once the position is occupied by a non-replaceable block
     */
    public boolean placeHeld(BlockPos target, boolean throwaway) {
        BlockState state = mc.level.getBlockState(target);
        if (!state.canBeReplaced()) {
            return true;
        }
        if (bot.tick - lastPlaceTick < (fastPlace ? 2 : 4)) {
            return false;
        }
        if (throwaway && !bot.inv.holdThrowaway()) {
            return false;
        }
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
                Direction.UP};
        // First pass skips neighbours that would open a screen when clicked (chests, furnaces, ...);
        // the second pass accepts them as a last resort.
        for (int pass = 0; pass < 2; pass++) {
            for (Direction side : order) {
                BlockPos ref = target.relative(side);
                BlockState support = mc.level.getBlockState(ref);
                if (support.canBeReplaced() || support.isAir()) {
                    continue;
                }
                boolean interactive = support.getMenuProvider(mc.level, ref) != null || support.hasBlockEntity();
                if (interactive && pass == 0) {
                    continue;
                }
                Direction face = side.getOpposite();
                Vec3 hit = Vec3.atCenterOf(ref)
                        .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                lookAt(hit);
                mc.gameMode.useItemOn(p(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, ref, false));
                p().swing(InteractionHand.MAIN_HAND);
                lastPlaceTick = bot.tick;
                return false;
            }
        }
        return false;
    }

    /** Right-clicks the given face of a block with the held item. Rate limited to once per 6 ticks. */
    public boolean useOn(BlockPos pos, Direction face) {
        if (bot.tick - lastUseTick < 6L) {
            return false;
        }
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        lookAt(hit);
        mc.gameMode.useItemOn(p(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        p().swing(InteractionHand.MAIN_HAND);
        lastUseTick = bot.tick;
        return true;
    }

    /** Right-clicks the held item in the air. Rate limited to once per 6 ticks. */
    public boolean useItem() {
        if (bot.tick - lastUseTick < 6L) {
            return false;
        }
        mc.gameMode.useItem(p(), InteractionHand.MAIN_HAND);
        p().swing(InteractionHand.MAIN_HAND);
        lastUseTick = bot.tick;
        return true;
    }

    /** True when the attack cooldown has (almost) fully recharged. */
    public boolean attackReady() {
        return p().getAttackStrengthScale(0.5F) >= 0.95F;
    }

    public void attack(Entity target) {
        lookAt(target.getBoundingBox().getCenter());
        mc.gameMode.attack(p(), target);
        p().swing(InteractionHand.MAIN_HAND);
    }
}
