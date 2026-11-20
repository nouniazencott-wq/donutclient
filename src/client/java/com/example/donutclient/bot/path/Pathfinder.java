package com.example.donutclient.bot.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import com.example.donutclient.bot.Blocks2;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Incremental weighted A* over feet positions. Costs are in ticks. Moves: walk/sprint, swim, step up, drop
 * down, diagonal, mine through, bridge, pillar up and dig down. The search is time-sliced with
 * {@link #step(long)}; when the goal cannot be reached within the node budget the path to the node closest to
 * the goal (by heuristic) is returned instead.
 */
public final class Pathfinder {
	private static final double INF = 1.0E9;
	/** Ticks to walk one block. */
	private static final double WALK = 4.63;
	/** Ticks to sprint one block. */
	private static final double SPRINT = 3.56;
	/** Ticks to swim one block. */
	private static final double SWIM = 9.0;
	/** Extra cost of placing a block. */
	private static final double PLACE = 16.0;
	/** Extra cost of jumping up a block. */
	private static final double JUMP = 2.5;
	/** Cost of pillaring up one block. */
	private static final double PILLAR = 12.0;
	/** Highest drop onto solid ground the bot will take. */
	private static final int MAX_FALL = 3;
	private static final int[] DX = {1, -1, 0, 0};
	private static final int[] DZ = {0, 0, 1, -1};

	private final ClientLevel level;
	private final Goal goal;
	/** Tools in the inventory, used to estimate mining times. */
	private final List<ItemStack> tools;
	/** Whether bridging and pillaring moves are allowed (the bot has blocks to place). */
	private final boolean canPlace;
	private final boolean allowBreak;
	/** Maximum number of nodes to expand before giving up and returning the best partial path. */
	private final int maxNodes;

	/** Optional centre of an area where open-air (uncovered) nodes are penalised. */
	public BlockPos coverCenter;
	public int coverRadius;
	/** Extra cost for every open-air node (non-zero in the Nether and the End). */
	public double openCost;

	private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
	private final PriorityQueue<Node> open = new PriorityQueue<>();
	/** Block flags by packed position; -1 = not cached. */
	private final Long2IntOpenHashMap flagCache = new Long2IntOpenHashMap();
	private final Long2BooleanOpenHashMap chunkLoaded = new Long2BooleanOpenHashMap();
	/** Ticks to mine each block state with the best available tool. */
	private final Reference2FloatOpenHashMap<BlockState> breakCache = new Reference2FloatOpenHashMap<>();
	private final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
	private final int minY;
	private final int maxY;

	private Node start;
	/** Expanded node with the lowest heuristic so far (fallback target). */
	private Node best;
	private int expanded;
	private boolean done;
	private List<BlockPos> result;
	private boolean reachedGoal;

	/** Search node; ordered by f = g + weighted h. */
	private static final class Node implements Comparable<Node> {
		final int x;
		final int y;
		final int z;
		double g = INF;
		double f;
		double h;
		Node parent;
		boolean closed;

		Node(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public int compareTo(Node other) {
			return Double.compare(f, other.f);
		}
	}

	public Pathfinder(ClientLevel level, BlockPos from, Goal goal, List<ItemStack> tools, boolean canPlace, boolean allowBreak,
			int maxNodes) {
		this.level = level;
		this.goal = goal;
		this.tools = tools;
		this.canPlace = canPlace;
		this.allowBreak = allowBreak;
		this.maxNodes = maxNodes;
		this.minY = level.getMinY();
		this.maxY = level.getMaxY();
		flagCache.defaultReturnValue(-1);
		start = node(from.getX(), from.getY(), from.getZ());
		start.g = 0;
		start.h = goal.h(start.x, start.y, start.z);
		start.f = start.h;
		best = start;
		open.add(start);
	}

	public boolean isDone() {
		return done;
	}

	/** The found path (start to end), or null when there is none. Only valid once the search is done. */
	public List<BlockPos> path() {
		return result;
	}

	/** Whether {@link #path()} ends at the goal (false for a partial best-effort path). */
	public boolean reachedGoal() {
		return reachedGoal;
	}

	/** Number of nodes expanded so far. */
	public int expanded() {
		return expanded;
	}

	/**
	 * Runs the search for about {@code budgetNanos} nanoseconds.
	 *
	 * @return true when the search has finished
	 */
	public boolean step(long budgetNanos) {
		if (done) {
			return true;
		}
		long deadline = System.nanoTime() + budgetNanos;
		int iterations = 0;
		while (true) {
			Node cur = open.poll();
			if (cur == null) {
				finish(null);
				return true;
			}
			if (cur.closed) {
				continue;
			}
			cur.closed = true;
			if (goal.at(cur.x, cur.y, cur.z)) {
				finish(cur);
				return true;
			}
			expanded++;
			if (cur != start && cur.h < best.h) {
				best = cur;
			}
			expand(cur);
			if (expanded >= maxNodes) {
				finish(null);
				return true;
			}
			// Only look at the clock every 64 nodes.
			if ((++iterations & 63) == 0 && System.nanoTime() > deadline) {
				return false;
			}
		}
	}

	/** Ends the search; {@code goalNode} is the goal node reached, or null to fall back to the best partial path. */
	private void finish(Node goalNode) {
		done = true;
		Node end = goalNode;
		reachedGoal = goalNode != null;
		// Partial path: only worth it if it gets at least a little closer than where we are.
		if (end == null && best != start && best.h < start.h - 1.0) {
			end = best;
		}
		if (end == null) {
			result = null;
			return;
		}
		ArrayList<BlockPos> list = new ArrayList<>();
		for (Node n = end; n != null; n = n.parent) {
			list.add(new BlockPos(n.x, n.y, n.z));
		}
		Collections.reverse(list);
		result = list;
	}

	private Node node(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		Node n = nodes.get(key);
		if (n == null) {
			n = new Node(x, y, z);
			nodes.put(key, n);
		}
		return n;
	}

	/** Cached {@link Blocks2} flags of a block; outside the world height everything is passable. */
	private int fl(int x, int y, int z) {
		if (y < minY || y > maxY) {
			return Blocks2.PASS;
		}
		long key = BlockPos.asLong(x, y, z);
		int flags = flagCache.get(key);
		if (flags != -1) {
			return flags;
		}
		long chunkKey = ((long) (x >> 4) << 32) | ((long) (z >> 4) & 0xFFFFFFFFL);
		boolean loaded;
		if (chunkLoaded.containsKey(chunkKey)) {
			loaded = chunkLoaded.get(chunkKey);
		} else {
			loaded = level.getChunkSource().hasChunk(x >> 4, z >> 4);
			chunkLoaded.put(chunkKey, loaded);
		}
		if (!loaded) {
			flags = Blocks2.UNLOADED;
		} else {
			mut.set(x, y, z);
			flags = Blocks2.flags(level, mut, level.getBlockState(mut));
		}
		flagCache.put(key, flags);
		return flags;
	}

	/** Ticks to mine the block at the position with the best tool we carry (cached per block state). */
	private double breakTicks(int x, int y, int z) {
		mut.set(x, y, z);
		BlockState state = level.getBlockState(mut);
		if (breakCache.containsKey(state)) {
			return breakCache.getFloat(state);
		}
		float hardness = state.getDestroySpeed(level, mut);
		float ticks = (float) ticksToBreak(state, hardness, tools);
		breakCache.put(state, ticks);
		return ticks;
	}

	/**
	 * Estimates the ticks needed to mine a block of the given hardness with the best of the given tools
	 * (ignoring enchantments, haste, being in water or in the air).
	 */
	public static double ticksToBreak(BlockState state, float hardness, List<ItemStack> tools) {
		if (hardness < 0) {
			return INF;
		}
		if (hardness == 0) {
			return 1;
		}
		float speed = 1.0f;
		// Bare hands harvest anything that does not need a specific tool.
		boolean correct = !state.requiresCorrectToolForDrops();
		for (ItemStack tool : tools) {
			float toolSpeed = tool.getDestroySpeed(state);
			boolean toolCorrect = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
			if (toolCorrect && !correct) {
				// The first tool that can actually harvest the block beats anything that cannot.
				correct = true;
				speed = toolSpeed;
			} else if ((toolCorrect == correct || toolCorrect) && toolSpeed > speed) {
				speed = toolSpeed;
			}
		}
		double progressPerTick = speed / hardness / (correct ? 30.0 : 100.0);
		return Math.ceil(1.0 / progressPerTick);
	}

	/** Cost of mining the block, or {@link #INF} when it must not be mined (unbreakable, liquid next to it...). */
	private double breakCost(int x, int y, int z) {
		if (!allowBreak) {
			return INF;
		}
		int flags = fl(x, y, z);
		if ((flags & Blocks2.BREAK) == 0) {
			return INF;
		}
		int above = fl(x, y + 1, z);
		if ((above & (Blocks2.WATER | Blocks2.LAVA)) != 0) {
			return INF;
		}
		for (int i = 0; i < 4; i++) {
			if ((fl(x + DX[i], y, z + DZ[i]) & (Blocks2.WATER | Blocks2.LAVA)) != 0) {
				return INF;
			}
		}
		double cost = breakTicks(x, y, z) + 6.0;
		// Gravel/sand above will fall in and has to be mined as well.
		if ((above & Blocks2.FALLING) != 0) {
			cost += 2.0 * breakTicks(x, y + 1, z) + 12.0;
		}
		return cost;
	}

	/** Cost of making the block passable: 0 if it already is, the mining cost otherwise, INF if impossible. */
	private double clear(int x, int y, int z) {
		int flags = fl(x, y, z);
		if ((flags & (Blocks2.DANGER | Blocks2.UNLOADED)) != 0) {
			return INF;
		}
		if ((flags & (Blocks2.PASS | Blocks2.WATER)) != 0) {
			return 0;
		}
		return breakCost(x, y, z);
	}

	/** Whether lava touches the player-sized space at the position (sides, above the head or under the feet). */
	private boolean lavaNear(int x, int y, int z) {
		for (int i = 0; i < 4; i++) {
			int nx = x + DX[i];
			int nz = z + DZ[i];
			if ((fl(nx, y, nz) & Blocks2.LAVA) != 0 || (fl(nx, y + 1, nz) & Blocks2.LAVA) != 0) {
				return true;
			}
		}
		return (fl(x, y + 2, z) & Blocks2.LAVA) != 0 || (fl(x, y - 1, z) & Blocks2.LAVA) != 0;
	}

	/**
	 * Extra cost for standing next to lava: 10 for each of the 8 neighbouring floor blocks that is lava, 6 for
	 * each neighbouring open column with lava within 5 blocks below.
	 */
	private double lavaPenalty(int x, int y, int z) {
		double penalty = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				int floor = fl(x + dx, y - 1, z + dz);
				if ((floor & Blocks2.LAVA) != 0) {
					penalty += 10.0;
				} else if ((floor & Blocks2.PASS) != 0) {
					for (int depth = 2; depth <= 5; depth++) {
						int below = fl(x + dx, y - depth, z + dz);
						if ((below & Blocks2.LAVA) != 0) {
							penalty += 6.0;
							break;
						}
						if ((below & Blocks2.PASS) == 0) {
							break;
						}
					}
				}
			}
		}
		return penalty;
	}

	/** Offers the move from -> (x, y, z) with the given cost to the open set. */
	private void relax(Node from, int x, int y, int z, double cost) {
		if (cost >= INF) {
			return;
		}
		if (y < minY + 1 || y > maxY - 2) {
			return;
		}
		if (lavaNear(x, y, z)) {
			return;
		}
		cost += lavaPenalty(x, y, z);
		// Prefer tunnelling to walking in the open where that is dangerous.
		if (openCost > 0 && (fl(x, y, z) & Blocks2.PASS) != 0 && (fl(x, y + 1, z) & Blocks2.PASS) != 0) {
			cost += openCost;
		}
		if (coverCenter != null) {
			int dx = x - coverCenter.getX();
			int dy = y - coverCenter.getY();
			int dz = z - coverCenter.getZ();
			if (dx * dx + dy * dy + dz * dz <= coverRadius * coverRadius && (fl(x, y, z) & Blocks2.PASS) != 0
					&& (fl(x, y + 1, z) & Blocks2.PASS) != 0) {
				cost += 30.0;
			}
		}
		Node n = node(x, y, z);
		if (n.closed) {
			return;
		}
		double g = from.g + cost;
		if (g < n.g - 1.0E-6) {
			n.g = g;
			n.parent = from;
			n.h = goal.h(x, y, z);
			// Weighted A*: greedier than optimal, but much faster.
			n.f = g + n.h * 1.25;
			open.add(n);
		}
	}

	/** Generates all moves out of a node. */
	private void expand(Node n) {
		int x = n.x;
		int y = n.y;
		int z = n.z;
		int here = fl(x, y, z);
		boolean inWater = (here & Blocks2.WATER) != 0;

		for (int i = 0; i < 4; i++) {
			int dx = DX[i];
			int dz = DZ[i];
			int nx = x + dx;
			int nz = z + dz;
			double feetCost = clear(nx, y, nz);
			double headCost = clear(nx, y + 1, nz);
			if (feetCost < INF && headCost < INF) {
				int feet = fl(nx, y, nz);
				int floor = fl(nx, y - 1, nz);
				if ((floor & Blocks2.STAND) != 0) {
					// Level walk (sprint only when nothing has to be mined).
					double move = feetCost + headCost > 0 ? WALK : SPRINT;
					if ((feet & Blocks2.WATER) != 0) {
						move = SWIM;
					}
					relax(n, nx, y, nz, move + feetCost + headCost);
				} else if ((feet & Blocks2.WATER) != 0) {
					// Swim along the surface only (no water above the destination).
					if ((fl(nx, y + 1, nz) & Blocks2.WATER) == 0) {
						relax(n, nx, y, nz, SWIM + feetCost + headCost);
					}
				} else {
					// No floor: drop down, and/or bridge across.
					boolean dropped = descend(n, nx, y, nz, feetCost + headCost);
					if (!dropped && canPlace && !inWater && (floor & Blocks2.PASS) != 0) {
						relax(n, nx, y, nz, WALK + PLACE + feetCost + headCost);
					} else if (canPlace && !inWater && (floor & Blocks2.PASS) != 0) {
						relax(n, nx, y, nz, WALK + PLACE + 8.0 + feetCost + headCost);
					}
				}
			}
			if ((fl(nx, y, nz) & Blocks2.STAND) != 0) {
				// Step up onto the neighbouring block.
				double aboveHead = clear(x, y + 2, z);
				if (aboveHead < INF) {
					double destFeet = clear(nx, y + 1, nz);
					if (destFeet < INF) {
						double destHead = clear(nx, y + 2, nz);
						if (destHead < INF) {
							relax(n, nx, y + 1, nz, WALK + JUMP + aboveHead + destFeet + destHead);
						}
					}
				}
			} else if (feetCost < INF && headCost < INF) {
				// Mine a stair step downwards.
				int floor = fl(nx, y - 1, nz);
				if ((floor & (Blocks2.PASS | Blocks2.WATER | Blocks2.DANGER | Blocks2.UNLOADED)) == 0
						&& (fl(nx, y - 2, nz) & Blocks2.STAND) != 0) {
					double dig = breakCost(nx, y - 1, nz);
					if (dig < INF) {
						relax(n, nx, y - 1, nz, WALK + 2.0 + feetCost + headCost + dig);
					}
				}
			}
		}

		// Diagonals: only over solid floor and with both adjacent corners open.
		if (!inWater) {
			for (int dx = -1; dx <= 1; dx += 2) {
				for (int dz = -1; dz <= 1; dz += 2) {
					int nx = x + dx;
					int nz = z + dz;
					if ((fl(nx, y - 1, nz) & Blocks2.STAND) == 0) {
						continue;
					}
					if (!open2(nx, y, nz) || !open2(nx, y, z) || !open2(x, y, nz)) {
						continue;
					}
					relax(n, nx, y, nz, SPRINT * 1.414);
				}
			}
		}

		if (inWater) {
			// Swim up / dive.
			if ((fl(x, y + 1, z) & Blocks2.WATER) != 0) {
				relax(n, x, y + 1, z, 5.0);
			}
			if ((fl(x, y - 1, z) & Blocks2.WATER) != 0) {
				relax(n, x, y - 1, z, 40.0);
			}
		} else if (canPlace) {
			// Pillar up.
			double aboveHead = clear(x, y + 2, z);
			int floor = fl(x, y - 1, z);
			if (aboveHead < INF && (floor & (Blocks2.WATER | Blocks2.LAVA)) == 0) {
				relax(n, x, y + 1, z, PILLAR + aboveHead);
			}
		}

		// Dig straight down and fall to the next floor (at most MAX_FALL blocks) or into water.
		int floor = fl(x, y - 1, z);
		if ((floor & (Blocks2.PASS | Blocks2.WATER | Blocks2.DANGER | Blocks2.UNLOADED)) == 0) {
			double dig = breakCost(x, y - 1, z);
			if (dig < INF) {
				for (int ny = y - 1; ny >= y - 1 - MAX_FALL + 1; ny--) {
					int flags = ny == y - 1 ? Blocks2.PASS : fl(x, ny, z);
					if ((flags & Blocks2.WATER) != 0) {
						relax(n, x, ny, z, dig + (y - ny) * 1.5);
						break;
					}
					if ((flags & Blocks2.PASS) == 0) {
						break;
					}
					int under = fl(x, ny - 1, z);
					if ((under & Blocks2.STAND) != 0) {
						relax(n, x, ny, z, dig + (y - ny) * 1.5);
						break;
					}
					if ((under & (Blocks2.DANGER | Blocks2.UNLOADED)) != 0) {
						break;
					}
				}
			}
		}
	}

	/** Whether both the feet and head block at the position are passable. */
	private boolean open2(int x, int y, int z) {
		return (fl(x, y, z) & Blocks2.PASS) != 0 && (fl(x, y + 1, z) & Blocks2.PASS) != 0;
	}

	/**
	 * Walk off an edge into the column (x, z): looks down up to 14 blocks for a landing spot. Water is accepted
	 * at any depth, solid ground only within {@link #MAX_FALL} blocks.
	 *
	 * @return true if a drop move was added
	 */
	private boolean descend(Node from, int x, int y, int z, double cost) {
		for (int ny = y - 1; ny >= y - 14; ny--) {
			int flags = fl(x, ny, z);
			if ((flags & Blocks2.WATER) != 0) {
				relax(from, x, ny, z, WALK + (y - ny) * 1.5 + cost);
				return true;
			}
			if ((flags & Blocks2.PASS) == 0) {
				return false;
			}
			int under = fl(x, ny - 1, z);
			if ((under & Blocks2.STAND) != 0) {
				if (y - ny > MAX_FALL) {
					return false;
				}
				relax(from, x, ny, z, WALK + (y - ny) * 1.5 + cost);
				return true;
			}
			if ((under & (Blocks2.DANGER | Blocks2.UNLOADED)) != 0) {
				return false;
			}
		}
		return false;
	}
}
