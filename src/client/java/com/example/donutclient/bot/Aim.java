package com.example.donutclient.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Works out where the bot has to look to click on something: a point on a
 * block face that is within reach and really visible from the player's eyes,
 * verified with the same ray cast the game uses for the crosshair.
 */
public final class Aim {
    private final Bot bot;

    /**
     * Offsets (along the two axes of a block face, relative to its centre)
     * that are tried in order when looking for a visible point: the centre
     * first, then points 0.3 off, then points close to the edges.
     */
    private static final double[][] OFFSETS = {
            {0.0, 0.0},
            {0.3, 0.0}, {-0.3, 0.0}, {0.0, 0.3}, {0.0, -0.3},
            {0.3, 0.3}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3},
            {0.42, 0.0}, {-0.42, 0.0}, {0.0, 0.42}, {0.0, -0.42},
    };

    Aim(Bot bot) {
        this.bot = bot;
    }

    /**
     * Finds a point to click in order to place a block at {@code target}: a
     * visible spot on the face of a solid, full neighbour block that points
     * towards the target position.
     *
     * @return the point to look at, or null if no neighbour face can be clicked from here
     */
    public Vec3 faceFor(BlockPos target) {
        Vec3 eye = bot.player().getEyePosition();
        for (Direction d : Direction.values()) {
            BlockPos ref = target.relative(d);
            BlockState rs = bot.level().getBlockState(ref);
            if (rs.isAir() || rs.canBeReplaced() || !rs.getFluidState().isEmpty()) {
                continue;
            }
            if (!rs.isCollisionShapeFullBlock(bot.level(), ref)) {
                continue;
            }
            Vec3 p = visiblePoint(eye, ref, d.getOpposite());
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /**
     * Looks for a point on the given {@code face} of block {@code ref} that is
     * within reach (4.3 blocks) of {@code eye} and that a ray from the eye hits
     * first, on that block and on that face.
     *
     * @return the visible point, or null if the face is out of reach or hidden
     */
    public Vec3 visiblePoint(Vec3 eye, BlockPos ref, Direction face) {
        // Centre of the face.
        Vec3 c = Vec3.atCenterOf(ref).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        for (double[] o : OFFSETS) {
            // Spread the offset over the two axes the face lies in.
            Vec3 p;
            switch (face.getAxis()) {
                case X:
                    p = c.add(0.0, o[0], o[1]);
                    break;
                case Y:
                    p = c.add(o[0], 0.0, o[1]);
                    break;
                default:
                    p = c.add(o[0], o[1], 0.0);
                    break;
            }
            if (eye.distanceTo(p) > 4.3) {
                continue;
            }
            // Cast slightly past the point so the ray ends inside the block.
            Vec3 dir = p.subtract(eye).normalize();
            Vec3 to = p.add(dir.scale(0.05));
            BlockHitResult hit = bot.level().clip(
                    new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, bot.player()));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(ref) && hit.getDirection() == face) {
                return p;
            }
        }
        return null;
    }

    /**
     * True if a bucket used while looking at the centre of the fluid source
     * block {@code source} would pick it up: it is within reach and nothing
     * (no block and no other fluid source) is in the way.
     */
    public boolean canScoop(BlockPos source) {
        Vec3 eye = bot.player().getEyePosition();
        Vec3 c = Vec3.atCenterOf(source);
        if (eye.distanceTo(c) > 4.3) {
            return false;
        }
        Vec3 to = c.add(c.subtract(eye).normalize().scale(0.3));
        BlockHitResult hit = bot.level().clip(
                new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, bot.player()));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(source);
    }
}
