package com.example.donutclient.bot;

import com.example.donutclient.DonutClient;
import com.example.donutclient.bot.path.Nav;
import com.example.donutclient.bot.task.BeatGameTask;
import com.example.donutclient.bot.task.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * The autonomous player. Singleplayer only: it refuses to run unless the
 * world is hosted by this client's own integrated server.
 */
public final class Bot {
    public static final Bot I = new Bot();

    public final Minecraft mc = Minecraft.getInstance();
    public final BotInput input = new BotInput();
    public final Inv inv = new Inv();
    public final Act act = new Act(this);
    public final Nav nav = new Nav(this);
    public final Scan scan = new Scan(this);
    public final Reflex reflex = new Reflex(this);
    public final Memory memory = new Memory();
    public final Gear gear = new Gear(this);
    public final Aim aim = new Aim(this);

    public long tick;
    /** Tasks that are legitimately waiting (smelting, sheltering) bump this so the stall detector stays quiet. */
    public long heartbeat;
    private boolean active;
    private Task root;
    private String status = "idle";
    private String lastLogged = "";
    private int respawnCooldown;
    private int graceTicks;
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastDim;
    private boolean savedPauseOnLostFocus = true;
    public int deaths;

    private Bot() {}

    public boolean isActive() { return active; }

    public String status() { return status; }

    public LocalPlayer player() { return mc.player; }

    public ClientLevel level() { return mc.level; }

    /** The integrated server's copy of the dimension the player is in (read-only use). */
    public ServerLevel serverLevel() {
        var server = mc.getSingleplayerServer();
        return server == null || mc.level == null ? null : server.getLevel(mc.level.dimension());
    }

    public BlockPos feet() {
        LocalPlayer p = mc.player;
        return BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
    }

    public boolean canRun() {
        return mc.player != null && mc.level != null && mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null;
    }

    /** Test hook: just walk to a goal instead of playing the game. */
    public String startDebugNav(com.example.donutclient.bot.path.Goal goal) {
        String err = start();
        if (err != null) return err;
        root = new Task(this) {
            @Override
            public boolean tick() {
                setStatus("debug nav to " + goal);
                var st = nav.goTo(goal);
                if (st != Nav.Status.RUNNING) dbg("debug nav finished: " + st);
                return st != Nav.Status.RUNNING;
            }
        };
        return null;
    }

    public String start() {
        if (!canRun()) return "The bot only works in a singleplayer world.";
        if (active) return "Bot is already running.";
        active = true;
        savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        root = new BeatGameTask(this);
        nav.reset();
        say("Bot started. I'll try to beat the game. Use /bot stop to take back control.");
        return null;
    }

    public void stop() {
        if (!active) return;
        active = false;
        mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
        root = null;
        nav.reset();
        act.stopBreaking();
        reflex.release();
        input.clear();
        if (mc.player != null && mc.player.input == input) mc.player.input = new KeyboardInput(mc.options);
        status = "idle";
        if (mc.player != null) say("Bot stopped.");
    }

    public void setStatus(String s) {
        status = s;
        if (!s.equals(lastLogged)) {
            lastLogged = s;
            DonutClient.LOGGER.info("[bot] {}", s);
        }
    }

    /** Diagnostic detail for the log file only. */
    public void dbg(String msg) {
        DonutClient.LOGGER.info("[bot:dbg] {}", msg);
    }

    public void say(String msg) {
        DonutClient.LOGGER.info("[bot] {}", msg);
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Bot] " + msg));
    }

    /** Called at the start of every client tick. */
    public void onTick() {
        if (!active) return;
        if (!canRun()) { active = false; root = null; status = "idle"; return; }
        if (mc.isPaused()) return;
        tick++;
        LocalPlayer p = mc.player;

        if (p.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen) {
            if (respawnCooldown-- <= 0) {
                respawnCooldown = 30;
                deaths++;
                int near = 0;
                for (BlockPos d : memory.dangerZones) if (d.distSqr(p.blockPosition()) < 20 * 20) near++;
                memory.dangerZones.add(p.blockPosition());
                memory.deathPos = p.blockPosition();
                memory.deathDim = mc.level.dimension();
                memory.deathTick = tick;
                memory.deathsNearby = near;
                memory.diedRecovering = memory.recovering;
                memory.recovering = false;
                say("I died (" + deaths + "). Respawning and carrying on.");
                p.respawn();
                mc.gui.setScreen(null);
                nav.reset();
                reflex.release();
                if (root != null) root.onInterrupted();
                graceTicks = 60; // let the world load around the respawn point
            }
            return;
        }
        respawnCooldown = 0;
        if (!p.connection.hasClientLoaded()) return; // still on the loading screen
        if (graceTicks > 0) { graceTicks--; input.clear(); if (p.input != input) p.input = input; return; }

        if (lastDim != mc.level.dimension()) {
            // Changed dimension: give the chunks a moment to arrive.
            lastDim = mc.level.dimension();
            graceTicks = 40;
            nav.reset();
            return;
        }
        if (memory.netherPortal == null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
            memory.netherPortal = feet();
        }
        ClientInput cur = p.input;
        if (cur != input) p.input = input;
        input.clear();
        act.breakingRequested = false;

        if (tick % 20 == 0) inv.dropJunk();
        try {
            if (!reflex.tick() && root != null) {
                if (root.tick()) {
                    say("Done: the game is beaten!");
                    stop();
                    return;
                }
            }
        } catch (RuntimeException e) {
            DonutClient.LOGGER.error("[bot] task error", e);
            nav.reset();
            if (root != null) root.onInterrupted();
        }
        if (!act.breakingRequested) act.stopBreaking();
    }
}
