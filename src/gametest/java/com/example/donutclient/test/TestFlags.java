package com.example.donutclient.test;

public final class TestFlags {
    private TestFlags() {}

    /** False between screenshots: the selected parts of the frame are skipped. */
    public static volatile boolean renderWorld = true;
    /** Game speed multiplier (the vanilla client never ticks faster than 20/s on its own). */
    public static volatile float speed = 1f;
    /** Rendering is only skipped in normal play; loading and menu screens need real frames to progress. */
    public static boolean skipping() {
        return !renderWorld && net.minecraft.client.Minecraft.getInstance().gui.screen() == null;
    }

    public static volatile boolean skipLevel = true, skipRender, skipExtract, skipPresent;
}
