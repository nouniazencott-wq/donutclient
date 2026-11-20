package com.example.donutclient.modules;

import com.example.donutclient.module.Category;
import com.example.donutclient.module.Module;
import com.example.donutclient.setting.BoolSetting;
import com.example.donutclient.util.Chat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tells you in chat when another player enters or leaves your render range. */
public class PlayerAlertModule extends Module {
    private final BoolSetting sound = add(new BoolSetting("Sound", true));
    private final BoolSetting leaves = add(new BoolSetting("Show Leaves", true));

    private final Map<UUID, String> seen = new HashMap<>();
    private ClientLevel lastLevel;

    public PlayerAlertModule() {
        super("Player Alert", "Chat alert when a player comes into range.", Category.WORLD, -1);
    }

    @Override
    protected void onEnable() {
        seen.clear();
    }

    @Override
    public void onTick() {
        if (mc.level == null || mc.player == null) return;
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            seen.clear();
        }

        Map<UUID, String> now = new HashMap<>();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == mc.player) continue;
            String name = p.getName().getString();
            now.put(p.getUUID(), name);
            if (!seen.containsKey(p.getUUID())) {
                BlockPos pos = p.blockPosition();
                Chat.info(name + " in range at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + " (" + (int) p.distanceTo(mc.player) + "m)");
                if (sound.get()) Chat.ping();
            }
        }
        if (leaves.get()) {
            for (Map.Entry<UUID, String> e : seen.entrySet()) {
                if (!now.containsKey(e.getKey())) Chat.info(e.getValue() + " left range");
            }
        }
        seen.clear();
        seen.putAll(now);
    }
}
