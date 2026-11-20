package com.example.donutclient.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Line-of-sight helpers for items that act where the player is really looking (buckets). */
public final class Aim {
    private final Bot bot;

    Aim(Bot bot) { this.bot = bot; }

    private static final double[][] OFFSETS = {{0, 0}, {0.3, 0}, {-0.3, 0}, {0, 0.3}, {0, -0.3}, {0.3, 0.3}, {-0.3, -0.3},
            {0.3, -0.3}, {-0.3, 0.3}, {0.42, 0}, {-0.42, 0}, {0, 0.42}, {0, -0.42}};

    /**
     * A point on a solid neighbour's face, visible from the eye, such that using a
     * filled bucket there puts the fluid at target. Null if none is visible.
     */
    public Vec3 faceFor(BlockPos target) {
        Vec3 eye = bot.player().getEyePosition();
        for (Direction d : Direction.values()) {
            BlockPos ref = target.relative(d);
            BlockState rs = bot.level().getBlockState(ref);
            if (rs.isAir() || rs.canBeReplaced() || !rs.getFluidState().isEmpty()) continue;
            if (!rs.isCollisionShapeFullBlock(bot.level(), ref)) continue;
            Vec3 p = visiblePoint(eye, ref, d.getOpposite());
            if (p != null) return p;
        }
        return null;
    }

    /** A visible point on the given face of a block, or null. */
    public Vec3 visiblePoint(Vec3 eye, BlockPos ref, Direction face) {
        Vec3 c = Vec3.atCenterOf(ref).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        for (double[] o : OFFSETS) {
            Vec3 p;
            switch (face.getAxis()) {
                case X -> p = c.add(0, o[0], o[1]);
                case Y -> p = c.add(o[0], 0, o[1]);
                default -> p = c.add(o[0], o[1], 0);
            }
            if (eye.distanceTo(p) > 4.3) continue;
            // Aim a hair past the surface so the ray actually enters the block.
            Vec3 dir = p.subtract(eye).normalize();
            Vec3 to = p.add(dir.scale(0.05));
            BlockHitResult hit = bot.level().clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE, bot.player()));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(ref) && hit.getDirection() == face) return p;
        }
        return null;
    }

    /** True if an empty bucket used while looking at the centre of this fluid source would pick it up. */
    public boolean canScoop(BlockPos source) {
        Vec3 eye = bot.player().getEyePosition();
        Vec3 c = Vec3.atCenterOf(source);
        if (eye.distanceTo(c) > 4.3) return false;
        Vec3 to = c.add(c.subtract(eye).normalize().scale(0.3));
        BlockHitResult hit = bot.level().clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY, bot.player()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(source);
    }
}
