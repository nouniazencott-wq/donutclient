package com.example.donutclient.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Inventory queries and hotbar management (player inventory menu only). */
public final class Inv {
    private final Minecraft mc = Minecraft.getInstance();

    /** Blocks the bot may freely place for bridging/pillaring. */
    public static final Set<Item> THROWAWAY = Set.of(
            Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.COBBLED_DEEPSLATE, Items.STONE,
            Items.END_STONE, Items.DEEPSLATE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF,
            Items.BLACKSTONE, Items.BASALT, Items.NETHER_BRICKS, Items.SANDSTONE, Items.STONE_BRICKS,
            Items.MOSSY_COBBLESTONE, Items.COARSE_DIRT, Items.ROOTED_DIRT, Items.CALCITE,
            Items.MOSSY_STONE_BRICKS, Items.CRACKED_STONE_BRICKS, Items.WARPED_NYLIUM, Items.CRIMSON_NYLIUM,
            Items.SOUL_SOIL, Items.SMOOTH_BASALT, Items.TERRACOTTA);

    private LocalPlayer p() { return mc.player; }

    public ItemStack get(int slot) { return p().getInventory().getItem(slot); }

    public int count(Predicate<ItemStack> pred) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = get(i);
            if (!s.isEmpty() && pred.test(s)) n += s.getCount();
        }
        ItemStack off = p().getOffhandItem();
        if (!off.isEmpty() && pred.test(off)) n += off.getCount();
        return n;
    }

    public int count(Item item) { return count(s -> s.is(item)); }

    public int count(TagKey<Item> tag) { return count(s -> s.is(tag)); }

    public boolean has(Item item) { return count(item) > 0; }

    public int countThrowaway() { return count(s -> THROWAWAY.contains(s.getItem())); }

    public int find(Predicate<ItemStack> pred) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = get(i);
            if (!s.isEmpty() && pred.test(s)) return i;
        }
        return -1;
    }

    public int freeSlots() {
        int n = 0;
        for (int i = 0; i < 36; i++) if (get(i).isEmpty()) n++;
        return n;
    }

    public List<ItemStack> tools() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = get(i);
            if (!s.isEmpty() && s.has(DataComponents.TOOL)) out.add(s);
        }
        return out;
    }

    public boolean menuIsInventory() { return p().containerMenu == p().inventoryMenu; }

    /** Makes the given inventory slot (0..35) the held item. Returns false if impossible right now. */
    public boolean hold(int slot) {
        if (slot < 0) return false;
        var inv = p().getInventory();
        if (slot < 9) { inv.setSelectedSlot(slot); return true; }
        if (!menuIsInventory()) return false;
        int target = pickHotbarSlot();
        mc.gameMode.handleContainerInput(p().inventoryMenu.containerId, slot, target, ContainerInput.SWAP, p());
        inv.setSelectedSlot(target);
        return true;
    }

    private int pickHotbarSlot() {
        for (int i = 0; i < 9; i++) if (get(i).isEmpty()) return i;
        // Prefer overwriting something that is neither a tool nor food.
        for (int i = 8; i >= 0; i--) {
            ItemStack s = get(i);
            if (!s.has(DataComponents.TOOL) && !s.has(DataComponents.WEAPON) && !s.has(DataComponents.FOOD)) return i;
        }
        return 8;
    }

    public boolean hold(Predicate<ItemStack> pred) {
        ItemStack cur = p().getMainHandItem();
        if (!cur.isEmpty() && pred.test(cur)) return true;
        return hold(find(pred));
    }

    public boolean hold(Item item) { return hold(s -> s.is(item)); }

    public boolean holdThrowaway() { return hold(s -> THROWAWAY.contains(s.getItem()) && s.getItem() instanceof BlockItem); }

    /** Holds the fastest tool for the block (or a non-tool if tools do not help). */
    public void holdBestTool(BlockState state) {
        int best = -1;
        float bestSpeed = 1.0f;
        boolean needs = state.requiresCorrectToolForDrops();
        boolean bestOk = false;
        for (int i = 0; i < 36; i++) {
            ItemStack s = get(i);
            if (s.isEmpty()) continue;
            boolean ok = !needs || s.isCorrectToolForDrops(state);
            float sp = s.getDestroySpeed(state);
            if (sp <= 1.0f && !(needs && ok)) continue;
            if ((ok && !bestOk) || (ok == bestOk && sp > bestSpeed)) {
                best = i; bestSpeed = sp; bestOk = ok;
            }
        }
        if (best >= 0) { hold(best); return; }
        // No useful tool: avoid wearing down whatever tool is in hand.
        ItemStack cur = p().getMainHandItem();
        if (cur.isDamageableItem()) {
            for (int i = 0; i < 9; i++) {
                if (!get(i).isDamageableItem()) { p().getInventory().setSelectedSlot(i); return; }
            }
        }
    }

    public boolean holdWeapon() {
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = get(i);
            double sc = weaponScore(s);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        if (best < 0) return false;
        ItemStack cur = p().getMainHandItem();
        if (weaponScore(cur) >= bestScore) return true;
        return hold(best);
    }

    private static double weaponScore(ItemStack s) {
        if (s.isEmpty()) return 0;
        Item i = s.getItem();
        if (i == Items.NETHERITE_SWORD) return 8;
        if (i == Items.DIAMOND_SWORD) return 7;
        if (i == Items.IRON_SWORD) return 6;
        if (i == Items.STONE_SWORD) return 5;
        if (i == Items.IRON_AXE) return 4.5;
        if (i == Items.STONE_AXE) return 4.2;
        if (i == Items.WOODEN_SWORD || i == Items.GOLDEN_SWORD) return 4;
        if (s.is(ItemTags.PICKAXES)) return 2;
        return 0;
    }
}
