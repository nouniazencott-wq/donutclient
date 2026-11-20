package com.example.donutclient.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Client-only chat output (never sent to the server). */
public final class Chat {
    private Chat() {}

    public static void info(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        Component text = Component.literal("[Donut] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
        mc.gui.hud.getChat().addClientSystemMessage(text);
    }

    public static void ping() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
    }
}
