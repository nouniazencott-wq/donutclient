package com.example.donutclient.bot;

import com.mojang.datafixers.util.Pair;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Asks the integrated server where the nearest biome or structure is - the
 * same lookups the {@code /locate} command performs. This only works in
 * singleplayer, which is the only place the bot runs. The search is executed
 * on the server thread and polled from the client through a {@link Query}.
 */
public final class Locate {
    private Locate() {}

    /** Handle to a search that is still running on the server thread. */
    public static final class Query {
        private final CompletableFuture<BlockPos> future;

        private Query(CompletableFuture<BlockPos> f) {
            this.future = f;
        }

        public boolean ready() {
            return future.isDone();
        }

        /** The found position, or null if nothing was found, the search failed or it is not ready yet. */
        public BlockPos result() {
            try {
                return future.isDone() ? future.getNow(null) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Runs {@code fn} on the server thread against the server-side copy of the bot's current dimension. */
    private static Query run(Bot bot, Function<ServerLevel, BlockPos> fn) {
        MinecraftServer server = bot.mc.getSingleplayerServer();
        if (server == null || bot.level() == null) {
            return new Query(CompletableFuture.completedFuture(null));
        }
        ResourceKey<Level> dim = bot.level().dimension();
        return new Query(server.submit(() -> {
            ServerLevel level = server.getLevel(dim);
            return level == null ? null : fn.apply(level);
        }));
    }

    /** Nearest biome matching {@code test} within {@code radius} blocks of the bot. */
    public static Query biome(Bot bot, Predicate<Holder<Biome>> test, int radius) {
        BlockPos origin = bot.feet();
        return run(bot, level -> {
            Pair<BlockPos, Holder<Biome>> r = level.findClosestBiome3d(test, origin, radius, 32, 64);
            return r == null ? null : r.getFirst();
        });
    }

    /** Nearest structure of the given type within {@code radiusChunks} chunks of the bot. */
    public static Query structure(Bot bot, ResourceKey<Structure> key, int radiusChunks) {
        BlockPos origin = bot.feet();
        return run(bot, level -> {
            Registry<Structure> reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            Optional<Holder.Reference<Structure>> holder = reg.get(key);
            if (holder.isEmpty()) {
                return null;
            }
            Pair<BlockPos, Holder<Structure>> r = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, HolderSet.direct(holder.get()), origin, radiusChunks, false);
            return r == null ? null : r.getFirst();
        });
    }
}
