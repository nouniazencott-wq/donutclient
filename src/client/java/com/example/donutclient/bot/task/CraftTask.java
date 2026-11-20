package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Ing;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Crafts the requested items, resolving intermediate recipes, placing and
 * picking up a crafting table when a 3x3 grid is needed.
 */
public final class CraftTask extends Task {
    private record Step(Ing ing, int times) {}

    private final List<Object[]> wants = new ArrayList<>();
    private List<Step> steps;
    private int stepIdx;
    private int stepTarget = -1;
    private int wait;
    private int failures;
    private boolean placed;
    private boolean waitingResult;
    private BlockPos tablePos;
    private boolean ownTable;
    private int openWait;
    private boolean finishing;
    private final PickupBlockTask[] pickup = new PickupBlockTask[1];
    public boolean failed;
    private int totalTicks;

    public CraftTask(Bot bot) { super(bot); }

    /** Ask for the inventory to contain at least total of ing. */
    public CraftTask want(Ing ing, int total) {
        wants.add(new Object[]{ing, total});
        return this;
    }

    /** True if everything requested could be crafted from the current inventory. */
    public boolean feasible() { return plan() != null; }

    private List<Step> plan() {
        Map<Ing, Integer> have = new HashMap<>();
        List<Step> out = new ArrayList<>();
        for (Object[] w : wants) {
            if (!plan((Ing) w[0], (Integer) w[1], have, out)) return null;
        }
        boolean needTable = false;
        for (Step s : out) if (!s.ing.recipe.fits2x2()) needTable = true;
        if (needTable && findTable() == null && bot.inv.count(Items.CRAFTING_TABLE) == 0) {
            List<Step> pre = new ArrayList<>();
            Map<Ing, Integer> have2 = new HashMap<>();
            if (!plan(Ing.TABLE, 1, have2, pre)) return null;
            // Re-plan the rest with the table's cost already taken out.
            List<Step> rest = new ArrayList<>();
            for (Object[] w : wants) {
                if (!plan((Ing) w[0], (Integer) w[1], have2, rest)) return null;
            }
            pre.addAll(rest);
            return pre;
        }
        return out;
    }

    private boolean plan(Ing ing, int want, Map<Ing, Integer> have, List<Step> out) {
        int cur = have.computeIfAbsent(ing, i -> bot.inv.count(i.pred));
        if (cur >= want) return true;
        if (ing.recipe == null) return false;
        int times = (want - cur + ing.recipe.out() - 1) / ing.recipe.out();
        for (Map.Entry<Ing, Integer> e : ing.recipe.inputs().entrySet()) {
            int need = e.getValue() * times;
            if (!plan(e.getKey(), need, have, out)) return false;
            have.merge(e.getKey(), -need, Integer::sum);
        }
        have.merge(ing, times * ing.recipe.out(), Integer::sum);
        out.add(new Step(ing, times));
        return true;
    }

    private BlockPos findTable() {
        BlockPos f = bot.feet();
        BlockPos best = null;
        double bestD = 4.2;
        for (BlockPos p : BlockPos.betweenClosed(f.offset(-4, -3, -4), f.offset(4, 4, 4))) {
            if (bot.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                double d = bot.act.eyeDist(p);
                if (d < bestD) { bestD = d; best = p.immutable(); }
            }
        }
        return best;
    }

    private void click(int slot, int button, ContainerInput type) {
        LocalPlayer p = bot.player();
        bot.mc.gameMode.handleContainerInput(p.containerMenu.containerId, slot, button, type, p);
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        steps = null;
        stepIdx = 0;
        stepTarget = -1;
        placed = false;
        waitingResult = false;
    }

    @Override
    public boolean interruptible() { return false; }

    @Override
    public boolean tick() {
        LocalPlayer p = bot.player();
        if (busy()) return false;
        if (++totalTicks > 1500 && !finishing) {
            bot.dbg("craft task timed out");
            failed = true;
            finishing = true;
        }
        if (totalTicks > 2400) return true;
        if (finishing) return finish();
        if (steps == null) {
            steps = plan();
            if (steps == null) {
                failed = true;
                bot.setStatus("craft: missing materials for " + wants.get(0)[0]);
                finishing = true;
                return false;
            }
            stepIdx = 0;
        }
        if (stepIdx >= steps.size()) { finishing = true; return false; }
        Step step = steps.get(stepIdx);
        Ing.Recipe r = step.ing.recipe;
        bot.setStatus("crafting " + step.ing);
        if (wait > 0) { wait--; return false; }

        boolean needTable = !r.fits2x2();
        AbstractContainerMenu menu = p.containerMenu;
        if (needTable && !(menu instanceof CraftingMenu)) {
            if (!openTable()) { failed = true; finishing = true; }
            return false;
        }
        if (stepTarget < 0) stepTarget = bot.inv.count(step.ing.pred) + step.times * r.out();
        int width = menu instanceof CraftingMenu ? 3 : 2;

        if (waitingResult) {
            ItemStack res = menu.getSlot(0).getItem();
            if (!res.isEmpty() && step.ing.pred.test(res)) {
                click(0, 0, ContainerInput.QUICK_MOVE);
                wait = 2;
                return false;
            }
            if (++openWait > 25 || gridEmpty(menu, width)) {
                // Nothing (more) to take: return leftovers and evaluate.
                for (int i = 1; i <= width * width; i++) {
                    if (!menu.getSlot(i).getItem().isEmpty()) click(i, 0, ContainerInput.QUICK_MOVE);
                }
                waitingResult = false;
                placed = false;
                if (bot.inv.count(step.ing.pred) >= stepTarget) {
                    stepIdx++;
                    stepTarget = -1;
                    failures = 0;
                } else if (++failures > 4) {
                    failed = true;
                    finishing = true;
                }
                wait = 2;
            }
            return false;
        }

        // Lay out one batch of ingredients.
        int remaining = stepTarget - bot.inv.count(step.ing.pred);
        int n = Math.min((remaining + r.out() - 1) / r.out(), 64);
        if (n <= 0) { stepIdx++; stepTarget = -1; return false; }
        if (!menu.getCarried().isEmpty()) { dropCarried(menu); return false; }
        Map<Ing, List<Integer>> cells = new HashMap<>();
        String[] pat = r.pattern();
        for (int row = 0; row < pat.length; row++) {
            for (int col = 0; col < pat[row].length(); col++) {
                char c = pat[row].charAt(col);
                if (c != ' ') cells.computeIfAbsent(r.key().get(c), k -> new ArrayList<>()).add(1 + row * width + col);
            }
        }
        for (Map.Entry<Ing, List<Integer>> e : cells.entrySet()) {
            for (int guard = 0; guard < 12; guard++) {
                boolean full = true;
                for (int cell : e.getValue()) if (menu.getSlot(cell).getItem().getCount() < n) full = false;
                if (full) break;
                int src = findSource(menu, e.getKey(), e.getValue(), n);
                if (src < 0) break;
                click(src, 0, ContainerInput.PICKUP);
                for (int cell : e.getValue()) {
                    while (!menu.getCarried().isEmpty() && menu.getSlot(cell).getItem().getCount() < n
                            && (menu.getSlot(cell).getItem().isEmpty()
                            || ItemStack.isSameItemSameComponents(menu.getSlot(cell).getItem(), menu.getCarried()))) {
                        click(cell, 1, ContainerInput.PICKUP);
                    }
                }
                if (!menu.getCarried().isEmpty()) click(src, 0, ContainerInput.PICKUP);
            }
        }
        waitingResult = true;
        openWait = 0;
        wait = 3;
        return false;
    }

    private boolean gridEmpty(AbstractContainerMenu menu, int width) {
        for (int i = 1; i <= width * width; i++) if (!menu.getSlot(i).getItem().isEmpty()) return false;
        return true;
    }

    private void dropCarried(AbstractContainerMenu menu) {
        for (Slot s : menu.slots) {
            if (s.container == bot.player().getInventory() && s.getContainerSlot() < 36 && s.getItem().isEmpty()) {
                click(s.index, 0, ContainerInput.PICKUP);
                return;
            }
        }
        click(-999, 0, ContainerInput.PICKUP);
    }

    private int findSource(AbstractContainerMenu menu, Ing ing, List<Integer> cells, int n) {
        int best = -1, bestCount = 0;
        for (Slot s : menu.slots) {
            if (s.container != bot.player().getInventory() || s.getContainerSlot() >= 36) continue;
            ItemStack st = s.getItem();
            if (st.isEmpty() || !ing.pred.test(st)) continue;
            boolean useful = false;
            for (int cell : cells) {
                ItemStack cs = menu.getSlot(cell).getItem();
                if (cs.getCount() < n && (cs.isEmpty() || ItemStack.isSameItemSameComponents(cs, st))) useful = true;
            }
            if (useful && st.getCount() > bestCount) { bestCount = st.getCount(); best = s.index; }
        }
        return best;
    }

    /** Works toward having a crafting table menu open. Returns false if that is impossible. */
    private boolean openTable() {
        LocalPlayer p = bot.player();
        if (tablePos != null && !bot.level().getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) tablePos = null;
        if (tablePos == null) tablePos = findTable();
        if (tablePos == null) {
            if (!p.onGround() && !p.isInWater()) return true;
            if (bot.inv.count(Items.CRAFTING_TABLE) == 0) return false;
            BlockPos spot = PlaceSpot.find(bot);
            if (spot == null) {
                // Boxed in: open up a side block at head height.
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos c = bot.feet().above().relative(d);
                    int f = Blocks2.flags(bot.level(), c, bot.level().getBlockState(c));
                    if ((f & Blocks2.BREAK) != 0) { bot.act.breakBlock(c); return true; }
                }
                return false;
            }
            if (bot.inv.hold(Items.CRAFTING_TABLE)) {
                if (bot.act.placeHeld(spot, false)) { tablePos = spot; ownTable = true; }
                else if (bot.level().getBlockState(spot).is(Blocks.CRAFTING_TABLE)) { tablePos = spot; ownTable = true; }
            }
            return true;
        }
        ownTable |= false;
        if (++openWait > 200) { openWait = 0; return false; }
        if (bot.inv.menuIsInventory()) bot.act.useOn(tablePos, Direction.UP);
        return true;
    }

    private boolean finish() {
        LocalPlayer p = bot.player();
        if (!bot.inv.menuIsInventory()) {
            if (!p.containerMenu.getCarried().isEmpty()) { dropCarried(p.containerMenu); return false; }
            p.closeContainer();
            return false;
        }
        if (ownTable && tablePos != null) {
            if (pickup[0] == null) pickup[0] = new PickupBlockTask(bot, tablePos, Items.CRAFTING_TABLE);
            if (!pickup[0].tick()) return false;
        }
        return true;
    }
}
