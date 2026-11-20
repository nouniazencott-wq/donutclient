package com.example.donutclient.bot.path;

import com.example.donutclient.bot.Blocks2;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2FloatOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import static com.example.donutclient.bot.Blocks2.*;

/**
 * A* over player feet positions. Moves: walk, diagonal, step up, drop down,
 * swim, pillar up, dig down, with block breaking and bridging priced in.
 * The search is resumable so it can be spread over several client ticks.
 */
public final class Pathfinder {
    private static final double INF = 1e9;
    private static final double WALK = 4.63, SPRINT = 3.56, SWIM = 9.0;
    private static final double PLACE = 16.0, JUMP = 2.5, PILLAR = 12.0;
    private static final int MAX_FALL = 3;
    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        double g = INF, f, h;
        Node parent;
        boolean closed;

        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        public int compareTo(Node o) { return Double.compare(f, o.f); }
    }

    private final ClientLevel level;
    private final Goal goal;
    private final List<ItemStack> tools;
    private final boolean canPlace;
    private final boolean allowBreak;
    private final int maxNodes;

    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>();
    private final Long2IntOpenHashMap flagCache = new Long2IntOpenHashMap();
    private final Long2BooleanOpenHashMap chunkLoaded = new Long2BooleanOpenHashMap();
    private final Reference2FloatOpenHashMap<BlockState> breakCache = new Reference2FloatOpenHashMap<>();
    private final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
    private final int minY, maxY;

    private Node start, best;
    private int expanded;
    private boolean done;
    private List<BlockPos> result;
    private boolean reachedGoal;

    public Pathfinder(ClientLevel level, BlockPos from, Goal goal, List<ItemStack> tools, boolean canPlace,
                      boolean allowBreak, int maxNodes) {
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

    public boolean isDone() { return done; }

    /** Null if nothing useful was found. */
    public List<BlockPos> path() { return result; }

    public boolean reachedGoal() { return reachedGoal; }

    public int expanded() { return expanded; }

    /** Runs the search for up to the given time. Returns true when finished. */
    public boolean step(long budgetNanos) {
        if (done) return true;
        long end = System.nanoTime() + budgetNanos;
        int n = 0;
        while (true) {
            Node cur = open.poll();
            if (cur == null) { finish(null); return true; }
            if (cur.closed) continue;
            cur.closed = true;
            if (goal.at(cur.x, cur.y, cur.z)) { finish(cur); return true; }
            expanded++;
            if (cur != start && cur.h < best.h) best = cur;
            expand(cur);
            if (expanded >= maxNodes) { finish(null); return true; }
            if ((++n & 63) == 0 && System.nanoTime() > end) return false;
        }
    }

    private void finish(Node goalNode) {
        done = true;
        Node end = goalNode;
        reachedGoal = goalNode != null;
        if (end == null) {
            // Partial path: head for the most promising node, if it is a real improvement.
            if (best != start && best.h < start.h - 1.0) end = best;
        }
        if (end == null) { result = null; return; }
        ArrayList<BlockPos> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) out.add(new BlockPos(n.x, n.y, n.z));
        Collections.reverse(out);
        result = out;
    }

    // ---- world queries --------------------------------------------------------

    private Node node(int x, int y, int z) {
        long k = BlockPos.asLong(x, y, z);
        Node n = nodes.get(k);
        if (n == null) { n = new Node(x, y, z); nodes.put(k, n); }
        return n;
    }

    private int fl(int x, int y, int z) {
        if (y < minY || y > maxY) return PASS;
        long k = BlockPos.asLong(x, y, z);
        int f = flagCache.get(k);
        if (f != -1) return f;
        long ck = (((long) (x >> 4)) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        boolean loaded;
        if (chunkLoaded.containsKey(ck)) loaded = chunkLoaded.get(ck);
        else { loaded = level.getChunkSource().hasChunk(x >> 4, z >> 4); chunkLoaded.put(ck, loaded); }
        if (!loaded) f = UNLOADED;
        else {
            mut.set(x, y, z);
            f = Blocks2.flags(level, mut, level.getBlockState(mut));
        }
        flagCache.put(k, f);
        return f;
    }

    private double breakTicks(int x, int y, int z) {
        mut.set(x, y, z);
        BlockState s = level.getBlockState(mut);
        if (breakCache.containsKey(s)) return breakCache.getFloat(s);
        float hardness = s.getDestroySpeed(level, mut);
        float t = (float) ticksToBreak(s, hardness, tools);
        breakCache.put(s, t);
        return t;
    }

    public static double ticksToBreak(BlockState s, float hardness, List<ItemStack> tools) {
        if (hardness < 0) return INF;
        if (hardness == 0) return 1;
        float bestSpeed = 1f;
        boolean harvest = !s.requiresCorrectToolForDrops();
        for (ItemStack t : tools) {
            float sp = t.getDestroySpeed(s);
            boolean ok = !s.requiresCorrectToolForDrops() || t.isCorrectToolForDrops(s);
            if (ok && !harvest) { harvest = true; bestSpeed = sp; } else if (ok == harvest || ok) {
                if (sp > bestSpeed) bestSpeed = sp;
            }
        }
        double perTick = bestSpeed / hardness / (harvest ? 30.0 : 100.0);
        return Math.ceil(1.0 / perTick);
    }

    /** Cost of breaking the block, INF if not allowed. */
    private double breakCost(int x, int y, int z) {
        if (!allowBreak) return INF;
        int f = fl(x, y, z);
        if ((f & BREAK) == 0) return INF;
        int up = fl(x, y + 1, z);
        if ((up & (LAVA | WATER)) != 0) return INF;
        for (int d = 0; d < 4; d++) {
            if ((fl(x + DX[d], y, z + DZ[d]) & (LAVA | WATER)) != 0) return INF;
        }
        double c = breakTicks(x, y, z) + 6;
        if ((up & FALLING) != 0) c += 2 * breakTicks(x, y + 1, z) + 12;
        return c;
    }

    /** Cost to make this block passable for the body. */
    private double clear(int x, int y, int z) {
        int f = fl(x, y, z);
        if ((f & (DANGER | UNLOADED)) != 0) return INF;
        if ((f & (PASS | WATER)) != 0) return 0;
        return breakCost(x, y, z);
    }

    private boolean lavaNear(int x, int y, int z) {
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d], nz = z + DZ[d];
            if ((fl(nx, y, nz) & LAVA) != 0 || (fl(nx, y + 1, nz) & LAVA) != 0) return true;
        }
        return (fl(x, y + 2, z) & LAVA) != 0 || (fl(x, y - 1, z) & LAVA) != 0;
    }

    // ---- expansion --------------------------------------------------------------

    private void relax(Node from, int x, int y, int z, double cost) {
        if (cost >= INF) return;
        if (y < minY + 1 || y > maxY - 2) return;
        if (lavaNear(x, y, z)) return;
        Node n = node(x, y, z);
        if (n.closed) return;
        double g = from.g + cost;
        if (g < n.g - 1e-6) {
            n.g = g;
            n.parent = from;
            n.h = goal.h(x, y, z);
            n.f = g + n.h * 1.25;
            open.add(n);
        }
    }

    private void expand(Node n) {
        final int x = n.x, y = n.y, z = n.z;
        final int feet = fl(x, y, z);
        final boolean inWater = (feet & WATER) != 0;

        for (int d = 0; d < 4; d++) {
            int dx = DX[d], dz = DZ[d];
            int nx = x + dx, nz = z + dz;

            double c0 = clear(nx, y, nz), c1 = clear(nx, y + 1, nz);
            if (c0 < INF && c1 < INF) {
                int destFeet = fl(nx, y, nz);
                int floor = fl(nx, y - 1, nz);
                if ((floor & STAND) != 0) {
                    double base = (c0 + c1 > 0) ? WALK : SPRINT;
                    if ((destFeet & WATER) != 0) base = SWIM;
                    relax(n, nx, y, nz, base + c0 + c1);
                } else if ((destFeet & WATER) != 0) {
                    // Surface swimming only.
                    if ((fl(nx, y + 1, nz) & WATER) == 0) relax(n, nx, y, nz, SWIM + c0 + c1);
                } else {
                    // No floor: either drop down or bridge across.
                    boolean dropped = descend(n, nx, y, nz, c0 + c1);
                    if (!dropped && canPlace && !inWater && (floor & PASS) != 0) {
                        relax(n, nx, y, nz, WALK + PLACE + c0 + c1);
                    } else if (canPlace && !inWater && (floor & PASS) != 0) {
                        // Bridging stays available as a pricier alternative to dropping.
                        relax(n, nx, y, nz, WALK + PLACE * 1.5 + c0 + c1);
                    }
                }
            }

            // Step up one block.
            if ((fl(nx, y, nz) & STAND) != 0) {
                double cu = clear(x, y + 2, z);
                if (cu < INF) {
                    double a0 = clear(nx, y + 1, nz);
                    if (a0 < INF) {
                        double a1 = clear(nx, y + 2, nz);
                        if (a1 < INF) relax(n, nx, y + 1, nz, WALK + JUMP + cu + a0 + a1);
                    }
                }
            } else if (c0 < INF && c1 < INF) {
                // Dig a descending staircase: break the block below the neighbour.
                int below = fl(nx, y - 1, nz);
                if ((below & (PASS | WATER | DANGER | UNLOADED)) == 0 && (fl(nx, y - 2, nz) & STAND) != 0) {
                    double cb = breakCost(nx, y - 1, nz);
                    if (cb < INF) relax(n, nx, y - 1, nz, WALK + 2 + c0 + c1 + cb);
                }
            }
        }

        // Diagonals over flat, open ground.
        if (!inWater) {
            for (int dx = -1; dx <= 1; dx += 2) {
                for (int dz = -1; dz <= 1; dz += 2) {
                    int nx = x + dx, nz = z + dz;
                    if ((fl(nx, y - 1, nz) & STAND) == 0) continue;
                    if (!open2(nx, y, nz) || !open2(nx, y, z) || !open2(x, y, nz)) continue;
                    relax(n, nx, y, nz, SPRINT * 1.414);
                }
            }
        }

        if (inWater) {
            if ((fl(x, y + 1, z) & WATER) != 0) relax(n, x, y + 1, z, 5);
            if ((fl(x, y - 1, z) & WATER) != 0) relax(n, x, y - 1, z, 6);
        } else {
            // Pillar up.
            if (canPlace) {
                double cu = clear(x, y + 2, z);
                int floor = fl(x, y - 1, z);
                if (cu < INF && (floor & (WATER | LAVA)) == 0) relax(n, x, y + 1, z, PILLAR + cu);
            }
        }
        // Dig straight down.
        int below = fl(x, y - 1, z);
        if ((below & (PASS | WATER | DANGER | UNLOADED)) == 0) {
            double cb = breakCost(x, y - 1, z);
            if (cb < INF) {
                for (int ny = y - 1; ny >= y - 1 - MAX_FALL + 1; ny--) {
                    int f = ny == y - 1 ? PASS : fl(x, ny, z);
                    if ((f & WATER) != 0) { relax(n, x, ny, z, cb + (y - ny) * 1.5); break; }
                    if ((f & PASS) == 0) break;
                    int under = fl(x, ny - 1, z);
                    if ((under & STAND) != 0) { relax(n, x, ny, z, cb + (y - ny) * 1.5); break; }
                    if ((under & (DANGER | UNLOADED)) != 0) break;
                }
            }
        }
    }

    private boolean open2(int x, int y, int z) {
        return (fl(x, y, z) & PASS) != 0 && (fl(x, y + 1, z) & PASS) != 0;
    }

    /** Walk off an edge into column (nx,nz) and fall. */
    private boolean descend(Node n, int nx, int y, int nz, double clearCost) {
        for (int ny = y - 1; ny >= y - 14; ny--) {
            int f = fl(nx, ny, nz);
            if ((f & WATER) != 0) {
                relax(n, nx, ny, nz, WALK + (y - ny) * 1.5 + clearCost);
                return true;
            }
            if ((f & PASS) == 0) return false;
            int under = fl(nx, ny - 1, nz);
            if ((under & STAND) != 0) {
                if (y - ny > MAX_FALL) return false;
                relax(n, nx, ny, nz, WALK + (y - ny) * 1.5 + clearCost);
                return true;
            }
            if ((under & (DANGER | UNLOADED)) != 0) return false;
        }
        return false;
    }
}
