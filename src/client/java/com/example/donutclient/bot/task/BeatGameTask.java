package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Ing;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.Reflex;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.AbstractCow;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** The whole run: an ordered checklist; the first unmet item is worked on. */
public final class BeatGameTask extends Task {
    private record Stage(String name, BooleanSupplier done, Supplier<Task> make) {}

    private static final Ing[] IRON_GEAR = {Ing.IRON_PICK, Ing.BUCKET, Ing.IRON_SWORD, Ing.SHIELD, Ing.FLINT_STEEL,
            Ing.CHESTPLATE, Ing.LEGGINGS, Ing.HELMET, Ing.BOOTS};

    private static final int EYES_NEEDED = 12;

    private final List<Stage> stages = new ArrayList<>();
    private Stage current;
    private Task task;

    public BeatGameTask(Bot bot) {
        super(bot);
        var inv = bot.inv;
        var gear = bot.gear;

        stage("recover items", () -> !RecoverItemsTask.pending(bot), () -> new RecoverItemsTask(bot));
        stage("gather wood", () -> !inOverworld() || woodUnits() >= 40 || gear.pickTier() >= 1, () -> wood(12));
        stage("wooden pickaxe", () -> !inOverworld() || gear.pickTier() >= 1, () -> new CraftTask(bot).want(Ing.WOOD_PICK, 1));
        stage("wood reserve", () -> !inOverworld() || woodUnits() >= 6 || (gear.pickTier() >= 3 && nextGear() == null && gear.ironNeeded() == 0),
                () -> wood(5));
        stage("mine stone", () -> !inOverworld() || inv.count(Ing.COBBLE.pred) >= 12 || (gear.pickTier() >= 2 && hasWeapon()),
                () -> stone(24));
        stage("stone tools", () -> !inOverworld() || (gear.pickTier() >= 2 && hasWeapon()),
                () -> new CraftTask(bot).want(Ing.STONE_PICK, 1).want(Ing.STONE_SWORD, 1).want(Ing.STONE_AXE, 1));
        stage("spare pickaxe", () -> inv.count(ItemTags.PICKAXES) >= 2
                        || !new CraftTask(bot).want(Ing.STONE_PICK, inv.count(Items.STONE_PICKAXE) + 1).feasible(),
                () -> new CraftTask(bot).want(Ing.STONE_PICK, inv.count(Items.STONE_PICKAXE) + 1));
        stage("food", () -> !inOverworld() || foodCount() >= 6,
                () -> new HuntTask(bot, "animals", BeatGameTask::isLivestock, BeatGameTask::isMeat, () -> foodCount() >= 20));
        stage("iron ore", () -> !inOverworld() || gear.ironOwned() >= gear.ironNeeded(),
                () -> ore("iron ore", s -> s.is(Blocks.IRON_ORE) || s.is(Blocks.DEEPSLATE_IRON_ORE),
                        s -> s.is(Items.RAW_IRON), () -> gear.ironOwned() >= gear.ironNeeded()));
        stage("fuel", () -> !inOverworld() || fuelUnits() >= smeltables(),
                () -> ore("coal", s -> s.is(Blocks.COAL_ORE) || s.is(Blocks.DEEPSLATE_COAL_ORE),
                        s -> s.is(Items.COAL), () -> fuelUnits() >= smeltables() + 8));
        stage("smelt iron", () -> !inOverworld() || inv.count(Items.RAW_IRON) == 0,
                () -> new SmeltTask(bot, "iron", s -> s.is(Items.RAW_IRON)));
        stage("cook food", () -> !inOverworld() || inv.count(BeatGameTask::isRawMeat) == 0,
                () -> new SmeltTask(bot, "food", BeatGameTask::isRawMeat));
        stage("flint", () -> !inOverworld() || gear.has(Items.FLINT_AND_STEEL) || inv.has(Items.FLINT),
                () -> {
                    MineTask t = new MineTask(bot, "gravel (for flint)", s -> s.is(Blocks.GRAVEL), s -> s.is(Items.FLINT),
                            () -> inv.has(Items.FLINT), true);
                    t.scanRadius = 8;
                    return t;
                });
        stage("wood for gear", () -> !inOverworld() || gear.has(Items.SHIELD) || woodUnits() >= 10, () -> wood(4));
        stage("craft iron gear", () -> nextGear() == null, () -> {
            Ing g = nextGear();
            int have = inv.count(g.pred);
            return new CraftTask(bot).want(g, have + 1);
        });
        stage("equip", () -> !EquipTask.needed(bot), () -> new EquipTask(bot));
        stage("spare pickaxes for the nether", () -> !inOverworld() || !needNether() || inv.count(ItemTags.PICKAXES) >= 5
                        || !new CraftTask(bot).want(Ing.STONE_PICK, inv.count(Items.STONE_PICKAXE) + 1).feasible(),
                () -> new CraftTask(bot).want(Ing.STONE_PICK, inv.count(Items.STONE_PICKAXE) + Math.max(1, 5 - inv.count(ItemTags.PICKAXES))));
        stage("building blocks", () -> inv.countThrowaway() >= 64 || !inOverworld() || !needNether(),
                () -> stone(inv.count(Ing.COBBLE.pred) + 96));
        stage("water bucket", () -> inv.has(Items.WATER_BUCKET) || !inOverworld() || !needNether(),
                () -> new FetchFluidTask(bot, false));
        stage("enter the nether", () -> !inOverworld() || !needNether(), () -> new PortalTask(bot));
        stage("blaze rods", () -> !inNether() || powderPossible() >= EYES_NEEDED,
                () -> new NetherGatherTask(bot, NetherGatherTask.Kind.BLAZE_RODS, 7));
        stage("ender pearls", () -> !inNether() || inv.count(Items.ENDER_PEARL) + inv.count(Items.ENDER_EYE) >= EYES_NEEDED,
                () -> new NetherGatherTask(bot, NetherGatherTask.Kind.PEARLS, 14 - inv.count(Items.ENDER_EYE)));
        stage("leave the nether", () -> !inNether(), () -> new NetherReturnTask(bot));
        stage("craft eyes of ender", () -> !inOverworld() || bot.memory.portalFilled
                        || !new CraftTask(bot).want(Ing.EYE, inv.count(Items.ENDER_EYE) + 1).feasible(),
                () -> new CraftTask(bot).want(Ing.EYE, inv.count(Items.ENDER_EYE)
                        + Math.min(inv.count(Items.ENDER_PEARL), inv.count(Items.BLAZE_POWDER) + 2 * inv.count(Items.BLAZE_ROD))));
        stage("blocks for the End", () -> !inOverworld() || inv.countThrowaway() >= 160,
                () -> stone(inv.count(Ing.COBBLE.pred) + 128));
        stage("stronghold", () -> !inOverworld() || bot.memory.dragonDead, () -> new StrongholdTask(bot));
        stage("ender dragon", () -> bot.level().dimension() != Level.END, () -> new EndTask(bot));
    }

    // ---- helpers ------------------------------------------------------------------

    private boolean inOverworld() { return bot.level().dimension() == Level.OVERWORLD; }

    private boolean inNether() { return bot.level().dimension() == Level.NETHER; }

    /** Eyes of ender the bot could make right now. */
    private int eyesPossible() {
        var inv = bot.inv;
        int powder = inv.count(Items.BLAZE_POWDER) + 2 * inv.count(Items.BLAZE_ROD);
        return inv.count(Items.ENDER_EYE) + Math.min(powder, inv.count(Items.ENDER_PEARL));
    }

    private int powderPossible() {
        return bot.inv.count(Items.ENDER_EYE) + bot.inv.count(Items.BLAZE_POWDER) + 2 * bot.inv.count(Items.BLAZE_ROD);
    }

    private boolean needNether() {
        if (bot.memory.portalFilled || bot.memory.dragonDead) return false;
        int needed = bot.memory.framesMissing >= 0 ? bot.memory.framesMissing : EYES_NEEDED;
        return eyesPossible() < needed;
    }

    private Ing nextGear() {
        var gear = bot.gear;
        for (Ing g : IRON_GEAR) {
            boolean missing;
            if (g == Ing.IRON_PICK) missing = bot.inv.count(Items.IRON_PICKAXE) < 2;
            else if (g == Ing.BUCKET) missing = gear.buckets() < 2;
            else if (g == Ing.IRON_SWORD) missing = !gear.has(Items.IRON_SWORD);
            else if (g == Ing.SHIELD) missing = !gear.has(Items.SHIELD);
            else if (g == Ing.FLINT_STEEL) missing = !gear.has(Items.FLINT_AND_STEEL);
            else if (g == Ing.CHESTPLATE) missing = !gear.has(Items.IRON_CHESTPLATE);
            else if (g == Ing.LEGGINGS) missing = !gear.has(Items.IRON_LEGGINGS);
            else if (g == Ing.HELMET) missing = !gear.has(Items.IRON_HELMET);
            else missing = !gear.has(Items.IRON_BOOTS);
            if (!missing) continue;
            if (new CraftTask(bot).want(g, bot.inv.count(g.pred) + 1).feasible()) return g;
        }
        return null;
    }

    private int woodUnits() {
        return bot.inv.count(ItemTags.LOGS) * 4 + bot.inv.count(ItemTags.PLANKS) + bot.inv.count(Items.STICK) / 2;
    }

    private boolean hasWeapon() {
        return bot.inv.count(ItemTags.SWORDS) > 0;
    }

    private int foodCount() { return bot.inv.count(Reflex::isGoodFood) + bot.inv.count(Items.CHICKEN); }

    private int smeltables() { return bot.inv.count(Items.RAW_IRON) + bot.inv.count(BeatGameTask::isRawMeat); }

    private int fuelUnits() {
        var inv = bot.inv;
        return inv.count(Items.COAL) * 8 + inv.count(Items.CHARCOAL) * 8
                + (int) ((Math.max(0, inv.count(ItemTags.PLANKS) - 12) + Math.max(0, inv.count(ItemTags.LOGS) - 2) * 4) * 1.5);
    }

    private static boolean isLivestock(Entity e) {
        return e instanceof AbstractCow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken;
    }

    private static boolean isRawMeat(ItemStack s) {
        return s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN)
                || s.is(Items.RABBIT) || s.is(Items.COD) || s.is(Items.SALMON);
    }

    private static boolean isMeat(ItemStack s) {
        return isRawMeat(s) || s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON)
                || s.is(Items.COOKED_CHICKEN);
    }

    private Task wood(int logs) {
        int target = bot.inv.count(ItemTags.LOGS) + logs;
        MineTask t = new MineTask(bot, "logs", s -> s.is(BlockTags.LOGS), s -> s.is(ItemTags.LOGS),
                () -> bot.inv.count(ItemTags.LOGS) >= target, false);
        t.minY = 50;
        t.where = this::isTreeLog; // real trees, not mineshaft or outpost timber
        t.hint = () -> Locate.biome(bot, b -> b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA)
                || b.is(BiomeTags.IS_JUNGLE), 3000);
        return t;
    }

    private Task stone(int count) {
        MineTask t = new MineTask(bot, "stone", BeatGameTask::isStone, Ing.COBBLE.pred,
                () -> bot.inv.count(Ing.COBBLE.pred) >= count, true);
        t.scanRadius = 2;
        return t;
    }

    private static boolean isStone(BlockState s) {
        return s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE);
    }

    private Task ore(String name, java.util.function.Predicate<BlockState> block,
                     java.util.function.Predicate<ItemStack> drop, BooleanSupplier done) {
        MineTask t = new MineTask(bot, name, block, drop, done, true);
        t.scanRadius = 8;
        return t;
    }

    private boolean isTreeLog(BlockPos p) {
        BlockPos.MutableBlockPos m = p.mutable();
        for (int i = 0; i < 12; i++) {
            m.move(0, 1, 0);
            var st = bot.level().getBlockState(m);
            if (st.is(BlockTags.LEAVES)) return true;
            if (!st.is(BlockTags.LOGS)) return false;
        }
        return false;
    }

    private void stage(String name, BooleanSupplier done, Supplier<Task> make) {
        stages.add(new Stage(name, done, make));
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        if (task != null) task.onInterrupted();
        current = null;
        task = null;
    }

    /** Detects the bot spinning its wheels: nothing gained, nowhere gone, for minutes. */
    private boolean stalled() {
        long h = bot.level().dimension().hashCode();
        BlockPos f = bot.feet();
        h = h * 31 + (f.getX() >> 3);
        h = h * 31 + (f.getY() >> 3);
        h = h * 31 + (f.getZ() >> 3);
        for (int i = 0; i < 36; i++) {
            ItemStack s = bot.inv.get(i);
            h = h * 31 + (s.isEmpty() ? 0 : s.getItem().hashCode() + s.getCount() * 7L + s.getDamageValue());
        }
        h = h * 31 + (long) bot.player().getHealth();
        if (h != stallHash || bot.heartbeat > stallSince) {
            stallHash = h;
            stallSince = bot.tick;
            return false;
        }
        return bot.tick - stallSince > 20 * 60 * 4;
    }

    private long stallHash, stallSince;
    private Goal unstick;
    private int unstickTicks;

    @Override
    public boolean tick() {
        if (unstick != null) {
            bot.setStatus("shaking myself loose");
            if (++unstickTicks > 600 || bot.nav.goTo(unstick) != Nav.Status.RUNNING) unstick = null;
            return false;
        }
        if (bot.tick % 100 == 0 && stalled()) {
            bot.say("I seem to be stuck on '" + (current == null ? "?" : current.name) + "'. Trying something else.");
            if (task != null) task.onInterrupted();
            task = null;
            if (!bot.inv.menuIsInventory()) bot.player().closeContainer();
            bot.nav.reset();
            BlockPos f = bot.feet();
            double ang = (bot.tick % 628) / 100.0;
            unstick = Goal.xz(f.getX() + (int) (Math.cos(ang) * 24), f.getZ() + (int) (Math.sin(ang) * 24), 3);
            unstickTicks = 0;
            stallSince = bot.tick;
            return false;
        }
        if (task != null) {
            // A more basic need may have reappeared (tool broke, out of food...).
            if (bot.tick % 60 == 0 && task.interruptible()) {
                for (Stage s : stages) {
                    if (s == current) break;
                    if (!s.done.getAsBoolean()) {
                        task.onInterrupted();
                        task = null;
                        break;
                    }
                }
            }
            if (task != null) {
                // Otherwise let the task finish its own cleanup (closing menus, picking up tables).
                if (!task.tick()) return false;
                task = null;
            }
        }
        for (Stage s : stages) {
            if (s.done.getAsBoolean()) continue;
            if (s != current) bot.say("Stage: " + s.name);
            current = s;
            bot.nav.reset();
            task = s.make.get();
            return false;
        }
        return bot.memory.dragonDead;
    }
}
