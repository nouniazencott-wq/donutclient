package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

import java.util.List;

/**
 * The two Nether errands: blaze rods from a fortress, ender pearls from the
 * endermen of a warped forest. Both are "find the place, go there, hunt".
 */
public final class NetherGatherTask extends Task {
    public enum Kind { BLAZE_RODS, PEARLS }

    private enum S { LOCATE, TRAVEL, SEARCH, HUNT }

    private final Kind kind;
    private final int want;
    private S state = S.LOCATE;
    private Locate.Query query;
    private BlockPos place;
    private Goal goal;
    private CampTask camp;
    private DeepTravelTask travel;
    private int camps;
    private int searchIdx;
    private int ticks;
    private int travelFails;

    public NetherGatherTask(Bot bot, Kind kind, int want) {
        super(bot);
        this.kind = kind;
        this.want = want;
    }

    private int have() {
        return kind == Kind.BLAZE_RODS ? bot.inv.count(Items.BLAZE_ROD) : bot.inv.count(Items.ENDER_PEARL);
    }

    @Override
    public void onInterrupted() {
        super.onInterrupted();
        state = S.LOCATE;
        query = null;
        camp = null;
        travel = null;
        goal = null;
    }

    @Override
    public boolean tick() {
        if (have() >= want) { bot.nav.reset(); return true; }
        ticks++;
        String what = kind == Kind.BLAZE_RODS ? "nether fortress" : "warped forest";
        switch (state) {
            case LOCATE -> {
                bot.setStatus("locating a " + what);
                if (query == null) {
                    query = kind == Kind.BLAZE_RODS
                            ? Locate.structure(bot, BuiltinStructures.FORTRESS, 100)
                            : Locate.biome(bot, b -> b.is(Biomes.WARPED_FOREST), 4000);
                }
                if (!query.ready()) return false;
                place = query.result();
                query = null;
                if (place == null) {
                    bot.say("Could not locate a " + what + ".");
                    place = bot.feet().offset(300, 0, 0);
                }
                bot.say("Heading to the " + what + " at " + place.getX() + ", " + place.getZ());
                goal = Goal.xz(place.getX(), place.getZ(), 16);
                state = S.TRAVEL;
            }
            case TRAVEL -> {
                if (travel == null) travel = new DeepTravelTask(bot, place.getX(), place.getZ(), camps > 0 ? 6 : 16);
                if (travel.tick()) {
                    travel = null;
                    state = S.SEARCH;
                    goal = null;
                }
            }
            case SEARCH -> {
                if (kind == Kind.PEARLS) { startHunt(place); return false; }
                bot.setStatus("searching the fortress for a blaze spawner");
                List<BlockPos> spawners = bot.scan.find(s -> s.is(Blocks.SPAWNER), 8, 4);
                if (!spawners.isEmpty()) {
                    bot.say("Found a blaze spawner at " + spawners.get(0).toShortString());
                    startHunt(spawners.get(0));
                    return false;
                }
                // Walk a widening pattern around the fortress origin until a spawner shows up.
                if (goal == null) {
                    int ring = 1 + searchIdx / 8;
                    double ang = (searchIdx % 8) * Math.PI / 4;
                    goal = Goal.xz(place.getX() + (int) (Math.cos(ang) * 56 * ring),
                            place.getZ() + (int) (Math.sin(ang) * 56 * ring), 10);
                    searchIdx++;
                }
                Nav.Status st = bot.nav.goTo(goal);
                if (st != Nav.Status.RUNNING) goal = null;
                if (searchIdx > 24) {
                    // Give up on a spawner; blazes also roam the fortress.
                    startHunt(place);
                }
            }
            case HUNT -> {
                if (camp.tick()) {
                    if (have() >= want) return true;
                    // This spot dried up: move on and set up again.
                    camps++;
                    goal = null;
                    if (kind == Kind.PEARLS) {
                        double ang = camps * 2.1;
                        place = place.offset((int) (Math.cos(ang) * 40), 0, (int) (Math.sin(ang) * 40));
                        state = S.TRAVEL;
                    } else state = S.SEARCH;
                }
            }
        }
        return false;
    }

    private double horizontalDistSq(BlockPos p) {
        BlockPos f = bot.feet();
        double dx = f.getX() - p.getX(), dz = f.getZ() - p.getZ();
        return dx * dx + dz * dz;
    }

    private void startHunt(BlockPos home) {
        if (kind == Kind.BLAZE_RODS) {
            boolean spawner = bot.level().getBlockState(home).is(Blocks.SPAWNER);
            // Dug into the brickwork right under the spawner: blazes appear within reach but never get a shot.
            camp = new CampTask(bot, CampTask.Mode.BOX, "blazes", spawner ? home.below(2) : bot.feet(),
                    e -> e instanceof Blaze, s -> s.is(Items.BLAZE_ROD), () -> have() >= want);
        } else {
            camp = new CampTask(bot, CampTask.Mode.ROOF, "endermen", campSpot(),
                    e -> e instanceof EnderMan, s -> s.is(Items.ENDER_PEARL), () -> have() >= want);
            camp.patience = 20 * 90;
        }
        state = S.HUNT;
        bot.nav.reset();
    }

    /** Open, level ground near the bot for an enderman shelter. */
    private BlockPos campSpot() {
        BlockPos f = bot.feet();
        var level = bot.level();
        BlockPos best = f;
        double bestD = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(f.offset(-10, -3, -10), f.offset(10, 3, 10))) {
            boolean ok = true;
            for (int dx = -2; dx <= 2 && ok; dx++) {
                for (int dz = -2; dz <= 2 && ok; dz++) {
                    BlockPos b = q.offset(dx, 0, dz);
                    if (!level.getBlockState(b.below()).isCollisionShapeFullBlock(level, b.below())) ok = false;
                    for (int dy = 0; dy <= 3 && ok; dy++) {
                        var st = level.getBlockState(b.above(dy));
                        if (!st.getFluidState().isEmpty() || (!st.isAir() && !st.canBeReplaced())) ok = false;
                    }
                }
            }
            if (!ok) continue;
            double d = q.distSqr(f);
            if (d < bestD) { bestD = d; best = q.immutable(); }
        }
        return best;
    }
}
