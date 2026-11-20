package com.example.donutclient.bot.path;

import java.util.ArrayList;
import java.util.List;

import com.example.donutclient.bot.Blocks2;
import com.example.donutclient.bot.Bot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a path produced by {@link Pathfinder}, one node-to-node move per step: mines blocks in the way,
 * pillars up, bridges gaps, swims and jumps as needed. {@link #tick()} is called once per client tick and
 * drives the bot's input for that tick.
 */
public final class PathExecutor {
	/** Result of one {@link #tick()}. */
	public enum Result {
		RUNNING,
		DONE,
		FAILED
	}

	private final Bot bot;
	/** Feet positions, start to end. */
	private final List<BlockPos> path;
	/** Index of the node we are currently leaving; the move in progress is idx -> idx + 1. */
	private int idx;
	/** Consecutive grounded ticks spent on neither end of the current move. */
	private int offPath;
	/** Consecutive ticks without noticeable movement. */
	private int stuck;
	/** Ticks spent on the current move. */
	private int moveTicks;
	/** Ticks spent mining during the current move (not counted in {@link #moveTicks}). */
	private int mineTicks;
	/** Ticks spent trying to pillar up during the current move. */
	private int pillarTicks;
	/** Failed bridge placements during the current move. */
	private int placeFails;
	private Vec3 lastPos;
	/** Why the last {@link Result#FAILED} happened. */
	public String failReason = "";

	public PathExecutor(Bot bot, List<BlockPos> path) {
		this.bot = bot;
		this.path = path;
	}

	/** Last node of the path. */
	public BlockPos end() {
		return path.get(path.size() - 1);
	}

	/** The node the move in progress is heading for (where a failure happened), or null at the end of the path. */
	public BlockPos failedNode() {
		return idx + 1 < path.size() ? path.get(idx + 1) : null;
	}

	private int flags(BlockPos pos) {
		return Blocks2.flags(bot.level(), pos, bot.level().getBlockState(pos));
	}

	/** Whether the feet position counts as being on a path node; when swimming one block of vertical slack is allowed. */
	private boolean matches(BlockPos feet, BlockPos node, boolean inWater) {
		if (feet.equals(node)) {
			return true;
		}
		return inWater && feet.getX() == node.getX() && feet.getZ() == node.getZ() && Math.abs(feet.getY() - node.getY()) <= 1;
	}

	private Result fail(String reason) {
		failReason = reason;
		if (idx + 1 < path.size()) {
			bot.dbg("path step failed (" + reason + ") " + path.get(idx).toShortString() + " -> " + path.get(idx + 1).toShortString()
					+ " at " + bot.feet().toShortString());
		}
		bot.act.stopBreaking();
		return Result.FAILED;
	}

	/** Performs one tick of path following. */
	public Result tick() {
		LocalPlayer player = bot.player();
		BlockPos feet = bot.feet();
		boolean inWater = player.isInWater();
		boolean grounded = player.onGround() || inWater;

		// Advance: if we are standing on one of the next few nodes, skip ahead to the furthest one.
		if (grounded) {
			for (int i = Math.min(path.size() - 1, idx + 4); i > idx; i--) {
				if (matches(feet, path.get(i), inWater)) {
					idx = i;
					moveTicks = 0;
					mineTicks = 0;
					pillarTicks = 0;
					placeFails = 0;
					break;
				}
			}
		}
		if (idx >= path.size() - 1) {
			bot.act.stopBreaking();
			return Result.DONE;
		}

		BlockPos from = path.get(idx);
		BlockPos to = path.get(idx + 1);
		if (grounded && !matches(feet, from, inWater) && !matches(feet, to, inWater)) {
			if (++offPath > 15) {
				// Knocked off the path: resume from whichever node we happen to stand on, if any.
				for (int i = 0; i < path.size(); i++) {
					if (matches(feet, path.get(i), inWater)) {
						idx = i;
						offPath = 0;
						return Result.RUNNING;
					}
				}
				return fail("off path");
			}
		} else {
			offPath = 0;
		}
		if (++moveTicks > 600) {
			return fail("move timeout");
		}

		int dx = to.getX() - from.getX();
		int dy = to.getY() - from.getY();
		int dz = to.getZ() - from.getZ();
		boolean vertical = dx == 0 && dz == 0;

		// Blocks that must be clear for this move, in the order they should be mined.
		List<BlockPos> toClear = new ArrayList<>(4);
		if (vertical) {
			if (dy > 0) {
				toClear.add(from.above(2));
			} else {
				for (int y = from.getY() - 1; y >= to.getY(); y--) {
					toClear.add(new BlockPos(from.getX(), y, from.getZ()));
				}
			}
		} else if (dy > 0) {
			toClear.add(from.above(2));
			toClear.add(to.above());
			toClear.add(to);
		} else if (dy == 0) {
			toClear.add(to.above());
			toClear.add(to);
		} else {
			toClear.add(new BlockPos(to.getX(), from.getY() + 1, to.getZ()));
			for (int y = from.getY(); y >= to.getY(); y--) {
				toClear.add(new BlockPos(to.getX(), y, to.getZ()));
			}
		}
		for (BlockPos pos : toClear) {
			int f = flags(pos);
			if ((f & (Blocks2.PASS | Blocks2.WATER)) != 0) {
				continue;
			}
			if ((f & Blocks2.DANGER) != 0) {
				return fail("danger in path");
			}
			if ((f & Blocks2.BREAK) == 0) {
				return fail("unbreakable in path");
			}
			// Only lava aborts. Water next to the block is harmless, and the planner happily routes past
			// waterlogged blocks and flowing water, so failing on it made the bot replan the same path forever.
			if (lavaNextTo(pos)) {
				return fail("lava next to block");
			}
			if (!bot.act.inReach(pos)) {
				// Too far to mine yet: keep moving and try again when closer.
				break;
			}
			if (vertical && dy < 0 && !centered(from, 0.2)) {
				// Centre on the block before digging straight down.
				walkTo(from, false);
				return Result.RUNNING;
			}
			if (inWater) {
				bot.input.jump = true;
			}
			// Mining time has its own, longer limit: three hard blocks by hand legitimately take more than the
			// 600 ticks allowed for a move, and timing out there only made the bot replan the same dig again.
			moveTicks--;
			if (++mineTicks > 1200) {
				return fail("mine timeout");
			}
			bot.act.breakBlock(pos);
			stuck = 0;
			return Result.RUNNING;
		}
		bot.act.stopBreaking();

		// Pillar up: jump and place a block under the feet.
		if (vertical && dy > 0 && !inWater) {
			if ((flags(from) & Blocks2.WATER) != 0 || (flags(to) & Blocks2.WATER) != 0) {
				bot.input.jump = true;
				return Result.RUNNING;
			}
			if (bot.inv.countThrowaway() == 0) {
				return fail("no blocks to pillar");
			}
			if (!centered(from, 0.25) && player.onGround()) {
				walkTo(from, false);
				return Result.RUNNING;
			}
			// Counted apart from moveTicks so that time spent mining the block overhead first does not get a
			// perfectly good pillar declared stuck (which bans pillaring and bridging for the next plans).
			if (++pillarTicks > 120) {
				return fail("pillar stuck");
			}
			if (player.onGround()) {
				bot.input.jump = true;
			}
			if (player.getY() >= from.getY() + 1.0) {
				bot.act.placeBlock(from);
			} else {
				bot.act.lookAt(Vec3.atCenterOf(from.below()));
			}
			return Result.RUNNING;
		}
		// Other vertical moves: swim up, or fall down the shaft we just dug.
		if (vertical) {
			if (inWater && dy > 0) {
				bot.input.jump = true;
			}
			return checkStuck(player, true) ? fail("stuck") : Result.RUNNING;
		}

		// Bridge: nothing to stand on at the destination, so place a block there first.
		if (dy == 0) {
			BlockPos floor = to.below();
			int floorFlags = flags(floor);
			if ((floorFlags & Blocks2.STAND) == 0 && (flags(to) & Blocks2.WATER) == 0 && (floorFlags & Blocks2.PASS) != 0) {
				if (bot.inv.countThrowaway() == 0) {
					return fail("no blocks to bridge");
				}
				if (!bot.act.placeBlock(floor)) {
					if (++placeFails > 60) {
						return fail("cannot place");
					}
					bot.input.sneak = true;
					if (!centered(from, 0.35)) {
						walkTo(from, false);
					} else {
						// Sneak out to the edge so the side face of the block under us becomes clickable.
						bot.act.faceXZ(to.getX() + 0.5, to.getZ() + 0.5);
						bot.input.forward = true;
						bot.input.sneak = true;
					}
					return Result.RUNNING;
				}
			}
		}

		if (lavaAround(to, 1, 0)) {
			return fail("lava moved into the path");
		}
		boolean nearLava = lavaAround(to, 1, 5) || lavaAround(from, 1, 5);
		boolean diagonal = dx != 0 && dz != 0;
		boolean sprint = dy == 0 && !inWater && player.getFoodData().getFoodLevel() > 6;
		// Do not sprint into a drop (this move or the next one).
		if (dy < 0 || idx + 2 < path.size() && path.get(idx + 2).getY() < to.getY()) {
			sprint = false;
		}
		walkTo(to, sprint);
		if (inWater) {
			if (dy >= 0 || player.getY() < to.getY() + 0.2) {
				bot.input.jump = true;
			}
		} else if (dy > 0 && player.onGround()) {
			bot.input.jump = true;
		}
		if (diagonal) {
			bot.input.sprint = sprint;
		}
		if (nearLava && dy == 0 && !inWater) {
			bot.input.sprint = false;
			bot.input.sneak = true;
		}
		// No jump-tapping beside lava: a hop cancels the sneak that keeps the bot from walking off the edge.
		if (checkStuck(player, !nearLava)) {
			return fail("stuck");
		}
		return Result.RUNNING;
	}

	/**
	 * Tracks lack of movement. After 25 still ticks it starts tapping jump to shake loose (when
	 * {@code mayJump}); returns true once the bot has not moved for more than 80 ticks.
	 */
	private boolean checkStuck(LocalPlayer player, boolean mayJump) {
		Vec3 pos = player.position();
		if (lastPos == null || pos.distanceToSqr(lastPos) > 0.04) {
			lastPos = pos;
			stuck = 0;
			return false;
		}
		stuck++;
		if (mayJump && stuck > 25 && stuck % 10 < 3 && player.onGround()) {
			bot.input.jump = true;
		}
		return stuck > 80;
	}

	/** Whether the player is horizontally within {@code tolerance} of the centre of the block column. */
	private boolean centered(BlockPos pos, double tolerance) {
		LocalPlayer player = bot.player();
		return Math.abs(player.getX() - (pos.getX() + 0.5)) < tolerance && Math.abs(player.getZ() - (pos.getZ() + 0.5)) < tolerance;
	}

	private void walkTo(BlockPos pos, boolean sprint) {
		bot.act.faceXZ(pos.getX() + 0.5, pos.getZ() + 0.5);
		bot.input.forward = true;
		bot.input.sprint = sprint;
	}

	/**
	 * Looks for lava in the columns within {@code radius} of {@code pos}, from one block above the feet down to
	 * {@code depth} blocks below; a column's scan stops at the first solid block below feet level.
	 */
	private boolean lavaAround(BlockPos pos, int radius, int depth) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = 1; dy >= -depth; dy--) {
					cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
					BlockState state = bot.level().getBlockState(cursor);
					if (state.getFluidState().is(FluidTags.LAVA)) {
						return true;
					}
					if (dy < 0 && !state.isAir() && state.getFluidState().isEmpty()) {
						break;
					}
				}
			}
		}
		return false;
	}

	private static final Direction[] FLOW_SIDES = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	/** Whether lava touches the block from above or the sides (mining it would let the lava in). */
	private boolean lavaNextTo(BlockPos pos) {
		for (Direction dir : FLOW_SIDES) {
			BlockState state = bot.level().getBlockState(pos.relative(dir));
			if (state.getFluidState().is(FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}
}
