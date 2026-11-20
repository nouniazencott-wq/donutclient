package com.example.donutclient.bot;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Facts the bot has learned during the current run and that must survive
 * task switches: portal locations, stronghold progress, and where it died.
 */
public final class Memory {
    /** Nether portal the bot built/used in the overworld. */
    public BlockPos overworldPortal;
    /** The matching portal on the Nether side. */
    public BlockPos netherPortal;
    /** Position of the End portal in the stronghold. */
    public BlockPos strongholdPortal;
    /** True once every End portal frame holds an eye. */
    public boolean portalFilled;
    /** Number of End portal frames still without an eye; -1 while unknown. */
    public int framesMissing = -1;
    public boolean dragonDead;

    /** Where and when the bot last died (used to go back for the dropped items). */
    public BlockPos deathPos;
    public ResourceKey<Level> deathDim;
    public long deathTick;
    /** How many deaths happened close to the previous death position. */
    public int deathsNearby;
    /** True while the bot is walking back to its death position. */
    public boolean recovering;
    public boolean diedRecovering;

    /** Places that killed the bot and should be avoided from now on (newest last). */
    public final List<BlockPos> dangerZones = new ArrayList<>();
    /** Dimension of each entry of {@link #dangerZones} (same order). */
    private final List<ResourceKey<Level>> dangerDims = new ArrayList<>();
    /** At most this many danger zones are remembered; the oldest is forgotten first. */
    private static final int MAX_DANGER_ZONES = 12;
    /** The dimension the bot is in right now; kept up to date by {@link Bot}. */
    public ResourceKey<Level> currentDim;

    /** Remembers that the bot died at {@code pos} in dimension {@code dim}. */
    public void addDanger(BlockPos pos, ResourceKey<Level> dim) {
        dangerZones.add(pos);
        dangerDims.add(dim);
        while (dangerZones.size() > MAX_DANGER_ZONES) {
            dangerZones.remove(0);
            dangerDims.remove(0);
        }
    }

    /** How many remembered deaths in dimension {@code dim} happened within {@code radius} blocks of {@code pos}. */
    public int deathsNear(BlockPos pos, ResourceKey<Level> dim, double radius) {
        int n = 0;
        for (int i = 0; i < dangerZones.size(); i++) {
            if (dangerDims.get(i) == dim && dangerZones.get(i).distSqr(pos) < radius * radius) {
                n++;
            }
        }
        return n;
    }

    /**
     * True when {@code pos} lies within 40 blocks of a remembered danger zone in the current dimension. A death in
     * the overworld says nothing about the same coordinates in the Nether or the End.
     */
    public boolean dangerous(BlockPos pos) {
        for (int i = 0; i < dangerZones.size(); i++) {
            if (currentDim != null && dangerDims.get(i) != currentDim) {
                continue;
            }
            if (dangerZones.get(i).distSqr(pos) < 1600.0) {
                return true;
            }
        }
        return false;
    }

    /** Forgets everything; called when the bot leaves a world so nothing leaks into the next one. */
    public void reset() {
        overworldPortal = null;
        netherPortal = null;
        strongholdPortal = null;
        portalFilled = false;
        framesMissing = -1;
        dragonDead = false;
        deathPos = null;
        deathDim = null;
        deathTick = 0;
        deathsNearby = 0;
        recovering = false;
        diedRecovering = false;
        dangerZones.clear();
        dangerDims.clear();
        currentDim = null;
    }
}
