package com.example.donutclient.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Block search over the chunks the client currently has loaded. Chunks are
 * visited in square rings around the player so the nearest matches are found
 * first and far chunks are only scanned when needed.
 */
public final class Scan {
    private final Bot bot;

    Scan(Bot bot) {
        this.bot = bot;
    }

    /** Like {@link #find(Predicate, int, int, int, int)} without a height limit. */
    public List<BlockPos> find(Predicate<BlockState> pred, int chunkRadius, int max) {
        return find(pred, chunkRadius, max, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Finds blocks matching {@code pred} within {@code chunkRadius} chunks of
     * the player and between {@code minY} and {@code maxY} (inclusive).
     *
     * @return at most {@code max} positions, nearest to the player first
     */
    public List<BlockPos> find(Predicate<BlockState> pred, int chunkRadius, int max, int minY, int maxY) {
        BlockPos origin = bot.feet();
        int pcx = origin.getX() >> 4;
        int pcz = origin.getZ() >> 4;
        List<BlockPos> out = new ArrayList<>();
        // Ring at which we first had enough results; -1 until then.
        int enoughAtRing = -1;
        for (int ring = 0; ring <= chunkRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    // Only the border of the square: inner chunks were done by earlier rings.
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    scanChunk(pcx + dx, pcz + dz, pred, out, minY, maxY);
                }
            }
            if (out.size() >= max && enoughAtRing < 0) {
                enoughAtRing = ring;
            }
            // Scan one extra ring after having enough: a corner of the next
            // ring can still be closer than what the current ring produced.
            if (enoughAtRing >= 0 && ring >= enoughAtRing + 1) {
                break;
            }
            // Hard cap for very common blocks.
            if (out.size() > 20000) {
                break;
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(origin)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Adds every matching block of one loaded chunk to {@code out}; unloaded chunks are skipped. */
    private void scanChunk(int cx, int cz, Predicate<BlockState> pred, List<BlockPos> out, int minY, int maxY) {
        LevelChunk chunk = bot.level().getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) {
            return;
        }
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            if (baseY > maxY || baseY + 15 < minY) {
                continue;
            }
            // Cheap palette check before touching 4096 block states.
            if (!sec.maybeHas(pred)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                int wy = baseY + y;
                if (wy < minY || wy > maxY) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (pred.test(sec.getBlockState(x, y, z))) {
                            out.add(new BlockPos((cx << 4) + x, wy, (cz << 4) + z));
                        }
                    }
                }
            }
        }
    }
}
