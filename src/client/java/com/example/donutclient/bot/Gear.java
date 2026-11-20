package com.example.donutclient.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Questions about the equipment the bot currently owns: which tools and
 * armour it has, and how much iron it still needs for the full iron kit.
 */
public final class Gear {
    private final Bot bot;

    Gear(Bot bot) {
        this.bot = bot;
    }

    /** True if the item is in the inventory or worn/held in any equipment slot. */
    public boolean has(Item item) {
        if (bot.inv.count(item) > 0) {
            return true;
        }
        LocalPlayer player = bot.player();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (player.getItemBySlot(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best pickaxe tier in the 36 inventory slots:
     * 0 = none, 1 = wood/gold, 2 = stone, 3 = iron, 4 = diamond or better.
     */
    public int pickTier() {
        int best = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = bot.inv.get(i);
            if (!stack.is(ItemTags.PICKAXES)) {
                continue;
            }
            Item item = stack.getItem();
            int tier = item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE ? 1
                    : item == Items.STONE_PICKAXE ? 2
                    : item == Items.IRON_PICKAXE ? 3
                    : 4;
            best = Math.max(best, tier);
        }
        return best;
    }

    /** Buckets of any kind (empty, water, lava). */
    public int buckets() {
        return bot.inv.count(Items.BUCKET) + bot.inv.count(Items.WATER_BUCKET) + bot.inv.count(Items.LAVA_BUCKET);
    }

    /**
     * Iron ingots still required for the target kit: two iron pickaxes, two
     * buckets, sword, shield, flint and steel and a full set of iron armour.
     */
    public int ironNeeded() {
        int needed = 0;
        int picks = bot.inv.count(Items.IRON_PICKAXE);
        if (picks < 2) {
            needed += 3 * (2 - picks);
        }
        int buckets = buckets();
        if (buckets < 2) {
            needed += 3 * (2 - buckets);
        }
        if (!has(Items.IRON_SWORD)) needed += 2;
        if (!has(Items.SHIELD)) needed += 1;
        if (!has(Items.FLINT_AND_STEEL)) needed += 1;
        if (!has(Items.IRON_CHESTPLATE)) needed += 8;
        if (!has(Items.IRON_LEGGINGS)) needed += 7;
        if (!has(Items.IRON_HELMET)) needed += 5;
        if (!has(Items.IRON_BOOTS)) needed += 4;
        return needed;
    }

    /** Iron on hand, smelted or not. */
    public int ironOwned() {
        return bot.inv.count(Items.IRON_INGOT) + bot.inv.count(Items.RAW_IRON);
    }
}
