package com.example.donutclient.bot;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Asks the integrated server where things are (what /locate does). This is
 * the bot's substitute for a human's wandering and Eye of Ender throws, and
 * the reason it is singleplayer-only. Lookups run on the server thread.
 */
public final class Locate {
    private Locate() {}

    /** A lookup in flight. Poll {@link #ready()} then read {@link #result()} (null = not found). */
    public static final class Query {
        private final CompletableFuture<BlockPos> future;

        private Query(CompletableFuture<BlockPos> f) { this.future = f; }

        public boolean ready() { return future.isDone(); }

        public BlockPos result() {
            try {
                return future.isDone() ? future.getNow(null) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    private static Query run(Bot bot, java.util.function.Function<ServerLevel, BlockPos> fn) {
        MinecraftServer server = bot.mc.getSingleplayerServer();
        if (server == null || bot.level() == null) return new Query(CompletableFuture.completedFuture(null));
        ResourceKey<Level> dim = bot.level().dimension();
        return new Query(server.submit(() -> {
            ServerLevel level = server.getLevel(dim);
            return level == null ? null : fn.apply(level);
        }));
    }

    public static Query biome(Bot bot, Predicate<Holder<Biome>> test, int radius) {
        BlockPos origin = bot.feet();
        return run(bot, level -> {
            Pair<BlockPos, Holder<Biome>> r = level.findClosestBiome3d(test, origin, radius, 32, 64);
            return r == null ? null : r.getFirst();
        });
    }

    public static Query structure(Bot bot, ResourceKey<Structure> key, int radiusChunks) {
        BlockPos origin = bot.feet();
        return run(bot, level -> {
            var reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var holder = reg.get(key);
            if (holder.isEmpty()) return null;
            Pair<BlockPos, Holder<Structure>> r = level.getChunkSource().getGenerator()
                    .findNearestMapStructure(level, HolderSet.direct(holder.get()), origin, radiusChunks, false);
            return r == null ? null : r.getFirst();
        });
    }
}
