package com.example.donutclient.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Classifies block entities by registry name ("chest", "mob_spawner", ...). */
public final class BlockEntities {
    private BlockEntities() {}

    public static String name(BlockEntity be) {
        Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return id == null ? "" : id.getPath();
    }

    public static boolean isStorage(String n) {
        return n.equals("chest") || n.equals("trapped_chest") || n.equals("barrel") || n.equals("shulker_box");
    }

    public static boolean isSpawner(String n) {
        return n.equals("mob_spawner");
    }

    /**
     * How strongly one block entity of this kind suggests a player base.
     * Naturally generated loot rooms have a couple of chests and at most one
     * spawner; bases have piles of storage, hoppers and stacked spawners.
     */
    public static float baseWeight(String n) {
        switch (n) {
            case "beacon": return 10f;
            case "mob_spawner": return 6f;
            case "conduit": return 5f;
            case "shulker_box": return 4f;
            case "ender_chest":
            case "enchanting_table": return 3f;
            case "brewing_stand":
            case "crafter": return 2f;
            case "hopper": return 1.5f;
            case "chest":
            case "trapped_chest":
            case "barrel":
            case "furnace":
            case "blast_furnace":
            case "smoker":
            case "dispenser":
            case "dropper":
            case "lectern":
            case "jukebox": return 1f;
            case "sign":
            case "hanging_sign":
            case "bed":
            case "banner":
            case "skull": return 0.5f;
            default: return 0f;
        }
    }

    /** ESP color (ARGB) or 0 if this kind is not highlighted. */
    public static int color(String n) {
        switch (n) {
            case "chest":
            case "trapped_chest": return 0xFFFFAA00;
            case "barrel": return 0xFFC08040;
            case "shulker_box": return 0xFFFF55FF;
            case "ender_chest": return 0xFFAA00FF;
            case "mob_spawner": return 0xFFFF3030;
            case "beacon": return 0xFF55FFFF;
            case "hopper":
            case "dispenser":
            case "dropper":
            case "crafter": return 0xFF909090;
            case "furnace":
            case "blast_furnace":
            case "smoker":
            case "brewing_stand": return 0xFFD0D0D0;
            default: return 0;
        }
    }
}
