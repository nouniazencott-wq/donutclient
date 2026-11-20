package com.example.donutclient.esp;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The ores Ore ESP can highlight. Each entry groups the stone/deepslate/nether
 * variants of one resource under a single toggle and colour.
 */
public enum OreType {
    DIAMOND("Diamond", 0x33E5F0, true, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
    ANCIENT_DEBRIS("Ancient Debris", 0xB0603A, true, Blocks.ANCIENT_DEBRIS),
    EMERALD("Emerald", 0x2ECC55, false, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
    GOLD("Gold", 0xFFD700, false, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
    IRON("Iron", 0xD8AF93, false, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
    REDSTONE("Redstone", 0xFF3030, false, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
    LAPIS("Lapis", 0x3060FF, false, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
    COPPER("Copper", 0xE0734D, false, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
    COAL("Coal", 0x606060, false, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
    QUARTZ("Nether Quartz", 0xF0E8E0, false, Blocks.NETHER_QUARTZ_ORE);

    public final String displayName;
    /** 0xRRGGBB, no alpha. */
    public final int rgb;
    public final boolean enabledByDefault;
    public final Block[] blocks;

    OreType(String displayName, int rgb, boolean enabledByDefault, Block... blocks) {
        this.displayName = displayName;
        this.rgb = rgb;
        this.enabledByDefault = enabledByDefault;
        this.blocks = blocks;
    }

    /** Stable key used in the config file. */
    public String id() {
        return name().toLowerCase();
    }

    public int argb(int alpha) {
        return (alpha << 24) | rgb;
    }
}
