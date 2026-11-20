package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import com.example.donutclient.bot.Locate;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;

/**
 * Collects the two Nether ingredients for eyes of ender: blaze rods (locate a fortress, find a blaze spawner,
 * camp next to it) or ender pearls (locate a warped forest and camp there for endermen).
 */
public final class NetherGatherTask extends Task {
    /** What to gather. */
    public enum Kind {
        BLAZE_RODS,
        PEARLS
    }

    private enum S {
        LOCATE,
        TRAVEL,
        SEARCH,
        HUNT
    }

    private final Kind kind;
    private final int want;
    private S state = S.LOCATE;
    private Locate.Query query;
    /** Centre of the fortress / warped forest (or the next camp site). */
    private BlockPos place;
    private Goal goal;
    private CampTask camp;
    private DeepTravelTask travel;
    /** Number of camps finished without reaching the wanted amount. */
    private int camps;
    private int searchIdx;
    private int ticks;
    private int travelFails;
    /** Number of locate queries that found nothing. */
    private int locateFails;
    /** True while travelling to a guessed position because the last locate query found nothing. */
    private boolean blind;
    /** Spawner the current camp was set up at, or null for a camp without a spawner. */
    private BlockPos campSpawner;
    /** {@link #have()} when the current camp started. */
    private int campStartHave;
    /** Spawners whose camp ended without enough rods (unreachable, not a blaze spawner, ...). */
    private final Set<BlockPos> badSpawners = new HashSet<>();
    /** Bot tick of the last spawner scan. */
    private long lastScan = -1000L;

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
        // the camp is ticked directly (not as a child), so it has to be told here: it resets the "sheltered"
        // reflex flag and the pathfinder's cover area, which would otherwise stay set for good
        if (camp != null) {
            camp.onInterrupted();
        }
        blind = false;
        state = S.LOCATE;
        query = null;
        camp = null;
        travel = null;
        goal = null;
    }

    @Override
    public boolean tick() {
        if (have() >= want) {
            bot.nav.reset();
            return true;
        }
        ticks++;
        String name = kind == Kind.BLAZE_RODS ? "nether fortress" : "warped forest";
        switch (state) {
            case LOCATE -> {
                bot.setStatus("locating a " + name);
                if (query == null) {
                    query = kind == Kind.BLAZE_RODS
                            ? Locate.structure(bot, BuiltinStructures.FORTRESS, 100)
                            : Locate.biome(bot, biome -> biome.is(Biomes.WARPED_FOREST), 4000);
                }
                if (!query.ready()) {
                    return false;
                }
                place = query.result();
                query = null;
                if (place == null) {
                    // Nothing found: move 300 blocks in a direction that changes with every failure and ask
                    // again from there (instead of pretending the guessed spot is the real thing).
                    double angle = locateFails++ * 2.1;
                    place = bot.feet().offset((int) (Math.cos(angle) * 300.0), 0, (int) (Math.sin(angle) * 300.0));
                    blind = true;
                    bot.say("Could not locate a " + name + " from here, looking further away.");
                } else {
                    blind = false;
                    bot.say("Heading to the " + name + " at " + place.getX() + ", " + place.getZ());
                }
                goal = Goal.xz(place.getX(), place.getZ(), 16.0);
                state = S.TRAVEL;
            }
            case TRAVEL -> {
                if (travel == null) {
                    travel = new DeepTravelTask(bot, place.getX(), place.getZ(), camps > 0 ? 6.0 : 16.0);
                }
                if (travel.tick()) {
                    // after a blind leg there is nothing to search here: run the locate query again. The same
                    // goes for a trip that gave up far from the target - searching "the fortress" from there
                    // would only walk rings around the wrong place.
                    boolean gaveUp = travel.failed && travel.remaining() > 64;
                    travel = null;
                    state = blind || gaveUp ? S.LOCATE : S.SEARCH;
                    blind = false;
                    goal = null;
                }
            }
            case SEARCH -> {
                if (kind == Kind.PEARLS) {
                    startHunt(place);
                    return false;
                }
                bot.setStatus("searching the fortress for a blaze spawner");
                // Scanning 17x17 chunks is too expensive to do every tick: once a second is plenty.
                if (bot.tick - lastScan >= 20L) {
                    lastScan = bot.tick;
                    List<BlockPos> spawners = bot.scan.find(state -> state.is(Blocks.SPAWNER), 8, 8);
                    for (BlockPos spawner : spawners) {
                        if (badSpawners.contains(spawner)) {
                            continue;
                        }
                        bot.say("Found a blaze spawner at " + spawner.toShortString());
                        startHunt(spawner);
                        return false;
                    }
                }
                if (goal == null) {
                    // Visit points on growing rings (8 per ring, 56 blocks apart) around the fortress position.
                    int ring = 1 + searchIdx / 8;
                    double angle = (searchIdx % 8) * Math.PI / 4.0;
                    goal = Goal.xz(place.getX() + (int) (Math.cos(angle) * 56.0 * ring),
                            place.getZ() + (int) (Math.sin(angle) * 56.0 * ring), 10.0);
                    searchIdx++;
                }
                Nav.Status status = bot.nav.goTo(goal);
                if (status != Nav.Status.RUNNING) {
                    goal = null;
                }
                if (searchIdx > 24) {
                    // No spawner anywhere: fight whatever blazes roam the fortress. Start the ring search over
                    // afterwards (and give the spawners that failed before another chance); without this every
                    // later pass came straight back here and camped on the same spot for ever.
                    searchIdx = 0;
                    badSpawners.clear();
                    goal = null;
                    startHunt(place);
                }
            }
            case HUNT -> {
                if (camp.tick()) {
                    if (have() >= want) {
                        return true;
                    }
                    camps++;
                    goal = null;
                    if (campSpawner != null && have() <= campStartHave) {
                        // this spawner gave nothing at all: look for another one before coming back to it
                        // (one that did give rods is simply camped at again)
                        badSpawners.add(campSpawner);
                    }
                    if (kind == Kind.PEARLS) {
                        // This spot dried up: move the camp 40 blocks in a new direction.
                        double angle = camps * 2.1;
                        place = place.offset((int) (Math.cos(angle) * 40.0), 0, (int) (Math.sin(angle) * 40.0));
                        state = S.TRAVEL;
                    } else {
                        state = S.SEARCH;
                    }
                }
            }
        }
        return false;
    }

    private double horizontalDistSq(BlockPos pos) {
        BlockPos feet = bot.feet();
        double dx = feet.getX() - pos.getX();
        double dz = feet.getZ() - pos.getZ();
        return dx * dx + dz * dz;
    }

    /** Starts the camp-and-fight phase at {@code pos} (a spawner for blazes, otherwise a general area). */
    private void startHunt(BlockPos pos) {
        campSpawner = null;
        campStartHave = have();
        if (kind == Kind.BLAZE_RODS) {
            boolean spawner = bot.level().getBlockState(pos).is(Blocks.SPAWNER);
            if (spawner) {
                campSpawner = pos;
            }
            camp = new CampTask(bot, CampTask.Mode.BOX, "blazes", spawner ? pos.below(2) : bot.feet(),
                    entity -> entity instanceof Blaze, stack -> stack.is(Items.BLAZE_ROD), () -> have() >= want);
        } else {
            camp = new CampTask(bot, CampTask.Mode.ROOF, "endermen", campSpot(),
                    entity -> entity instanceof EnderMan, stack -> stack.is(Items.ENDER_PEARL), () -> have() >= want);
            camp.patience = 1800;
        }
        state = S.HUNT;
        bot.nav.reset();
    }

    /**
     * Nearest position (within 10 blocks) that is the centre of a flat, solid 5x5 floor with 4 blocks of clear
     * headroom - somewhere an enderman roof can be built. Falls back to the bot's own position.
     */
    private BlockPos campSpot() {
        BlockPos feet = bot.feet();
        ClientLevel level = bot.level();
        BlockPos best = feet;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-10, -3, -10), feet.offset(10, 3, 10))) {
            boolean ok = true;
            for (int dx = -2; dx <= 2 && ok; dx++) {
                for (int dz = -2; dz <= 2 && ok; dz++) {
                    BlockPos cell = pos.offset(dx, 0, dz);
                    if (!level.getBlockState(cell.below()).isCollisionShapeFullBlock(level, cell.below())) {
                        ok = false;
                    }
                    for (int dy = 0; dy <= 3 && ok; dy++) {
                        BlockState state = level.getBlockState(cell.above(dy));
                        if (!state.getFluidState().isEmpty() || (!state.isAir() && !state.canBeReplaced())) {
                            ok = false;
                        }
                    }
                }
            }
            if (!ok) {
                continue;
            }
            double dist = pos.distSqr(feet);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.immutable();
            }
        }
        return best;
    }
}
