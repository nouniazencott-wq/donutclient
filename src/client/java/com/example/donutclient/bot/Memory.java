package com.example.donutclient.bot;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Things worth remembering across tasks (and across deaths). */
public final class Memory {
    /** Overworld-side nether portal we built or used. */
    public BlockPos overworldPortal;
    /** Nether-side portal position. */
    public BlockPos netherPortal;
    public BlockPos strongholdPortal;
    /** Places where the bot died; resource gathering steers clear of them. */
    public final List<BlockPos> dangerZones = new ArrayList<>();

    public boolean dangerous(BlockPos p) {
        for (BlockPos d : dangerZones) if (d.distSqr(p) < 40 * 40) return true;
        return false;
    }
}
