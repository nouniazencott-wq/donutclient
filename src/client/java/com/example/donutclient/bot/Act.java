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

/** Low-level world interaction: looking, breaking, placing, attacking. */
public final class Act {
    private final Bot bot;
    private final Minecraft mc = Minecraft.getInstance();
    private long lastPlaceTick = -100;
    private long lastUseTick = -100;
    /** Set while the bot is mining so the vanilla "mouse released" logic does not reset progress. */
    public boolean breaking;
    /** True if breakBlock was called during the current tick. */
    public boolean breakingRequested;
    /** Emergency building: place blocks as fast as the game allows. */
    public boolean fastPlace;

    Act(Bot bot) { this.bot = bot; }

    private LocalPlayer p() { return mc.player; }

    public void lookAt(Vec3 target) {
        Vec3 eye = p().getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double hor = Math.sqrt(dx * dx + dz * dz);
        if (hor > 1e-4 || Math.abs(dy) > 1e-4) {
            if (hor > 1e-4) p().setYRot((float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f);
            p().setXRot(Mth.clamp((float) -(Mth.atan2(dy, hor) * (180 / Math.PI)), -90f, 90f));
        }
    }

    public void lookAt(BlockPos pos) { lookAt(Vec3.atCenterOf(pos)); }

    /** Face horizontally toward a point (used while walking). */
    public void faceXZ(double x, double z) {
        double dx = x - p().getX(), dz = z - p().getZ();
        if (dx * dx + dz * dz > 1e-6) p().setYRot((float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f);
        p().setXRot(10f);
    }

    public double eyeDist(BlockPos pos) { return Math.sqrt(p().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))); }

    public boolean inReach(BlockPos pos) { return eyeDist(pos) <= 4.4; }

    /** Call every tick until it returns true (block is gone / passable). */
    public boolean breakBlock(BlockPos pos) {
        BlockState s = mc.level.getBlockState(pos);
        if (s.isAir()) return true;
        if (s.liquid()) return true;
        bot.inv.holdBestTool(s);
        lookAt(pos);
        breaking = true;
        breakingRequested = true;
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

    /** Places a throwaway block at target. Returns true once the spot is occupied. */
    public boolean placeBlock(BlockPos target) {
        return placeHeld(target, true);
    }

    /**
     * Places the block item at target against any solid neighbour.
     * If throwaway is false the currently held item is used.
     */
    public boolean placeHeld(BlockPos target, boolean throwaway) {
        BlockState cur = mc.level.getBlockState(target);
        if (!cur.canBeReplaced()) return true;
        if (bot.tick - lastPlaceTick < (fastPlace ? 2 : 4)) return false;
        if (throwaway && !bot.inv.holdThrowaway()) return false;
        Direction[] order = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        for (int pass = 0; pass < 2; pass++) {
            for (Direction d : order) {
                BlockPos ref = target.relative(d);
                BlockState rs = mc.level.getBlockState(ref);
                if (rs.canBeReplaced() || rs.isAir()) continue;
                boolean interactive = rs.getMenuProvider(mc.level, ref) != null || rs.hasBlockEntity();
                if (interactive && pass == 0) continue;
                Direction face = d.getOpposite();
                Vec3 hit = Vec3.atCenterOf(ref).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                lookAt(hit);
                mc.gameMode.useItemOn(p(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, ref, false));
                p().swing(InteractionHand.MAIN_HAND);
                lastPlaceTick = bot.tick;
                return false;
            }
        }
        return false;
    }

    /** Right-clicks a block (open a container, use an item on it). Rate limited. */
    public boolean useOn(BlockPos pos, Direction face) {
        if (bot.tick - lastUseTick < 6) return false;
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        lookAt(hit);
        mc.gameMode.useItemOn(p(), InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
        p().swing(InteractionHand.MAIN_HAND);
        lastUseTick = bot.tick;
        return true;
    }

    /** Uses the held item in the air (buckets, eyes, food start). Rate limited. */
    public boolean useItem() {
        if (bot.tick - lastUseTick < 6) return false;
        mc.gameMode.useItem(p(), InteractionHand.MAIN_HAND);
        p().swing(InteractionHand.MAIN_HAND);
        lastUseTick = bot.tick;
        return true;
    }

    public boolean attackReady() { return p().getAttackStrengthScale(0.5f) >= 0.95f; }

    public void attack(Entity e) {
        lookAt(e.getBoundingBox().getCenter());
        mc.gameMode.attack(p(), e);
        p().swing(InteractionHand.MAIN_HAND);
    }
}
