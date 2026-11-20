package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Gear;
import com.example.donutclient.bot.Ing;
import com.example.donutclient.bot.Inv;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.Reflex;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
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

/**
 * The top-level plan for beating the game. It is an ordered list of stages, each with a "done" condition and a
 * factory for the task that makes it done. Every time a task is needed the first stage that is not done is run, so
 * the bot automatically falls back to earlier stages when it loses items (for example after dying).
 */
public final class BeatGameTask extends Task {
	/** Iron equipment in the order in which it gets crafted. */
	private static final Ing[] IRON_GEAR = {
			Ing.IRON_PICK, Ing.BUCKET, Ing.IRON_SWORD, Ing.SHIELD, Ing.FLINT_STEEL,
			Ing.CHESTPLATE, Ing.LEGGINGS, Ing.HELMET, Ing.BOOTS,
	};
	/** Eyes of ender needed to fill an end portal that has no eyes at all. */
	private static final int EYES_NEEDED = 12;

	private final List<Stage> stages = new ArrayList<>();
	/** The stage the running task belongs to. */
	private Stage current;
	private Task task;
	/** Fingerprint of position/inventory/health used to detect that nothing changes any more. */
	private long stallHash;
	/** Bot tick at which the fingerprint last changed. */
	private long stallSince;
	/** While non-null the bot walks to this random nearby goal to get out of a stall. */
	private Goal unstick;
	private int unstickTicks;

	public BeatGameTask(Bot bot) {
		super(bot);
		Inv inv = bot.inv;
		Gear gear = bot.gear;

		stage("recover items", () -> !RecoverItemsTask.pending(bot), () -> new RecoverItemsTask(bot));

		// --- overworld: tools ---
		stage("gather wood", () -> !inOverworld() || woodUnits() >= 40 || gear.pickTier() >= 1, () -> wood(12));
		stage("wooden pickaxe", () -> !inOverworld() || gear.pickTier() >= 1,
				() -> new CraftTask(bot).want(Ing.WOOD_PICK, 1));
		stage("wood reserve",
				() -> !inOverworld() || woodUnits() >= 6
						|| (gear.pickTier() >= 3 && nextGear() == null && gear.ironNeeded() == 0),
				() -> wood(5));
		stage("mine stone",
				() -> !inOverworld() || inv.count(Ing.COBBLE.pred) >= 12 || (gear.pickTier() >= 2 && hasWeapon()),
				() -> stone(24));
		stage("stone tools", () -> !inOverworld() || (gear.pickTier() >= 2 && hasWeapon()),
				() -> new CraftTask(bot).want(Ing.STONE_PICK, 1).want(Ing.STONE_SWORD, 1).want(Ing.STONE_AXE, 1));
		stage("spare pickaxe",
				() -> inv.count(ItemTags.PICKAXES) >= 2
						|| !new CraftTask(bot).want(Ing.STONE_PICK, inv.count(Items.STONE_PICKAXE) + 1).feasible(),
				() -> new CraftTask(bot).want(Ing.STONE_PICK, inv.count(Items.STONE_PICKAXE) + 1));
		stage("food", () -> !inOverworld() || foodCount() >= 6,
				() -> new HuntTask(bot, "animals", BeatGameTask::isLivestock, BeatGameTask::isMeat,
						() -> foodCount() >= 20));

		// --- overworld: iron ---
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
		stage("flint", () -> !inOverworld() || gear.has(Items.FLINT_AND_STEEL) || inv.has(Items.FLINT), () -> {
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

		// --- overworld: preparing for the nether ---
		stage("spare pickaxes for the nether",
				() -> !inOverworld() || !needNether() || inv.count(ItemTags.PICKAXES) >= 5
						|| !new CraftTask(bot).want(Ing.STONE_PICK, inv.count(Items.STONE_PICKAXE) + 1).feasible(),
				() -> new CraftTask(bot).want(Ing.STONE_PICK,
						inv.count(Items.STONE_PICKAXE) + Math.max(1, 5 - inv.count(ItemTags.PICKAXES))));
		stage("building blocks", () -> inv.countThrowaway() >= 64 || !inOverworld() || !needNether(),
				() -> stone(inv.count(Ing.COBBLE.pred) + 96));
		stage("water bucket", () -> inv.has(Items.WATER_BUCKET) || !inOverworld() || !needNether(),
				() -> new FetchFluidTask(bot, false));
		stage("enter the nether", () -> !inOverworld() || !needNether(), () -> new PortalTask(bot));

		// --- nether ---
		stage("blaze rods", () -> !inNether() || powderPossible() >= EYES_NEEDED,
				() -> new NetherGatherTask(bot, NetherGatherTask.Kind.BLAZE_RODS, 7));
		stage("ender pearls",
				() -> !inNether() || inv.count(Items.ENDER_PEARL) + inv.count(Items.ENDER_EYE) >= EYES_NEEDED,
				() -> new NetherGatherTask(bot, NetherGatherTask.Kind.PEARLS,
						EYES_NEEDED + 2 - inv.count(Items.ENDER_EYE)));
		stage("leave the nether", () -> !inNether(), () -> new NetherReturnTask(bot));

		// --- stronghold and the End ---
		stage("craft eyes of ender",
				() -> !inOverworld() || bot.memory.portalFilled
						|| !new CraftTask(bot).want(Ing.EYE, inv.count(Items.ENDER_EYE) + 1).feasible(),
				() -> new CraftTask(bot).want(Ing.EYE, inv.count(Items.ENDER_EYE) + Math.min(
						inv.count(Items.ENDER_PEARL),
						inv.count(Items.BLAZE_POWDER) + 2 * inv.count(Items.BLAZE_ROD))));
		stage("blocks for the End", () -> !inOverworld() || inv.countThrowaway() >= 160,
				() -> stone(inv.count(Ing.COBBLE.pred) + 128));
		stage("stronghold", () -> !inOverworld() || bot.memory.dragonDead, () -> new StrongholdTask(bot));
		stage("ender dragon", () -> bot.level().dimension() != Level.END, () -> new EndTask(bot));
	}

	private boolean inOverworld() {
		return bot.level().dimension() == Level.OVERWORLD;
	}

	private boolean inNether() {
		return bot.level().dimension() == Level.NETHER;
	}

	/** How many eyes of ender the bot owns or could craft from what it carries. */
	private int eyesPossible() {
		Inv inv = bot.inv;
		int powder = inv.count(Items.BLAZE_POWDER) + 2 * inv.count(Items.BLAZE_ROD);
		return inv.count(Items.ENDER_EYE) + Math.min(powder, inv.count(Items.ENDER_PEARL));
	}

	/** Eyes owned plus the blaze powder available for further eyes (ignores pearls). */
	private int powderPossible() {
		return bot.inv.count(Items.ENDER_EYE) + bot.inv.count(Items.BLAZE_POWDER)
				+ 2 * bot.inv.count(Items.BLAZE_ROD);
	}

	/** True while a nether trip is (still) required to get enough eyes of ender for the end portal. */
	private boolean needNether() {
		if (bot.memory.portalFilled || bot.memory.dragonDead) {
			return false;
		}
		// once the portal room has been seen the exact number of empty frames is known
		int needed = bot.memory.framesMissing >= 0 ? bot.memory.framesMissing : EYES_NEEDED;
		return eyesPossible() < needed;
	}

	/** The next missing piece of iron gear that can be crafted right now, or null. */
	private Ing nextGear() {
		Gear gear = bot.gear;
		for (Ing g : IRON_GEAR) {
			boolean missing;
			if (g == Ing.IRON_PICK) {
				missing = bot.inv.count(Items.IRON_PICKAXE) < 2;
			} else if (g == Ing.BUCKET) {
				missing = gear.buckets() < 2;
			} else if (g == Ing.IRON_SWORD) {
				missing = !gear.has(Items.IRON_SWORD);
			} else if (g == Ing.SHIELD) {
				missing = !gear.has(Items.SHIELD);
			} else if (g == Ing.FLINT_STEEL) {
				missing = !gear.has(Items.FLINT_AND_STEEL);
			} else if (g == Ing.CHESTPLATE) {
				missing = !gear.has(Items.IRON_CHESTPLATE);
			} else if (g == Ing.LEGGINGS) {
				missing = !gear.has(Items.IRON_LEGGINGS);
			} else if (g == Ing.HELMET) {
				missing = !gear.has(Items.IRON_HELMET);
			} else {
				missing = !gear.has(Items.IRON_BOOTS);
			}
			if (!missing) {
				continue;
			}
			if (new CraftTask(bot).want(g, bot.inv.count(g.pred) + 1).feasible()) {
				return g;
			}
		}
		return null;
	}

	/** Carried wood measured in planks (a log is 4 planks, two sticks are one plank). */
	private int woodUnits() {
		return bot.inv.count(ItemTags.LOGS) * 4 + bot.inv.count(ItemTags.PLANKS) + bot.inv.count(Items.STICK) / 2;
	}

	private boolean hasWeapon() {
		return bot.inv.count(ItemTags.SWORDS) > 0;
	}

	private int foodCount() {
		return bot.inv.count(Reflex::isGoodFood) + bot.inv.count(Items.CHICKEN);
	}

	/** Number of items waiting to be smelted (raw iron and raw meat). */
	private int smeltables() {
		return bot.inv.count(Items.RAW_IRON) + bot.inv.count(BeatGameTask::isRawMeat);
	}

	/** Number of items the carried fuel can smelt; keeps 12 planks and 2 logs back for crafting. */
	private int fuelUnits() {
		Inv inv = bot.inv;
		return inv.count(Items.COAL) * 8 + inv.count(Items.CHARCOAL) * 8
				+ (int) ((Math.max(0, inv.count(ItemTags.PLANKS) - 12)
						+ Math.max(0, inv.count(ItemTags.LOGS) - 2) * 4) * 1.5);
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

	/** A task that chops the given number of additional logs from real trees on the surface. */
	private Task wood(int logs) {
		int target = bot.inv.count(ItemTags.LOGS) + logs;
		MineTask t = new MineTask(bot, "logs", s -> s.is(BlockTags.LOGS), s -> s.is(ItemTags.LOGS),
				() -> bot.inv.count(ItemTags.LOGS) >= target, false);
		t.minY = 50;
		t.where = this::isTreeLog;
		// if no tree is in sight, head for the nearest wooded biome
		t.hint = () -> Locate.biome(bot,
				b -> b.is(BiomeTags.IS_FOREST) || b.is(BiomeTags.IS_TAIGA) || b.is(BiomeTags.IS_JUNGLE), 3000);
		return t;
	}

	/** A task that mines until the bot carries {@code count} cobblestone-like blocks. */
	private Task stone(int count) {
		MineTask t = new MineTask(bot, "stone", BeatGameTask::isStone, Ing.COBBLE.pred,
				() -> bot.inv.count(Ing.COBBLE.pred) >= count, true);
		t.scanRadius = 2;
		return t;
	}

	private static boolean isStone(BlockState s) {
		return s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.DEEPSLATE)
				|| s.is(Blocks.COBBLED_DEEPSLATE);
	}

	private Task ore(String name, Predicate<BlockState> block, Predicate<ItemStack> drop, BooleanSupplier done) {
		MineTask t = new MineTask(bot, name, block, drop, done, true);
		t.scanRadius = 8;
		return t;
	}

	/** True if the log at {@code p} belongs to a tree: the column of logs above it ends in leaves. */
	private boolean isTreeLog(BlockPos p) {
		BlockPos.MutableBlockPos m = p.mutable();
		for (int i = 0; i < 12; i++) {
			m.move(0, 1, 0);
			BlockState st = bot.level().getBlockState(m);
			if (st.is(BlockTags.LEAVES)) {
				return true;
			}
			if (!st.is(BlockTags.LOGS)) {
				return false;
			}
		}
		return false;
	}

	private void stage(String name, BooleanSupplier done, Supplier<Task> make) {
		stages.add(new Stage(name, done, make));
	}

	@Override
	public void onInterrupted() {
		super.onInterrupted();
		if (task != null) {
			task.onInterrupted();
		}
		current = null;
		task = null;
	}

	/**
	 * Watchdog: hashes dimension, coarse position (8-block cells), inventory and health. Returns true when that
	 * fingerprint has not changed, and no task reported progress via {@code bot.heartbeat}, for 4 minutes.
	 */
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
		return bot.tick - stallSince > 4800;
	}

	@Override
	public boolean tick() {
		// getting out of a stall: wander to a random spot 24 blocks away for at most 30 seconds
		if (unstick != null) {
			bot.setStatus("shaking myself loose");
			if (++unstickTicks > 600 || bot.nav.goTo(unstick) != Nav.Status.RUNNING) {
				unstick = null;
			}
			return false;
		}
		if (bot.tick % 100 == 0 && stalled()) {
			bot.say("I seem to be stuck on '" + (current == null ? "?" : current.name) + "'. Trying something else.");
			if (task != null) {
				task.onInterrupted();
			}
			task = null;
			if (!bot.inv.menuIsInventory()) {
				bot.player().closeContainer();
			}
			bot.nav.reset();
			BlockPos f = bot.feet();
			// pseudo-random direction derived from the tick counter (0 .. 2*pi)
			double ang = (bot.tick % 628) / 100.0;
			unstick = Goal.xz(f.getX() + (int) (Math.cos(ang) * 24.0), f.getZ() + (int) (Math.sin(ang) * 24.0), 3.0);
			unstickTicks = 0;
			stallSince = bot.tick;
			return false;
		}
		if (task != null) {
			// every 3 seconds: if an earlier stage became undone (items lost, ...), drop the current task for it
			if (bot.tick % 60 == 0 && task.interruptible()) {
				for (Stage s : stages) {
					if (s == current) {
						break;
					}
					if (!s.done.getAsBoolean()) {
						task.onInterrupted();
						task = null;
						break;
					}
				}
			}
			if (task != null) {
				if (!task.tick()) {
					return false;
				}
				task = null;
			}
		}
		// start the first stage that is not done yet
		for (Stage s : stages) {
			if (s.done.getAsBoolean()) {
				continue;
			}
			if (s != current) {
				bot.say("Stage: " + s.name);
			}
			current = s;
			bot.stageName = s.name;
			bot.stageIndex = stages.indexOf(s) + 1;
			bot.stageCount = stages.size();
			bot.nav.reset();
			task = s.make.get();
			return false;
		}
		return bot.memory.dragonDead;
	}

	/** One step of the plan: a display name, the condition under which it is skipped, and the task that does it. */
	private record Stage(String name, BooleanSupplier done, Supplier<Task> make) {
	}
}
