package com.example.donutclient.bot;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Replacement for the player's keyboard input while the bot is in control.
 * Tasks set the boolean "keys" each tick; {@link #tick()} turns them into the
 * key-press record and movement vector vanilla reads from a {@link ClientInput}.
 */
public final class BotInput extends ClientInput {
    public boolean forward;
    public boolean back;
    public boolean left;
    public boolean right;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    /** Releases every key. */
    public void clear() {
        forward = back = left = right = jump = sneak = sprint = false;
    }

    @Override
    public void tick() {
        keyPresses = new Input(forward, back, left, right, jump, sneak, sprint);
        // Opposite keys cancel out, exactly like the vanilla keyboard input.
        float forwardImpulse = forward == back ? 0.0f : (forward ? 1.0f : -1.0f);
        float strafeImpulse = left == right ? 0.0f : (left ? 1.0f : -1.0f);
        moveVector = new Vec2(strafeImpulse, forwardImpulse).normalized();
    }
}
