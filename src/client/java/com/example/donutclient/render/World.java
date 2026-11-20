package com.example.donutclient.render;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * In-world drawing helpers. Everything goes through vanilla's Gizmos system
 * (the same one the debug renderers use) flagged "always on top", so shapes
 * are visible through walls. Only valid inside Module#onRenderWorld.
 */
public final class World {
    /** Lines to far-away targets are clamped to this length so they never clip out. */
    private static final double MAX_TRACER = 96.0;

    private World() {}

    public static Vec3 cameraPos() {
        return Minecraft.getInstance().gameRenderer.mainCamera().position();
    }

    /** Outlined box, visible through walls. Colors are ARGB. */
    public static void box(AABB box, int strokeArgb, float width) {
        Gizmos.cuboid(box, GizmoStyle.stroke(strokeArgb, width)).setAlwaysOnTop();
    }

    /** Outlined + translucent filled box, visible through walls. */
    public static void box(AABB box, int strokeArgb, float width, int fillArgb) {
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(strokeArgb, width, fillArgb)).setAlwaysOnTop();
    }

    public static void line(Vec3 from, Vec3 to, int argb, float width) {
        Gizmos.line(from, to, argb, width).setAlwaysOnTop();
    }

    /** Line from the crosshair towards a world position. */
    public static void tracer(Vec3 target, int argb, float width) {
        Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
        Vector3fc f = cam.forwardVector();
        Vec3 start = cam.position().add(f.x() * 0.5, f.y() * 0.5, f.z() * 0.5);
        Vec3 delta = target.subtract(start);
        double len = delta.length();
        Vec3 end = len > MAX_TRACER ? start.add(delta.scale(MAX_TRACER / len)) : target;
        Gizmos.line(start, end, argb, width).setAlwaysOnTop();
    }

    /** Camera-facing text. Scale grows with distance so far labels stay readable. */
    public static void text(String text, Vec3 pos, int argb) {
        double dist = pos.distanceTo(cameraPos());
        float scale = (float) Math.max(0.8, dist * 0.12);
        Gizmos.billboardText(text, pos, TextGizmo.Style.forColorAndCentered(argb).withScale(scale)).setAlwaysOnTop();
    }

    /** Replace the alpha channel of an ARGB color. */
    public static int alpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }
}
