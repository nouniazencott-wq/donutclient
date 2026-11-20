package com.example.donutclient.bot.path;

import net.minecraft.core.BlockPos;

import java.util.List;

/** A pathfinding target. */
public interface Goal {
    boolean at(int x, int y, int z);

    /** Estimated remaining cost in ticks (should not grossly overestimate). */
    double h(int x, int y, int z);

    double SPRINT = 3.56;

    static double metric(int dx, int dy, int dz) {
        dx = Math.abs(dx);
        dz = Math.abs(dz);
        double hor = (Math.max(dx, dz) - Math.min(dx, dz)) + Math.min(dx, dz) * 1.414;
        return hor * SPRINT + (dy > 0 ? dy * 6.0 : -dy * 1.5);
    }

    /** Stand exactly in this block. */
    static Goal block(BlockPos p) {
        final int gx = p.getX(), gy = p.getY(), gz = p.getZ();
        return new Goal() {
            public boolean at(int x, int y, int z) { return x == gx && y == gy && z == gz; }
            public double h(int x, int y, int z) { return metric(gx - x, gy - y, gz - z); }
            public String toString() { return "block " + gx + "," + gy + "," + gz; }
        };
    }

    /** Get within r blocks (euclidean) of p. */
    static Goal near(BlockPos p, double r) {
        final int gx = p.getX(), gy = p.getY(), gz = p.getZ();
        final double r2 = r * r;
        return new Goal() {
            public boolean at(int x, int y, int z) {
                double dx = x - gx, dy = y - gy, dz = z - gz;
                return dx * dx + dy * dy + dz * dz <= r2;
            }
            public double h(int x, int y, int z) { return Math.max(0, metric(gx - x, gy - y, gz - z) - r * SPRINT); }
            public String toString() { return "near " + gx + "," + gy + "," + gz + " r" + r; }
        };
    }

    /** Stand somewhere from which the block is within arm's reach. */
    static Goal reach(BlockPos p, double range) {
        final double cx = p.getX() + 0.5, cy = p.getY() + 0.5, cz = p.getZ() + 0.5;
        final int gx = p.getX(), gy = p.getY(), gz = p.getZ();
        final double r2 = range * range;
        return new Goal() {
            public boolean at(int x, int y, int z) {
                if (x == gx && z == gz && (y == gy || y == gy - 1)) return false; // not inside it
                double dx = x + 0.5 - cx, dy = y + 1.62 - cy, dz = z + 0.5 - cz;
                return dx * dx + dy * dy + dz * dz <= r2;
            }
            public double h(int x, int y, int z) {
                return Math.max(0, metric(gx - x, gy - 1 - y, gz - z) - range * SPRINT);
            }
            public String toString() { return "reach " + gx + "," + gy + "," + gz; }
        };
    }

    /** Reach a column, any height. */
    static Goal xz(int gx, int gz, double r) {
        final double r2 = r * r;
        return new Goal() {
            public boolean at(int x, int y, int z) {
                double dx = x - gx, dz = z - gz;
                return dx * dx + dz * dz <= r2;
            }
            public double h(int x, int y, int z) { return Math.max(0, metric(gx - x, 0, gz - z) - r * SPRINT); }
            public String toString() { return "xz " + gx + "," + gz; }
        };
    }

    /** Reach a given height (used for digging down / surfacing). */
    static Goal y(int gy) {
        return new Goal() {
            public boolean at(int x, int y, int z) { return y == gy; }
            public double h(int x, int y, int z) { return metric(0, gy - y, 0); }
            public String toString() { return "y " + gy; }
        };
    }

    static Goal any(List<Goal> goals) {
        return new Goal() {
            public boolean at(int x, int y, int z) {
                for (Goal g : goals) if (g.at(x, y, z)) return true;
                return false;
            }
            public double h(int x, int y, int z) {
                double best = Double.MAX_VALUE;
                for (Goal g : goals) best = Math.min(best, g.h(x, y, z));
                return best;
            }
            public String toString() { return "any of " + goals.size(); }
        };
    }
}
