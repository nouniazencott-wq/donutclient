package com.example.donutclient.modules;

import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.example.donutclient.render.World;
import com.example.donutclient.setting.BoolSetting;
import com.example.donutclient.setting.NumSetting;
import com.example.donutclient.util.BlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** Outlines chests, shulkers, spawners and other containers through walls. */
public class StorageEspModule extends Module {
    private static final int MAX_BOXES = 600;

    private final NumSetting range = add(new NumSetting("Range", 96, 16, 256, 8));
    private final BoolSetting chests = add(new BoolSetting("Chests", true));
    private final BoolSetting shulkers = add(new BoolSetting("Shulkers", true));
    private final BoolSetting spawners = add(new BoolSetting("Spawners", true));
    private final BoolSetting other = add(new BoolSetting("Other", false));

    private record Box(AABB box, int color) {}

    private List<Box> boxes = new ArrayList<>();
    private int timer;

    public StorageEspModule() {
        super("Storage ESP", "Shows containers and spawners through walls.", Category.WORLD, -1);
    }

    @Override
    protected void onDisable() {
        boxes = new ArrayList<>();
    }

    private boolean wanted(String n) {
        switch (n) {
            case "chest":
            case "trapped_chest":
            case "barrel":
            case "ender_chest": return chests.get();
            case "shulker_box": return shulkers.get();
            case "mob_spawner": return spawners.get();
            default: return other.get();
        }
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (timer++ % 10 != 0) return;

        List<Box> next = new ArrayList<>();
        double r = range.get();
        double r2 = r * r;
        BlockPos me = mc.player.blockPosition();
        int cr = (int) Math.ceil(r / 16.0);
        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();
        outer:
        for (int cx = pcx - cr; cx <= pcx + cr; cx++) {
            for (int cz = pcz - cr; cz <= pcz + cr; cz++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (p.distSqr(me) > r2) continue;
                    String n = BlockEntities.name(be);
                    int color = BlockEntities.color(n);
                    if (color == 0 || !wanted(n)) continue;
                    next.add(new Box(new AABB(p), color));
                    if (next.size() >= MAX_BOXES) break outer;
                }
            }
        }
        boxes = next;
    }

    @Override
    public void onRenderWorld(float partialTick) {
        for (Box b : boxes) {
            World.box(b.box(), b.color(), 2f, World.alpha(b.color(), 30));
        }
    }
}
