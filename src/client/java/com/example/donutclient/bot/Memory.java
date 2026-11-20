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

    /** Places that killed the bot repeatedly and should be avoided from now on. */
    public final List<BlockPos> dangerZones = new ArrayList<>();

    /** True when {@code pos} lies within 40 blocks of a remembered danger zone. */
    public boolean dangerous(BlockPos pos) {
        for (BlockPos zone : dangerZones) {
            if (zone.distSqr(pos) < 1600.0) {
                return true;
            }
        }
        return false;
    }
}
