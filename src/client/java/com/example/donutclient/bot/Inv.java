package com.example.donutclient.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Inventory helper for the bot: counting and finding items, moving things into the hand,
 * choosing the best tool or weapon, and throwing away junk when the inventory fills up.
 * Slots 0-8 are the hotbar, 9-35 the main inventory.
 */
public final class Inv {
    private final Minecraft mc = Minecraft.getInstance();

    /** Cheap building blocks the bot is allowed to place freely (bridging, pillaring, shelters). */
    public static final Set<Item> THROWAWAY = Set.of(
            Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.COBBLED_DEEPSLATE, Items.STONE, Items.END_STONE,
            Items.DEEPSLATE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.BASALT,
            Items.NETHER_BRICKS, Items.SANDSTONE, Items.STONE_BRICKS, Items.MOSSY_COBBLESTONE, Items.COARSE_DIRT,
            Items.ROOTED_DIRT, Items.CALCITE, Items.MOSSY_STONE_BRICKS, Items.CRACKED_STONE_BRICKS,
            Items.WARPED_NYLIUM, Items.CRIMSON_NYLIUM, Items.SOUL_SOIL, Items.SMOOTH_BASALT, Items.TERRACOTTA);

    /** When set (and in stock), this block is used for placing before any other throwaway block. */
    public Item preferBlock;

    private LocalPlayer p() {
        return mc.player;
    }

    public ItemStack get(int slot) {
        return p().getInventory().getItem(slot);
    }

    /** Total number of items matching the predicate in the 36 inventory slots plus the offhand. */
    public int count(Predicate<ItemStack> pred) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = get(i);
            if (!stack.isEmpty() && pred.test(stack)) {
                total += stack.getCount();
            }
        }
        ItemStack offhand = p().getOffhandItem();
        if (!offhand.isEmpty() && pred.test(offhand)) {
            total += offhand.getCount();
        }
        return total;
    }

    public int count(Item item) {
        return count(s -> s.is(item));
    }

    public int count(TagKey<Item> tag) {
        return count(s -> s.is(tag));
    }

    public boolean has(Item item) {
        return count(item) > 0;
    }

    /**
     * Number of throwaway blocks {@link #holdThrowaway} can actually put in the hand: only the 36 inventory slots
     * count, not the offhand, so callers never wait for a block that cannot be selected.
     */
    public int countThrowaway() {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = get(i);
            if (!stack.isEmpty() && THROWAWAY.contains(stack.getItem()) && stack.getItem() instanceof BlockItem) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** First inventory slot (0-35) holding a matching stack, or -1. */
    public int find(Predicate<ItemStack> pred) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = get(i);
            if (!stack.isEmpty() && pred.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    public int freeSlots() {
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (get(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** All stacks in the inventory that are tools. */
    public List<ItemStack> tools() {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = get(i);
            if (!stack.isEmpty() && stack.has(DataComponents.TOOL)) {
                list.add(stack);
            }
        }
        return list;
    }

    /** True when no container screen is open, i.e. inventory clicks go to the player's own inventory menu. */
    public boolean menuIsInventory() {
        return p().containerMenu == p().inventoryMenu;
    }

    /**
     * Puts the given inventory slot into the main hand. Hotbar slots are simply selected; other slots are swapped
     * into a hotbar slot first (only possible while no container is open).
     *
     * @return true if the item is now selected
     */
    public boolean hold(int slot) {
        if (slot < 0) {
            return false;
        }
        Inventory inventory = p().getInventory();
        if (slot < 9) {
            inventory.setSelectedSlot(slot);
            return true;
        }
        if (!menuIsInventory()) {
            return false;
        }
        int target = pickHotbarSlot();
        // For slots 9-35 the inventory index equals the inventory-menu slot id.
        mc.gameMode.handleContainerInput(p().inventoryMenu.containerId, slot, target, ContainerInput.SWAP, p());
        inventory.setSelectedSlot(target);
        return true;
    }

    /** Hotbar slot to swap into: an empty one, else the right-most that holds no tool, weapon or food, else slot 8. */
    private int pickHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (get(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 8; i >= 0; i--) {
            ItemStack stack = get(i);
            if (!stack.has(DataComponents.TOOL) && !stack.has(DataComponents.WEAPON)
                    && !stack.has(DataComponents.FOOD)) {
                return i;
            }
        }
        return 8;
    }

    /** Holds the first stack matching the predicate (no-op if the main hand already matches). */
    public boolean hold(Predicate<ItemStack> pred) {
        ItemStack held = p().getMainHandItem();
        if (!held.isEmpty() && pred.test(held)) {
            return true;
        }
        return hold(find(pred));
    }

    public boolean hold(Item item) {
        return hold(s -> s.is(item));
    }

    /** Holds a placeable throwaway block, preferring {@link #preferBlock} while there is any. */
    public boolean holdThrowaway() {
        if (preferBlock != null && count(preferBlock) > 0) {
            return hold(preferBlock);
        }
        return hold(s -> THROWAWAY.contains(s.getItem()) && s.getItem() instanceof BlockItem);
    }

    /** Items that must never be dropped as junk. */
    private static boolean keep(ItemStack stack) {
        Item item = stack.getItem();
        if (stack.has(DataComponents.TOOL) || stack.has(DataComponents.WEAPON) || stack.has(DataComponents.FOOD)
                || stack.has(DataComponents.EQUIPPABLE) || stack.isDamageableItem()) {
            return true;
        }
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS) || stack.is(ItemTags.STONE_TOOL_MATERIALS)) {
            return true;
        }
        // Valuables and useful finds that are not part of the bot's own crafting tree but must not be binned.
        if (stack.is(ItemTags.BEDS) || stack.is(ItemTags.ARROWS) || stack.is(ItemTags.BOATS)) {
            return true;
        }
        if (item == Items.DIAMOND || item == Items.GOLD_INGOT || item == Items.RAW_GOLD || item == Items.STRING
                || item == Items.IRON_BLOCK || item == Items.RAW_IRON_BLOCK || item == Items.COAL_BLOCK
                || item == Items.IRON_NUGGET) {
            return true;
        }
        return item == Items.STICK || item == Items.COAL || item == Items.CHARCOAL || item == Items.RAW_IRON
                || item == Items.IRON_INGOT || item == Items.CRAFTING_TABLE || item == Items.FURNACE
                || item == Items.BUCKET || item == Items.WATER_BUCKET || item == Items.LAVA_BUCKET
                || item == Items.FLINT_AND_STEEL || item == Items.FLINT || item == Items.BLAZE_ROD
                || item == Items.BLAZE_POWDER || item == Items.ENDER_PEARL || item == Items.ENDER_EYE
                || item == Items.SHIELD || item == Items.OBSIDIAN || item == Items.END_STONE || item == Items.BEEF
                || item == Items.PORKCHOP || item == Items.MUTTON || item == Items.CHICKEN;
    }

    /**
     * Throws one stack away when fewer than 4 slots are free. Prefers a stack that is neither worth keeping nor a
     * building block; failing that, and if more than 320 building blocks are carried, drops a stack of the least
     * useful building block.
     */
    public void dropJunk() {
        if (!menuIsInventory() || freeSlots() >= 4) {
            return;
        }
        int victim = -1;
        for (int i = 9; i < 36 && victim < 0; i++) {
            ItemStack stack = get(i);
            if (!stack.isEmpty() && !keep(stack) && !THROWAWAY.contains(stack.getItem())) {
                victim = i;
            }
        }
        for (int i = 0; i < 9 && victim < 0; i++) {
            ItemStack stack = get(i);
            if (!stack.isEmpty() && !keep(stack) && !THROWAWAY.contains(stack.getItem())) {
                victim = i;
            }
        }
        if (victim < 0 && countThrowaway() > 320) {
            // Surplus building blocks, least valuable first.
            Item[] order = {Items.NETHERRACK, Items.DIRT, Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.TUFF,
                    Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE, Items.BASALT, Items.COBBLESTONE};
            for (Item item : order) {
                if (item == preferBlock) {
                    continue;
                }
                for (int i = 35; i >= 0 && victim < 0; i--) {
                    if (get(i).is(item)) {
                        victim = i;
                    }
                }
                if (victim >= 0) {
                    break;
                }
            }
        }
        if (victim < 0) {
            return;
        }
        // Inventory-menu slot ids: hotbar 0-8 lives at 36-44, main inventory 9-35 maps one to one.
        int menuSlot = victim < 9 ? 36 + victim : victim;
        mc.gameMode.handleContainerInput(p().inventoryMenu.containerId, menuSlot, 1, ContainerInput.THROW, p());
    }

    /** Rough worth of a tool by material name; cheaper tools are preferred when they are fast enough. */
    private static int toolValue(ItemStack stack) {
        Item item = stack.getItem();
        String name = item.toString();
        if (name.contains("netherite")) {
            return 6;
        }
        if (name.contains("diamond")) {
            return 5;
        }
        if (name.contains("iron")) {
            return 4;
        }
        if (name.contains("golden")) {
            return 1;
        }
        if (name.contains("stone")) {
            return 2;
        }
        if (name.contains("wooden")) {
            return 1;
        }
        return 3;
    }

    /** Estimated ticks to break a block of the given hardness with the given stack (vanilla formula, no modifiers). */
    private static double breakTicks(BlockState state, float hardness, ItemStack stack) {
        boolean canHarvest = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        float speed = Math.max(1.0F, stack.getDestroySpeed(state));
        return Math.ceil(1.0 / (speed / hardness / (canHarvest ? 30.0 : 100.0)));
    }

    /**
     * Selects the tool that breaks the block fastest. A cheaper tool wins when it is within 2 ticks of the best.
     * If no tool helps and a damageable item is in hand, switches to a hotbar slot without one to save durability.
     */
    public void holdBestTool(BlockState state) {
        float hardness = state.getDestroySpeed(mc.level, BlockPos.ZERO);
        if (hardness <= 0.0F) {
            return;
        }
        int bestSlot = -1;
        double bestTicks = breakTicks(state, hardness, ItemStack.EMPTY);
        int bestValue = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = get(i);
            if (stack.isEmpty() || !stack.has(DataComponents.TOOL)) {
                continue;
            }
            double ticks = breakTicks(state, hardness, stack);
            int value = toolValue(stack);
            if (ticks < bestTicks - 2.0
                    || (ticks <= bestTicks + 2.0 && bestSlot >= 0 && value < bestValue && ticks <= bestTicks + 2.0)) {
                bestSlot = i;
                bestTicks = Math.min(ticks, bestTicks);
                bestValue = value;
            }
        }
        if (bestSlot >= 0) {
            hold(bestSlot);
            return;
        }
        ItemStack held = p().getMainHandItem();
        if (held.isDamageableItem()) {
            for (int i = 0; i < 9; i++) {
                if (!get(i).isDamageableItem()) {
                    p().getInventory().setSelectedSlot(i);
                    return;
                }
            }
        }
    }

    /** Holds the best melee weapon available. Returns false if the bot has nothing better than fists. */
    public boolean holdWeapon() {
        int bestSlot = -1;
        double bestScore = 0.0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = get(i);
            double score = weaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        ItemStack held = p().getMainHandItem();
        if (weaponScore(held) >= bestScore) {
            return true;
        }
        return hold(bestSlot);
    }

    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0;
        }
        Item item = stack.getItem();
        if (item == Items.NETHERITE_SWORD) {
            return 8.0;
        }
        if (item == Items.DIAMOND_SWORD) {
            return 7.0;
        }
        // Slow but hard-hitting weapons rank just below the sword that out-damages them over time.
        if (item == Items.NETHERITE_AXE || item == Items.TRIDENT) {
            return 6.5;
        }
        if (item == Items.IRON_SWORD) {
            return 6.0;
        }
        if (item == Items.DIAMOND_AXE || item == Items.MACE) {
            return 5.5;
        }
        if (item == Items.STONE_SWORD || item == Items.COPPER_SWORD) {
            return 5.0;
        }
        if (item == Items.IRON_AXE) {
            return 4.5;
        }
        if (item == Items.STONE_AXE || item == Items.COPPER_AXE) {
            return 4.2;
        }
        if (item == Items.WOODEN_SWORD || item == Items.GOLDEN_SWORD) {
            return 4.0;
        }
        if (item == Items.WOODEN_AXE || item == Items.GOLDEN_AXE) {
            return 3.5;
        }
        if (stack.is(ItemTags.PICKAXES)) {
            return 2.0;
        }
        return 0.0;
    }
}
