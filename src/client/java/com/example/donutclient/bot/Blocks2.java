package com.example.donutclient.bot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Classifies block states into movement-relevant flags. */
public final class Blocks2 {
    private Blocks2() {}

    /** Body can occupy this block freely (air-like). */
    public static final int PASS = 1;
    /** A player can stand on top of this block. */
    public static final int STAND = 2;
    /** The bot is allowed to break this block. */
    public static final int BREAK = 4;
    public static final int WATER = 8;
    /** Never enter. */
    public static final int DANGER = 16;
    public static final int LAVA = 32;
    public static final int FALLING = 64;
    /** Chunk not loaded: unknown. */
    public static final int UNLOADED = 128;

    public static int flags(BlockGetter level, BlockPos pos, BlockState s) {
        if (s.isAir()) return PASS;
        Block b = s.getBlock();
        FluidState fluid = s.getFluidState();
        if (fluid.is(FluidTags.LAVA)) return LAVA | DANGER;
        VoxelShape shape = s.getCollisionShape(level, pos);
        boolean empty = shape.isEmpty();
        if (fluid.is(FluidTags.WATER)) {
            if (empty) return WATER;
            return standable(shape) ? STAND : 0;
        }
        if (b instanceof BaseFireBlock || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.WITHER_ROSE
                || b == Blocks.POWDER_SNOW || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL
                || b == Blocks.END_GATEWAY) {
            return DANGER;
        }
        if (empty) {
            if (b == Blocks.COBWEB) return BREAK;
            return PASS;
        }
        int f = 0;
        if (b == Blocks.CACTUS) return BREAK;
        if (b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) return BREAK;
        if (standable(shape)) f |= STAND;
        if (breakable(level, pos, s)) f |= BREAK;
        if (b instanceof Fallable) f |= FALLING;
        return f;
    }

    public static boolean breakable(BlockGetter level, BlockPos pos, BlockState s) {
        float h = s.getDestroySpeed(level, pos);
        if (h < 0 || h >= 40) return false; // bedrock, portal frames, obsidian
        Block b = s.getBlock();
        return b != Blocks.SPAWNER && b != Blocks.CRAFTING_TABLE && b != Blocks.FURNACE
                && b != Blocks.CHEST && b != Blocks.END_PORTAL_FRAME;
    }

    private static boolean standable(VoxelShape shape) {
        if (Block.isFaceFull(shape, Direction.UP)) return shape.max(Direction.Axis.Y) <= 1.0;
        double maxY = shape.max(Direction.Axis.Y);
        return maxY >= 0.85 && maxY <= 1.0
                && shape.min(Direction.Axis.X) <= 0.001 && shape.max(Direction.Axis.X) >= 0.999
                && shape.min(Direction.Axis.Z) <= 0.001 && shape.max(Direction.Axis.Z) >= 0.999
                && shape.toAabbs().size() == 1;
    }

    public static boolean isPass(int f) { return (f & PASS) != 0; }
    public static boolean isStand(int f) { return (f & STAND) != 0; }
    public static boolean isWater(int f) { return (f & WATER) != 0; }
}
