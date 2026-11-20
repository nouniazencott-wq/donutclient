package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Ing;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Crafts one or more wanted ingredients, including every intermediate item that is missing.
 *
 * <p>The task plans a list of crafting steps from the current inventory, then executes them by clicking the
 * real container slots: 2x2 recipes in the player inventory, everything else in a crafting table (an existing one
 * within reach, or one placed from the inventory and picked up again afterwards).
 */
public final class CraftTask extends Task {
    /** One planned craft: the ingredient to make and how many times its recipe has to be run. */
    private record Step(Ing ing, int times) {
    }

    /** Requested outputs as {@code {Ing, Integer total}} pairs. */
    private final List<Object[]> wants = new ArrayList<>();
    private List<Step> steps;
    private int stepIdx;
    /** Inventory count of the current step's item that means the step is finished; -1 = not computed yet. */
    private int stepTarget = -1;
    private int wait;
    private int failures;
    private boolean placed;
    /** True after the grid was filled and we are waiting for the result slot to show the item. */
    private boolean waitingResult;
    private BlockPos tablePos;
    /** True when the table at {@link #tablePos} was placed by this task and should be picked up again. */
    private boolean ownTable;
    private int openWait;
    /** Set once crafting is over (success or failure): close the menu and recover our table. */
    private boolean finishing;
    private final PickupBlockTask[] pickup = new PickupBlockTask[1];
    /** True when the task ended without producing everything that was asked for. */
    public boolean failed;
    private int totalTicks;
    /** Clicks on a result slot that would not give its item up (inventory full). */
    private int takeTries;
    /** Inventory count of the current step's item when the grid was last filled. */
    private int countAtLayout;
    /** Ticks spent trying to put our crafting table down. */
    private int placeTicks;
    /** Clicks spent on moving leftovers out of the 2x2 inventory grid during cleanup. */
    private int cleanupClicks;

    public CraftTask(Bot bot) {
        super(bot);
    }

    /** Asks for {@code total} of {@code ing} to be in the inventory when the task is done. */
    public CraftTask want(Ing ing, int total) {
        wants.add(new Object[] {ing, total});
        return this;
    }

    /** Whether everything wanted can be crafted from what is in the inventory right now. */
    public boolean feasible() {
        return plan() != null;
    }

    /**
     * Builds the ordered step list for all wants, or null when materials are missing. If some step needs a 3x3
     * grid and there is neither a table nearby nor one in the inventory, a table is planned first and the rest is
     * re-planned with the materials that remain.
     */
    private List<Step> plan() {
        Map<Ing, Integer> have = new HashMap<>();
        List<Step> out = new ArrayList<>();
        for (Object[] w : wants) {
            if (!plan((Ing) w[0], (Integer) w[1], have, out)) {
                return null;
            }
        }
        boolean needTable = false;
        for (Step s : out) {
            if (!s.ing.recipe.fits2x2()) {
                needTable = true;
            }
        }
        if (needTable && findTable() == null && bot.inv.count(Items.CRAFTING_TABLE) == 0) {
            List<Step> pre = new ArrayList<>();
            Map<Ing, Integer> have2 = new HashMap<>();
            if (!plan(Ing.TABLE, 1, have2, pre)) {
                return null;
            }
            List<Step> rest = new ArrayList<>();
            for (Object[] w : wants) {
                if (!plan((Ing) w[0], (Integer) w[1], have2, rest)) {
                    return null;
                }
            }
            pre.addAll(rest);
            return pre;
        }
        return out;
    }

    /**
     * Recursive planner: makes sure {@code want} of {@code ing} is available in the simulated inventory
     * {@code have}, appending the necessary steps (inputs first) to {@code out}.
     */
    private boolean plan(Ing ing, int want, Map<Ing, Integer> have, List<Step> out) {
        int cur = have.computeIfAbsent(ing, i -> bot.inv.count(i.pred));
        if (cur >= want) {
            return true;
        }
        if (ing.recipe == null) {
            return false;
        }
        int times = (want - cur + ing.recipe.out() - 1) / ing.recipe.out();
        for (Map.Entry<Ing, Integer> e : ing.recipe.inputs().entrySet()) {
            int need = e.getValue() * times;
            if (!plan(e.getKey(), need, have, out)) {
                return false;
            }
            have.merge(e.getKey(), -need, Integer::sum);
        }
        have.merge(ing, times * ing.recipe.out(), Integer::sum);
        out.add(new Step(ing, times));
        return true;
    }

    /** Nearest crafting table within reach (4.2 blocks from the eyes), or null. */
    private BlockPos findTable() {
        BlockPos f = bot.feet();
        BlockPos best = null;
        double bestD = 4.2;
        for (BlockPos p : BlockPos.betweenClosed(f.offset(-4, -3, -4), f.offset(4, 4, 4))) {
            if (bot.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                double d = bot.act.eyeDist(p);
                if (d < bestD) {
                    bestD = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    /** Sends one slot click in the currently open container. */
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
        // Never leave a crafting screen open behind us; the server puts grid and cursor items back.
        if (bot.player() != null && !bot.inv.menuIsInventory()) {
            bot.player().closeContainer();
        }
    }

    @Override
    public boolean interruptible() {
        return false;
    }

    @Override
    public boolean tick() {
        LocalPlayer p = bot.player();
        if (busy()) {
            return false;
        }
        // Soft timeout: give up crafting and start cleaning up. Hard timeout: end the task no matter what.
        if (++totalTicks > 1500 && !finishing) {
            bot.dbg("craft task timed out");
            failed = true;
            finishing = true;
        }
        if (totalTicks > 2400) {
            // Do not walk off with the crafting screen still open (closing it returns grid and cursor items).
            if (!bot.inv.menuIsInventory()) {
                p.closeContainer();
            }
            return true;
        }
        if (finishing) {
            return finish();
        }
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
        if (stepIdx >= steps.size()) {
            finishing = true;
            return false;
        }
        Step step = steps.get(stepIdx);
        Ing.Recipe r = step.ing.recipe;
        bot.setStatus("crafting " + step.ing);
        if (wait > 0) {
            wait--;
            return false;
        }
        boolean needTable = !r.fits2x2();
        AbstractContainerMenu menu = p.containerMenu;
        if (needTable && !(menu instanceof CraftingMenu)) {
            if (!openTable()) {
                failed = true;
                finishing = true;
            }
            return false;
        }
        if (stepTarget < 0) {
            stepTarget = bot.inv.count(step.ing.pred) + step.times * r.out();
        }
        // Grid slots are 1..width*width in both menus; slot 0 is the result.
        int width = menu instanceof CraftingMenu ? 3 : 2;
        if (waitingResult) {
            ItemStack res = menu.getSlot(0).getItem();
            if (!res.isEmpty() && step.ing.pred.test(res)) {
                // One shift-click normally takes everything; if the result still sits there after many, the
                // inventory has no room for it - stop instead of clicking until the timeout.
                if (++takeTries > 10) {
                    bot.dbg("craft: no room for " + step.ing);
                    failed = true;
                    finishing = true;
                    return false;
                }
                click(0, 0, ContainerInput.QUICK_MOVE);
                wait = 2;
                return false;
            }
            // No (more) result: once the grid is used up, or after a short timeout, clear it and re-evaluate.
            if (++openWait > 25 || gridEmpty(menu, width)) {
                for (int i = 1; i <= width * width; i++) {
                    if (!menu.getSlot(i).getItem().isEmpty()) {
                        click(i, 0, ContainerInput.QUICK_MOVE);
                    }
                }
                waitingResult = false;
                placed = false;
                if (bot.inv.count(step.ing.pred) >= stepTarget) {
                    stepIdx++;
                    stepTarget = -1;
                    failures = 0;
                } else if (bot.inv.count(step.ing.pred) > countAtLayout) {
                    // A partial batch came out: that is progress, not a failure.
                    failures = 0;
                } else if (++failures > 4) {
                    failed = true;
                    finishing = true;
                }
                wait = 2;
            }
            return false;
        }
        int remaining = stepTarget - bot.inv.count(step.ing.pred);
        // Number of recipe runs to lay out at once (items per grid cell).
        int n = Math.min((remaining + r.out() - 1) / r.out(), stackLimit(r));
        if (n <= 0) {
            stepIdx++;
            stepTarget = -1;
            return false;
        }
        if (!menu.getCarried().isEmpty()) {
            dropCarried(menu);
            return false;
        }
        // Group the grid cells by the ingredient that goes into them.
        Map<Ing, List<Integer>> cells = new HashMap<>();
        String[] pat = r.pattern();
        for (int row = 0; row < pat.length; row++) {
            for (int col = 0; col < pat[row].length(); col++) {
                char c = pat[row].charAt(col);
                if (c != ' ') {
                    cells.computeIfAbsent(r.key().get(c), k -> new ArrayList<>()).add(1 + row * width + col);
                }
            }
        }
        for (Map.Entry<Ing, List<Integer>> e : cells.entrySet()) {
            // Each pass picks up one inventory stack and deals it out one item at a time (right clicks).
            for (int guard = 0; guard < 12; guard++) {
                boolean full = true;
                for (int cell : e.getValue()) {
                    if (menu.getSlot(cell).getItem().getCount() < n) {
                        full = false;
                    }
                }
                if (full) {
                    break;
                }
                int src = findSource(menu, e.getKey(), e.getValue(), n);
                if (src < 0) {
                    break;
                }
                click(src, 0, ContainerInput.PICKUP);
                for (int cell : e.getValue()) {
                    // (bounded: a click that places nothing must not turn this into an endless loop)
                    for (int k = 0; k < 64 && !menu.getCarried().isEmpty() && menu.getSlot(cell).getItem().getCount() < n
                            && (menu.getSlot(cell).getItem().isEmpty()
                                    || ItemStack.isSameItemSameComponents(menu.getSlot(cell).getItem(), menu.getCarried())); k++) {
                        click(cell, 1, ContainerInput.PICKUP);
                    }
                }
                if (!menu.getCarried().isEmpty()) {
                    click(src, 0, ContainerInput.PICKUP);
                }
            }
        }
        waitingResult = true;
        takeTries = 0;
        countAtLayout = bot.inv.count(step.ing.pred);
        openWait = 0;
        wait = 3;
        return false;
    }

    /**
     * The most items one grid cell can take for this recipe: the smallest stack size among the ingredients carried
     * (ender pearls only stack to 16). Asking a cell for more could never be satisfied.
     */
    private int stackLimit(Ing.Recipe r) {
        int limit = 64;
        for (Ing in : r.key().values()) {
            for (int i = 0; i < 36; i++) {
                ItemStack st = bot.inv.get(i);
                if (!st.isEmpty() && in.pred.test(st)) {
                    limit = Math.min(limit, st.getMaxStackSize());
                }
            }
        }
        return Math.max(1, limit);
    }

    /** True when all {@code width x width} crafting grid slots are empty. */
    private boolean gridEmpty(AbstractContainerMenu menu, int width) {
        for (int i = 1; i <= width * width; i++) {
            if (!menu.getSlot(i).getItem().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets rid of the stack on the cursor: onto an inventory stack of the same item that has room (which may take
     * several calls), else into the first empty inventory slot, and only as a last resort thrown out of the window.
     */
    private void dropCarried(AbstractContainerMenu menu) {
        ItemStack carried = menu.getCarried();
        for (Slot s : menu.slots) {
            ItemStack st = s.getItem();
            if (s.container == bot.player().getInventory() && s.getContainerSlot() < 36 && !st.isEmpty()
                    && st.getCount() < st.getMaxStackSize() && ItemStack.isSameItemSameComponents(st, carried)) {
                click(s.index, 0, ContainerInput.PICKUP);
                return;
            }
        }
        for (Slot s : menu.slots) {
            if (s.container == bot.player().getInventory() && s.getContainerSlot() < 36 && s.getItem().isEmpty()) {
                click(s.index, 0, ContainerInput.PICKUP);
                return;
            }
        }
        click(-999, 0, ContainerInput.PICKUP);
    }

    /**
     * Picks the inventory slot to take {@code ing} from: the largest matching stack that can still go into at
     * least one of the unfilled {@code cells} (same item variant, or the cell is empty). Returns -1 if none.
     */
    private int findSource(AbstractContainerMenu menu, Ing ing, List<Integer> cells, int n) {
        int best = -1;
        int bestCount = 0;
        for (Slot s : menu.slots) {
            if (s.container != bot.player().getInventory() || s.getContainerSlot() >= 36) {
                continue;
            }
            ItemStack st = s.getItem();
            if (st.isEmpty() || !ing.pred.test(st)) {
                continue;
            }
            boolean useful = false;
            for (int cell : cells) {
                ItemStack cs = menu.getSlot(cell).getItem();
                if (cs.getCount() < n && (cs.isEmpty() || ItemStack.isSameItemSameComponents(cs, st))) {
                    useful = true;
                }
            }
            if (useful && st.getCount() > bestCount) {
                bestCount = st.getCount();
                best = s.index;
            }
        }
        return best;
    }

    /**
     * Works towards an open crafting table menu: finds or places a table, then right-clicks it.
     * Returns false when that is impossible (no table item, nowhere to place it, or it never opened).
     */
    private boolean openTable() {
        LocalPlayer p = bot.player();
        if (tablePos != null && !bot.level().getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
            tablePos = null;
        }
        if (tablePos == null) {
            tablePos = findTable();
        }
        if (tablePos == null) {
            if (!p.onGround() && !p.isInWater()) {
                return true;
            }
            if (bot.inv.count(Items.CRAFTING_TABLE) == 0) {
                return false;
            }
            BlockPos spot = PlaceSpot.find(bot);
            if (spot == null) {
                // Boxed in: dig out a head-height neighbour to make room for the table.
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos c = bot.feet().above().relative(d);
                    int f = Blocks2.flags(bot.level(), c, bot.level().getBlockState(c));
                    if ((f & Blocks2.BREAK) != 0) {
                        bot.act.breakBlock(c);
                        return true;
                    }
                }
                return false;
            }
            // Cannot get the table down here (no usable neighbour, something in the way): fail after 10 seconds
            // rather than clicking away until the task times out.
            if (++placeTicks > 200) {
                return false;
            }
            if (bot.inv.hold(Items.CRAFTING_TABLE)) {
                if (bot.act.placeHeld(spot, false)) {
                    tablePos = spot;
                    ownTable = true;
                } else if (bot.level().getBlockState(spot).is(Blocks.CRAFTING_TABLE)) {
                    tablePos = spot;
                    ownTable = true;
                }
            }
            return true;
        }
        ownTable |= false;
        if (++openWait > 200) {
            openWait = 0;
            return false;
        }
        if (bot.inv.menuIsInventory()) {
            bot.act.useOn(tablePos, Direction.UP);
        }
        return true;
    }

    /** Cleanup phase: empty the cursor, close the menu and break/collect our own table. True when done. */
    private boolean finish() {
        LocalPlayer p = bot.player();
        if (!bot.inv.menuIsInventory()) {
            if (!p.containerMenu.getCarried().isEmpty()) {
                dropCarried(p.containerMenu);
                return false;
            }
            p.closeContainer();
            return false;
        }
        // Leftovers in the 2x2 inventory grid (a craft that was cut short) are invisible to the rest of the bot:
        // shift-click them back into the inventory, one per tick, giving up after a few clicks if it is full.
        for (int i = 1; i <= 4 && cleanupClicks < 8; i++) {
            if (!p.inventoryMenu.getSlot(i).getItem().isEmpty()) {
                cleanupClicks++;
                click(i, 0, ContainerInput.QUICK_MOVE);
                return false;
            }
        }
        if (ownTable && tablePos != null) {
            if (pickup[0] == null) {
                pickup[0] = new PickupBlockTask(bot, tablePos, Items.CRAFTING_TABLE);
            }
            if (!pickup[0].tick()) {
                return false;
            }
        }
        return true;
    }
}
