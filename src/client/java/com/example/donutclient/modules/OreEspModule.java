package com.example.donutclient.modules;

import com.example.donutclient.esp.OreType;
import com.example.donutclient.hud.HudContext;
import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block ESP for ores: draws a coloured box around every selected ore near the
 * player (visible through walls) and a tracer line from the crosshair to the
 * closest veins. The world itself is rendered normally — nothing is made
 * transparent.
 *
 * How it works:
 *  - onTick scans a few loaded chunks per tick (palette check first, so
 *    sections without any wanted ore cost almost nothing) and keeps the hits
 *    per chunk. Once every chunk in range was visited it starts over, which
 *    is how mined blocks disappear and newly loaded chunks show up.
 *  - onRenderWorld submits vanilla "gizmos" (the debug-shape renderer added
 *    in 1.21.11) flagged always-on-top.
 *
 * Which ores are shown is chosen in OreEspScreen and saved in the config.
 */
public class OreEspModule extends Module {
    public static final int[] RADIUS_CHOICES = {2, 4, 6, 8};

    private static final int CHUNKS_PER_TICK = 4;
    private static final int MAX_BOXES = 400;
    private static final int MAX_TRACERS = 12;
    /** Hits closer than this to an already-traced hit count as the same vein. */
    private static final int VEIN_DISTANCE = 4;

    public record Hit(BlockPos pos, OreType type) {}

    private final Set<OreType> selected = EnumSet.noneOf(OreType.class);
    private boolean tracers = true;
    private int chunkRadius = 4;

    private final Map<Block, OreType> lookup = new IdentityHashMap<>();
    private final Map<Long, List<Hit>> hitsByChunk = new HashMap<>();
    private final ArrayDeque<Long> scanQueue = new ArrayDeque<>();
    private ClientLevel scannedLevel;

    /** Nearest-first snapshot rebuilt every tick; read by the renderer. */
    private volatile List<Hit> visible = List.of();
    private final Map<OreType, Integer> counts = new EnumMap<>(OreType.class);

    public OreEspModule() {
        super("Ore ESP", "Boxes and tracers on the ores you pick.", Category.RENDER, GLFW.GLFW_KEY_G);
        for (OreType t : OreType.values()) {
            if (t.enabledByDefault) selected.add(t);
        }
        rebuildLookup();
    }

    // ---- settings ---------------------------------------------------------

    public boolean isSelected(OreType type) {
        return selected.contains(type);
    }

    public void setSelected(OreType type, boolean on) {
        if (on ? selected.add(type) : selected.remove(type)) {
            rebuildLookup();
            reset();
        }
    }

    public boolean tracersEnabled() { return tracers; }
    public void setTracers(boolean on) { tracers = on; }

    public int getChunkRadius() { return chunkRadius; }

    public void setChunkRadius(int radius) {
        chunkRadius = Math.clamp(radius, 1, 16);
        reset();
    }

    private void rebuildLookup() {
        lookup.clear();
        for (OreType t : selected) {
            for (Block b : t.blocks) lookup.put(b, t);
        }
    }

    private void reset() {
        hitsByChunk.clear();
        scanQueue.clear();
        visible = List.of();
        counts.clear();
    }

    @Override
    public void writeSettings(JsonObject json) {
        JsonObject ores = new JsonObject();
        for (OreType t : OreType.values()) ores.addProperty(t.id(), selected.contains(t));
        json.add("ores", ores);
        json.addProperty("tracers", tracers);
        json.addProperty("chunkRadius", chunkRadius);
    }

    @Override
    public void readSettings(JsonObject json) {
        if (json.has("ores")) {
            JsonObject ores = json.getAsJsonObject("ores");
            for (OreType t : OreType.values()) {
                if (!ores.has(t.id())) continue;
                if (ores.get(t.id()).getAsBoolean()) selected.add(t); else selected.remove(t);
            }
            rebuildLookup();
        }
        if (json.has("tracers")) tracers = json.get("tracers").getAsBoolean();
        if (json.has("chunkRadius")) chunkRadius = Math.clamp(json.get("chunkRadius").getAsInt(), 1, 16);
        reset();
    }

    // ---- scanning -----------------------------------------------------------

    @Override
    protected void onDisable() {
        reset();
        scannedLevel = null;
    }

    @Override
    public void onTick() {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            reset();
            scannedLevel = null;
            return;
        }
        if (level != scannedLevel) { // joined a world / changed dimension
            reset();
            scannedLevel = level;
        }
        if (lookup.isEmpty()) return;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;

        if (scanQueue.isEmpty()) startScanPass(pcx, pcz);

        for (int i = 0; i < CHUNKS_PER_TICK && !scanQueue.isEmpty(); i++) {
            long key = scanQueue.poll();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                hitsByChunk.remove(key);
            } else {
                hitsByChunk.put(key, scanChunk(level, chunk));
            }
        }

        rebuildVisible();
    }

    /** Queue every chunk in range, nearest first, and forget chunks we left behind. */
    private void startScanPass(int pcx, int pcz) {
        int r = chunkRadius;
        hitsByChunk.keySet().removeIf(key ->
                Math.abs(ChunkPos.getX(key) - pcx) > r || Math.abs(ChunkPos.getZ(key) - pcz) > r);

        List<long[]> order = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                order.add(new long[] {ChunkPos.pack(pcx + dx, pcz + dz), (long) dx * dx + (long) dz * dz});
            }
        }
        order.sort(Comparator.comparingLong(a -> a[1]));
        for (long[] entry : order) scanQueue.add(entry[0]);
    }

    private List<Hit> scanChunk(ClientLevel level, LevelChunk chunk) {
        List<Hit> hits = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            // Cheap palette test: skips the 4096-block loop for most sections.
            if (!section.maybeHas(state -> lookup.containsKey(state.getBlock()))) continue;

            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        OreType type = lookup.get(state.getBlock());
                        if (type != null) {
                            hits.add(new Hit(new BlockPos(baseX + x, baseY + y, baseZ + z), type));
                        }
                    }
                }
            }
        }
        return hits;
    }

    private void rebuildVisible() {
        Vec3 eye = mc.player.getEyePosition();
        List<Hit> all = new ArrayList<>();
        counts.clear();
        for (List<Hit> hits : hitsByChunk.values()) {
            for (Hit h : hits) {
                all.add(h);
                counts.merge(h.type(), 1, Integer::sum);
            }
        }
        all.sort(Comparator.comparingDouble(h -> h.pos().distToCenterSqr(eye)));
        visible = List.copyOf(all.size() > MAX_BOXES ? all.subList(0, MAX_BOXES) : all);
    }

    // ---- rendering ------------------------------------------------------------

    @Override
    public void onRenderWorld(LevelExtractionContext ctx) {
        List<Hit> hits = visible;
        if (hits.isEmpty()) return;

        for (Hit h : hits) {
            GizmoStyle style = GizmoStyle.strokeAndFill(h.type().argb(0xFF), 2.0f, h.type().argb(0x30));
            Gizmos.cuboid(new AABB(h.pos()), style).setAlwaysOnTop();
        }

        if (!tracers) return;

        // Start a little in front of the camera so every line fans out from the crosshair.
        Camera camera = ctx.camera();
        Vector3fc forward = camera.forwardVector();
        Vec3 start = camera.position().add(forward.x(), forward.y(), forward.z());

        // One tracer per vein: hits are nearest-first, so skip any hit that sits
        // next to one we already drew a line to.
        List<Hit> traced = new ArrayList<>();
        for (Hit h : hits) {
            if (traced.size() >= MAX_TRACERS) break;
            boolean sameVein = false;
            for (Hit t : traced) {
                if (t.type() == h.type() && t.pos().distManhattan(h.pos()) <= VEIN_DISTANCE) {
                    sameVein = true;
                    break;
                }
            }
            if (sameVein) continue;
            traced.add(h);
            Gizmos.line(start, Vec3.atCenterOf(h.pos()), h.type().argb(0xFF), 1.5f).setAlwaysOnTop();
        }
    }

    @Override
    public void onRenderHud(HudContext ctx) {
        StringBuilder sb = new StringBuilder("Ore ESP");
        if (selected.isEmpty()) {
            sb.append(": no ores selected");
        } else if (counts.isEmpty()) {
            sb.append(": nothing nearby");
        } else {
            String sep = ": ";
            for (Map.Entry<OreType, Integer> e : counts.entrySet()) {
                sb.append(sep).append(e.getValue()).append(' ').append(e.getKey().displayName);
                sep = ", ";
            }
        }
        // Third line, under FPS and Coordinates.
        ctx.label(sb.toString(), 4, 4 + (ctx.font.lineHeight + 6) * 2, 0xFF55FFFF);
    }
}
