package com.example.donutclient.modules;

import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.example.donutclient.render.World;
import com.example.donutclient.setting.BoolSetting;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Boxes, tracers and name tags for other players, visible through walls. */
public class PlayerEspModule extends Module {
    private final BoolSetting boxes = add(new BoolSetting("Boxes", true));
    private final BoolSetting tracers = add(new BoolSetting("Tracers", true));
    private final BoolSetting names = add(new BoolSetting("Names", true));

    public PlayerEspModule() {
        super("Player ESP", "Shows other players through walls.", Category.RENDER, -1);
    }

    @Override
    public void onRenderWorld(float partialTick) {
        if (mc.level == null || mc.player == null) return;
        Vec3 cam = World.cameraPos();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            Vec3 pos = p.getPosition(partialTick);
            double dist = pos.distanceTo(cam);
            int color = dist < 16 ? 0xFFFF3030 : dist < 48 ? 0xFFFFAA00 : 0xFF55FF55;
            double w = p.getBbWidth() / 2.0;
            double h = p.getBbHeight();
            if (boxes.get()) {
                World.box(new AABB(pos.x - w, pos.y, pos.z - w, pos.x + w, pos.y + h, pos.z + w), color, 2.5f);
            }
            if (tracers.get()) {
                World.tracer(pos.add(0, h / 2, 0), color, 2f);
            }
            if (names.get()) {
                String label = p.getName().getString() + "  " + (int) p.getHealth() + "hp  " + (int) dist + "m";
                World.text(label, pos.add(0, h + 0.6 + dist * 0.02, 0), 0xFFFFFFFF);
            }
        }
    }
}
