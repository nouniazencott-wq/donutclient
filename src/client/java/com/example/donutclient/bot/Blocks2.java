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

/**
 * Classifies blocks for movement planning. Every block is reduced to a small
 * set of bit flags that say whether the bot can walk through it, stand on it,
 * mine it, or must stay away from it.
 */
public final class Blocks2 {
    /** The player can move through this block. */
    public static final int PASS = 1;
    /** The player can stand on top of this block. */
    public static final int STAND = 2;
    /** The block may be mined out of the way. */
    public static final int BREAK = 4;
    /** Water without a collision shape (swimmable). */
    public static final int WATER = 8;
    /** Touching or entering this block hurts or teleports the player. */
    public static final int DANGER = 16;
    public static final int LAVA = 32;
    /** Gravity block (sand, gravel, ...): mining below it makes it fall. */
    public static final int FALLING = 64;
    /** Position is in a chunk the client has not loaded. */
    public static final int UNLOADED = 128;

    private Blocks2() {}

    /** Computes the flag set for the block state {@code s} at {@code pos}. */
    public static int flags(BlockGetter level, BlockPos pos, BlockState s) {
        if (s.isAir()) {
            return PASS;
        }
        Block b = s.getBlock();
        FluidState fluid = s.getFluidState();
        if (fluid.is(FluidTags.LAVA)) {
            return DANGER | LAVA;
        }
        VoxelShape shape = s.getCollisionShape(level, pos);
        boolean empty = shape.isEmpty();
        if (fluid.is(FluidTags.WATER)) {
            if (empty) {
                return WATER;
            }
            // Waterlogged solid block: never mined, at most stood on.
            return standable(shape) ? STAND : 0;
        }
        if (b instanceof BaseFireBlock || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.WITHER_ROSE
                || b == Blocks.POWDER_SNOW || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL
                || b == Blocks.END_GATEWAY) {
            return DANGER;
        }
        if (empty) {
            // Cobwebs have no collision but slow the player to a crawl: mine instead of walking through.
            if (b == Blocks.COBWEB) {
                return BREAK;
            }
            return PASS;
        }
        int f = 0;
        // Solid blocks that hurt when touched or stood on: only ever mined, never used as floor.
        if (b == Blocks.CACTUS) {
            return BREAK;
        }
        if (b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) {
            return BREAK;
        }
        if (standable(shape)) {
            f |= STAND;
        }
        if (breakable(level, pos, s)) {
            f |= BREAK;
        }
        if (b instanceof Fallable) {
            f |= FALLING;
        }
        return f;
    }

    /**
     * True if the bot is allowed to mine the block: it must be destructible in
     * reasonable time (hardness below 40, so no obsidian or bedrock) and not be
     * one of the blocks the bot needs to keep (spawners, workstations, chests,
     * End portal frames).
     */
    public static boolean breakable(BlockGetter level, BlockPos pos, BlockState s) {
        float h = s.getDestroySpeed(level, pos);
        if (h < 0.0f || h >= 40.0f) {
            return false;
        }
        Block b = s.getBlock();
        return b != Blocks.SPAWNER && b != Blocks.CRAFTING_TABLE && b != Blocks.FURNACE && b != Blocks.CHEST
                && b != Blocks.END_PORTAL_FRAME;
    }

    /**
     * True if the collision shape offers a full, at most one block high top
     * surface: either a full top face, or a single box covering the whole
     * footprint whose top is at least 0.85 high (e.g. dirt paths, farmland).
     */
    private static boolean standable(VoxelShape shape) {
        if (Block.isFaceFull(shape, Direction.UP)) {
            return shape.max(Direction.Axis.Y) <= 1.0;
        }
        double maxY = shape.max(Direction.Axis.Y);
        return maxY >= 0.85 && maxY <= 1.0
                && shape.min(Direction.Axis.X) <= 0.001 && shape.max(Direction.Axis.X) >= 0.999
                && shape.min(Direction.Axis.Z) <= 0.001 && shape.max(Direction.Axis.Z) >= 0.999
                && shape.toAabbs().size() == 1;
    }

    public static boolean isPass(int f) {
        return (f & PASS) != 0;
    }

    public static boolean isStand(int f) {
        return (f & STAND) != 0;
    }

    public static boolean isWater(int f) {
        return (f & WATER) != 0;
    }
}
