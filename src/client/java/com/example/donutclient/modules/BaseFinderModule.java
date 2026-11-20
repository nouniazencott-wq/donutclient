package com.example.donutclient.modules;

import com.example.donutclient.DonutClient;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.example.donutclient.render.World;
import com.example.donutclient.setting.BoolSetting;
import com.example.donutclient.setting.NumSetting;
import com.example.donutclient.util.BlockEntities;
import com.example.donutclient.util.Chat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans every loaded chunk for clusters of player-made block entities
 * (storage, hoppers, spawners, beacons...) and scores each chunk. Chunks over
 * the threshold are marked as a possible base: highlighted through walls,
 * announced in chat, listed on the HUD and appended to
 * config/donutclient-bases.csv so finds survive a restart.
 *
 * It only reads data the server already sent to the client, so it can't see
 * anything the server hides (anti-xray / block-entity obfuscation).
 */
public class BaseFinderModule extends Module {
    private static final int CHUNKS_PER_TICK = 24;
    private static final Path LOG = FabricLoader.getInstance().getConfigDir().resolve("donutclient-bases.csv");

    private final NumSetting minScore = add(new NumSetting("Min Score", 12, 4, 100, 1));
    private final NumSetting maxY = add(new NumSetting("Max Y", 320, -64, 320, 8));
    private final BoolSetting tracers = add(new BoolSetting("Tracers", true));
    private final BoolSetting chatAlert = add(new BoolSetting("Chat Alert", true));
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting hudList = add(new BoolSetting("HUD List", true));
    private final BoolSetting logFile = add(new BoolSetting("Log To File", true));

    /** One flagged chunk. */
    public record Find(String dim, int cx, int cz, int score, int storage, int spawners, AABB box) {
        public Vec3 center() {
            return box.getCenter();
        }
    }

    private final Map<String, Find> finds = new LinkedHashMap<>();
    private final Set<String> logged = new HashSet<>();
    private ClientLevel lastLevel;
    private String dim = "";
    private int cursor;

    public BaseFinderModule() {
        super("Base Finder", "Flags chunks packed with storage, spawners and other player-made blocks.",
                Category.WORLD, -1);
    }

    @Override
    protected void onEnable() {
        cursor = 0;
    }

    @Override
    public void onTick() {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        if (level != lastLevel) {
            lastLevel = level;
            dim = level.dimension().identifier().toString();
            cursor = 0;
        }

        int r = mc.options.getEffectiveRenderDistance();
        int side = 2 * r + 1;
        int total = side * side;
        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            int idx = Math.floorMod(cursor++, total);
            int cx = pcx - r + idx % side;
            int cz = pcz - r + idx / side;
            LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk != null) scan(chunk, cx, cz);
        }
    }

    private void scan(LevelChunk chunk, int cx, int cz) {
        float score = 0;
        int storage = 0, spawners = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxYy = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int limitY = maxY.getInt();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos p = be.getBlockPos();
            if (p.getY() > limitY) continue;
            String n = BlockEntities.name(be);
            float w = BlockEntities.baseWeight(n);
            if (w <= 0) continue;
            score += w;
            if (BlockEntities.isStorage(n)) storage++;
            if (BlockEntities.isSpawner(n)) spawners++;
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxYy = Math.max(maxYy, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }

        String key = dim + ":" + cx + ":" + cz;
        if (score < minScore.get()) {
            finds.remove(key);
            return;
        }
        AABB box = new AABB(minX, minY, minZ, maxX + 1, maxYy + 1, maxZ + 1).inflate(0.5);
        Find find = new Find(dim, cx, cz, Math.round(score), storage, spawners, box);
        boolean isNew = finds.put(key, find) == null;
        if (isNew) announce(key, find);
    }

    private void announce(String key, Find f) {
        Vec3 c = f.center();
        String where = String.format("%d %d %d", (int) c.x, (int) c.y, (int) c.z);
        if (chatAlert.get()) {
            Chat.info("Possible base at " + where + " - score " + f.score()
                    + " (" + f.storage() + " storage, " + f.spawners() + " spawners)");
        }
        if (sound.get()) Chat.ping();
        if (logFile.get() && logged.add(key)) {
            ServerData server = mc.getCurrentServer();
            String line = String.join(",",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    server == null ? "singleplayer" : server.ip,
                    f.dim(), Integer.toString((int) c.x), Integer.toString((int) c.y), Integer.toString((int) c.z),
                    Integer.toString(f.score()), Integer.toString(f.storage()), Integer.toString(f.spawners()));
            try {
                if (!Files.exists(LOG)) {
                    Files.createDirectories(LOG.getParent());
                    Files.writeString(LOG, "time,server,dimension,x,y,z,score,storage,spawners\n");
                }
                Files.writeString(LOG, line + "\n", StandardOpenOption.APPEND);
            } catch (IOException e) {
                DonutClient.LOGGER.error("Could not write base log", e);
            }
        }
    }

    /** Finds in the current dimension, nearest first. */
    private List<Find> nearest(Vec3 from) {
        List<Find> out = new ArrayList<>();
        for (Find f : finds.values()) {
            if (f.dim().equals(dim)) out.add(f);
        }
        out.sort(Comparator.comparingDouble(f -> f.center().distanceToSqr(from)));
        return out;
    }

    private static int color(Find f) {
        return f.spawners() > 1 ? 0xFFFF3030 : 0xFFFFAA00;
    }

    @Override
    public void onRenderWorld(float partialTick) {
        if (mc.player == null) return;
        List<Find> list = nearest(World.cameraPos());
        int shown = 0;
        for (Find f : list) {
            int c = color(f);
            World.box(f.box(), c, 3f, World.alpha(c, 40));
            Vec3 center = f.center();
            World.text("Base? score " + f.score(), new Vec3(center.x, f.box().maxY + 1.5, center.z), c);
            if (tracers.get() && shown < 8) World.tracer(center, c, 2.5f);
            shown++;
        }
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        if (!hudList.get() || mc.player == null) return;
        Vec3 me = mc.player.position();
        List<Find> list = nearest(me);
        int y = 44;
        ctx.label("Base Finder: " + list.size() + " found", 4, y, 0xFFFFAA00);
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            Find f = list.get(i);
            Vec3 c = f.center();
            y += ctx.font.lineHeight + 5;
            ctx.label(String.format("%d %d %d  score %d  %dm",
                    (int) c.x, (int) c.y, (int) c.z, f.score(), (int) Math.sqrt(c.distanceToSqr(me))),
                    4, y, color(f));
        }
    }
}
