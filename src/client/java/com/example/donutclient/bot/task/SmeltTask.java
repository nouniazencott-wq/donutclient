package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Ing;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.function.Predicate;

/** Smelts every matching item in the inventory using a furnace the bot carries around. */
public final class SmeltTask extends Task {
    private enum S { PREP, PLACE, OPEN_LOAD, WAIT, OPEN_TAKE, PICKUP, DONE }

    private final Predicate<ItemStack> input;
    private final String what;
    private S state = S.PREP;
    private BlockPos furnace;
    private boolean own;
    private int waitTicks;
    private int stateTicks;
    private int loaded;
    private PickupBlockTask pickup;
    private boolean triedCraft;

    public SmeltTask(Bot bot, String what, Predicate<ItemStack> input) {
        super(bot);
        this.what = what;
        this.input = input;
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        state = S.PREP;
        furnace = null;
    }

    private void click(int slot, int button, ContainerInput type) {
        LocalPlayer p = bot.player();
        bot.mc.gameMode.handleContainerInput(p.containerMenu.containerId, slot, button, type, p);
    }

    private void go(S s) { state = s; stateTicks = 0; }

    private BlockPos findFurnace() {
        BlockPos f = bot.feet();
        for (BlockPos p : BlockPos.betweenClosed(f.offset(-4, -3, -4), f.offset(4, 4, 4))) {
            if (bot.level().getBlockState(p).is(Blocks.FURNACE) && bot.act.eyeDist(p) < 4.2) return p.immutable();
        }
        return null;
    }

    @Override
    public boolean interruptible() { return false; }

    @Override
    public boolean tick() {
        if (busy()) return false;
        LocalPlayer p = bot.player();
        stateTicks++;
        if (state != S.WAIT) bot.setStatus("smelting " + what);
        switch (state) {
            case PREP -> {
                if (bot.inv.count(input) == 0) { go(S.PICKUP); return false; }
                furnace = findFurnace();
                if (furnace != null) { go(S.OPEN_LOAD); return false; }
                if (bot.inv.count(Items.FURNACE) == 0) {
                    CraftTask c = new CraftTask(bot).want(Ing.FURNACE, 1);
                    if (triedCraft || !c.feasible()) { bot.setStatus("smelt: cannot get a furnace"); return true; }
                    triedCraft = true;
                    run(c);
                    return false;
                }
                go(S.PLACE);
            }
            case PLACE -> {
                if (!p.onGround()) return false;
                if (stateTicks > 200) return true;
                BlockPos spot = PlaceSpot.find(bot);
                if (spot == null) {
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos c = bot.feet().above().relative(d);
                        if (bot.level().getBlockState(c).getDestroySpeed(bot.level(), c) >= 0
                                && bot.level().getBlockState(c).getFluidState().isEmpty()) {
                            bot.act.breakBlock(c);
                            return false;
                        }
                    }
                    return true;
                }
                if (bot.inv.hold(Items.FURNACE)) bot.act.placeHeld(spot, false);
                if (bot.level().getBlockState(spot).is(Blocks.FURNACE)) { furnace = spot; own = true; go(S.OPEN_LOAD); }
            }
            case OPEN_LOAD -> {
                if (stateTicks > 200) { closeMenu(); go(S.PICKUP); return false; }
                if (!(p.containerMenu instanceof AbstractFurnaceMenu menu)) {
                    if (bot.inv.menuIsInventory()) bot.act.useOn(furnace, Direction.UP);
                    return false;
                }
                if (stateTicks % 3 != 0) return false;
                if (!menu.getCarried().isEmpty()) { putBack(menu); return false; }
                if (!menu.getSlot(2).getItem().isEmpty()) { click(2, 0, ContainerInput.QUICK_MOVE); return false; }
                ItemStack in = menu.getSlot(0).getItem();
                if (in.isEmpty()) {
                    int src = findSlot(menu, input);
                    if (src < 0) { closeMenu(); go(S.PICKUP); return false; }
                    click(src, 0, ContainerInput.PICKUP);
                    click(0, 0, ContainerInput.PICKUP);
                    return false;
                }
                // Fuel.
                int needTicks = in.getCount() * 200;
                ItemStack fuel = menu.getSlot(1).getItem();
                int haveTicks = fuel.isEmpty() ? 0 : fuel.getCount() * bot.level().fuelValues().burnDuration(fuel);
                if (menu.isLit()) haveTicks += 100;
                if (haveTicks < needTicks) {
                    int src = findFuel(menu, fuel);
                    if (src < 0) {
                        if (haveTicks == 0 && !menu.isLit()) {
                            bot.setStatus("smelt: no fuel");
                            click(0, 0, ContainerInput.QUICK_MOVE);
                            closeMenu();
                            go(S.PICKUP);
                            return false;
                        }
                    } else {
                        ItemStack st = menu.getSlot(src).getItem();
                        int per = bot.level().fuelValues().burnDuration(st);
                        int k = Math.min(st.getCount(), (needTicks - haveTicks + per - 1) / per);
                        click(src, 0, ContainerInput.PICKUP);
                        for (int i = 0; i < k; i++) click(1, 1, ContainerInput.PICKUP);
                        if (!menu.getCarried().isEmpty()) click(src, 0, ContainerInput.PICKUP);
                        return false;
                    }
                }
                loaded = in.getCount();
                waitTicks = loaded * 200 + 60;
                closeMenu();
                go(S.WAIT);
            }
            case WAIT -> {
                bot.setStatus("smelting " + what + " (" + Math.max(0, (waitTicks - stateTicks) / 20) + "s left)");
                if (stateTicks >= waitTicks) go(S.OPEN_TAKE);
            }
            case OPEN_TAKE -> {
                if (stateTicks > 200) { closeMenu(); go(S.PICKUP); return false; }
                if (!(p.containerMenu instanceof AbstractFurnaceMenu menu)) {
                    if (bot.inv.menuIsInventory()) bot.act.useOn(furnace, Direction.UP);
                    return false;
                }
                if (stateTicks % 3 != 0) return false;
                if (!menu.getSlot(2).getItem().isEmpty()) { click(2, 0, ContainerInput.QUICK_MOVE); return false; }
                if (!menu.getSlot(0).getItem().isEmpty() && menu.isLit()) { stateTicks = Math.min(stateTicks, 100); return false; }
                if (!menu.getSlot(0).getItem().isEmpty()) { click(0, 0, ContainerInput.QUICK_MOVE); return false; }
                if (bot.inv.count(input) > 0 && findFuel(menu, menu.getSlot(1).getItem()) >= 0
                        || bot.inv.count(input) > 0 && !menu.getSlot(1).getItem().isEmpty()) {
                    go(S.OPEN_LOAD);
                    return false;
                }
                if (!menu.getSlot(1).getItem().isEmpty()) { click(1, 0, ContainerInput.QUICK_MOVE); return false; }
                closeMenu();
                go(S.PICKUP);
            }
            case PICKUP -> {
                if (!bot.inv.menuIsInventory()) { closeMenu(); return false; }
                if (own && furnace != null) {
                    if (pickup == null) pickup = new PickupBlockTask(bot, furnace, Items.FURNACE);
                    if (!pickup.tick()) return false;
                }
                go(S.DONE);
                return true;
            }
            case DONE -> { return true; }
        }
        return false;
    }

    private void closeMenu() {
        LocalPlayer p = bot.player();
        if (!bot.inv.menuIsInventory()) p.closeContainer();
    }

    private void putBack(AbstractContainerMenu menu) {
        for (Slot s : menu.slots) {
            if (s.container == bot.player().getInventory() && s.getContainerSlot() < 36 && s.getItem().isEmpty()) {
                click(s.index, 0, ContainerInput.PICKUP);
                return;
            }
        }
    }

    private int findSlot(AbstractContainerMenu menu, Predicate<ItemStack> pred) {
        int best = -1, bestCount = 0;
        for (Slot s : menu.slots) {
            if (s.container != bot.player().getInventory() || s.getContainerSlot() >= 36) continue;
            ItemStack st = s.getItem();
            if (!st.isEmpty() && pred.test(st) && st.getCount() > bestCount) { bestCount = st.getCount(); best = s.index; }
        }
        return best;
    }

    private int findFuel(AbstractContainerMenu menu, ItemStack current) {
        if (!current.isEmpty()) {
            int same = findSlot(menu, s -> ItemStack.isSameItemSameComponents(s, current));
            return current.getCount() >= current.getMaxStackSize() ? -1 : same;
        }
        int s = findSlot(menu, st -> st.is(Items.COAL) || st.is(Items.CHARCOAL));
        if (s < 0) s = findSlot(menu, st -> st.is(ItemTags.PLANKS));
        if (s < 0) s = findSlot(menu, st -> st.is(ItemTags.LOGS_THAT_BURN));
        if (s < 0) s = findSlot(menu, st -> st.is(Items.STICK));
        return s;
    }
}
