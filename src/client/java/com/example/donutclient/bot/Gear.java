package com.example.donutclient.bot;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** What equipment the bot owns, counting worn armour and the off hand. */
public final class Gear {
    private final Bot bot;

    Gear(Bot bot) { this.bot = bot; }

    public boolean has(Item item) {
        if (bot.inv.count(item) > 0) return true;
        LocalPlayer p = bot.player();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (p.getItemBySlot(slot).is(item)) return true;
        }
        return false;
    }

    /** 0 none, 1 wood/gold, 2 stone, 3 iron, 4 diamond+. */
    public int pickTier() {
        int best = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = bot.inv.get(i);
            if (!s.is(ItemTags.PICKAXES)) continue;
            Item it = s.getItem();
            int t = it == Items.WOODEN_PICKAXE || it == Items.GOLDEN_PICKAXE ? 1 : it == Items.STONE_PICKAXE ? 2
                    : it == Items.IRON_PICKAXE ? 3 : 4;
            best = Math.max(best, t);
        }
        return best;
    }

    public int buckets() {
        return bot.inv.count(Items.BUCKET) + bot.inv.count(Items.WATER_BUCKET) + bot.inv.count(Items.LAVA_BUCKET);
    }

    /** Iron ingots still needed to finish the standard kit. */
    public int ironNeeded() {
        int n = 0;
        int picks = bot.inv.count(Items.IRON_PICKAXE);
        if (picks < 2) n += 3 * (2 - picks);
        int b = buckets();
        if (b < 2) n += 3 * (2 - b);
        if (!has(Items.IRON_SWORD)) n += 2;
        if (!has(Items.SHIELD)) n += 1;
        if (!has(Items.FLINT_AND_STEEL)) n += 1;
        if (!has(Items.IRON_CHESTPLATE)) n += 8;
        if (!has(Items.IRON_LEGGINGS)) n += 7;
        if (!has(Items.IRON_HELMET)) n += 5;
        if (!has(Items.IRON_BOOTS)) n += 4;
        return n;
    }

    public int ironOwned() {
        return bot.inv.count(Items.IRON_INGOT) + bot.inv.count(Items.RAW_IRON);
    }
}
