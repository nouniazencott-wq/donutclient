package com.example.donutclient.bot;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Movement input driven by the bot instead of the keyboard. */
public final class BotInput extends ClientInput {
    public boolean forward, back, left, right, jump, sneak, sprint;

    public void clear() {
        forward = back = left = right = jump = sneak = sprint = false;
    }

    @Override
    public void tick() {
        this.keyPresses = new Input(forward, back, left, right, jump, sneak, sprint);
        float f = forward == back ? 0f : (forward ? 1f : -1f);
        float s = left == right ? 0f : (left ? 1f : -1f);
        this.moveVector = new Vec2(s, f).normalized();
    }
}
