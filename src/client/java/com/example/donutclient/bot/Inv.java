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

    /** If set (and available) this block is used for building before any other. */
    public Item preferBlock;

    public boolean holdThrowaway() {
        if (preferBlock != null && count(preferBlock) > 0) return hold(preferBlock);
        return hold(s -> THROWAWAY.contains(s.getItem()) && s.getItem() instanceof BlockItem);
    }

    private static boolean keep(ItemStack s) {
        Item i = s.getItem();
        if (s.has(DataComponents.TOOL) || s.has(DataComponents.WEAPON) || s.has(DataComponents.FOOD)
                || s.has(DataComponents.EQUIPPABLE) || s.isDamageableItem()) return true;
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.STONE_TOOL_MATERIALS)) return true;
        return i == Items.STICK || i == Items.COAL || i == Items.CHARCOAL || i == Items.RAW_IRON || i == Items.IRON_INGOT
                || i == Items.CRAFTING_TABLE || i == Items.FURNACE || i == Items.BUCKET || i == Items.WATER_BUCKET
                || i == Items.LAVA_BUCKET || i == Items.FLINT_AND_STEEL || i == Items.FLINT || i == Items.BLAZE_ROD
                || i == Items.BLAZE_POWDER || i == Items.ENDER_PEARL || i == Items.ENDER_EYE || i == Items.SHIELD
                || i == Items.OBSIDIAN || i == Items.END_STONE || i == Items.BEEF || i == Items.PORKCHOP
                || i == Items.MUTTON || i == Items.CHICKEN;
    }

    /**
     * Keeps a few slots free by throwing away things the run does not need
     * (and building blocks beyond a few stacks). One stack per call.
     */
    public void dropJunk() {
        if (!menuIsInventory() || freeSlots() >= 4) return;
        int victim = -1;
        for (int i = 9; i < 36 && victim < 0; i++) {
            ItemStack s = get(i);
            if (!s.isEmpty() && !keep(s) && !THROWAWAY.contains(s.getItem())) victim = i;
        }
        for (int i = 0; i < 9 && victim < 0; i++) {
            ItemStack s = get(i);
            if (!s.isEmpty() && !keep(s) && !THROWAWAY.contains(s.getItem())) victim = i;
        }
        if (victim < 0 && countThrowaway() > 5 * 64) {
            // Too many building blocks: shed the least useful kind first.
            Item[] order = {Items.NETHERRACK, Items.DIRT, Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.TUFF,
                    Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE, Items.BASALT, Items.COBBLESTONE};
            for (Item it : order) {
                if (it == preferBlock) continue;
                for (int i = 35; i >= 0 && victim < 0; i--) if (get(i).is(it)) victim = i;
                if (victim >= 0) break;
            }
        }
        if (victim < 0) return;
        int menuSlot = victim < 9 ? 36 + victim : victim;
        mc.gameMode.handleContainerInput(p().inventoryMenu.containerId, menuSlot, 1, ContainerInput.THROW, p());
    }

    private static int toolValue(ItemStack s) {
        Item i = s.getItem();
        String n = i.toString();
        if (n.contains("netherite")) return 6;
        if (n.contains("diamond")) return 5;
        if (n.contains("iron")) return 4;
        if (n.contains("golden")) return 1;
        if (n.contains("stone")) return 2;
        if (n.contains("wooden")) return 1;
        return 3;
    }

    /** Ticks to break a block of the given hardness with this stack. */
    private static double breakTicks(BlockState state, float hardness, ItemStack s) {
        boolean ok = !state.requiresCorrectToolForDrops() || s.isCorrectToolForDrops(state);
        float sp = Math.max(1f, s.getDestroySpeed(state));
        return Math.ceil(1.0 / (sp / hardness / (ok ? 30.0 : 100.0)));
    }

    /** Holds the best tool for the block: fast, but not wasting a precious tool on easy blocks. */
    public void holdBestTool(BlockState state) {
        float hardness = state.getDestroySpeed(mc.level, net.minecraft.core.BlockPos.ZERO);
        if (hardness <= 0) return;
        int best = -1;
        double bestTicks = breakTicks(state, hardness, ItemStack.EMPTY);
        int bestValue = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = get(i);
            if (s.isEmpty() || !s.has(DataComponents.TOOL)) continue;
            double t = breakTicks(state, hardness, s);
            int v = toolValue(s);
            // Clearly faster wins; when about as fast, use the cheaper tool.
            if (t < bestTicks - 2 || (t <= bestTicks + 2 && best >= 0 && v < bestValue && t <= bestTicks + 2)) {
                best = i; bestTicks = Math.min(t, bestTicks); bestValue = v;
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
