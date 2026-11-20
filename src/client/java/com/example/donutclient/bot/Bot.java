package com.example.donutclient.bot;

import com.example.donutclient.DonutClient;
import com.example.donutclient.bot.path.Goal;
import com.example.donutclient.bot.path.Nav;
import com.example.donutclient.bot.task.BeatGameTask;
import com.example.donutclient.bot.task.Task;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The bot itself: a singleton that, while active, takes over the local
 * player's input every client tick and runs a tree of tasks until the Ender
 * Dragon is dead. It only works in singleplayer (integrated server).
 *
 * This class owns the shared helpers the tasks use (inventory, actions,
 * navigation, block scanning, reflexes, memory, ...) and the per-tick main
 * loop including death/respawn and dimension-change handling.
 */
public final class Bot {
    /** The one bot instance. */
    public static final Bot I = new Bot();

    public final Minecraft mc = Minecraft.getInstance();
    /** Fake keyboard: installed as the player's input while the bot runs. */
    public final BotInput input = new BotInput();
    public final Inv inv = new Inv();
    public final Act act = new Act(this);
    public final Nav nav = new Nav(this);
    public final Scan scan = new Scan(this);
    public final Reflex reflex = new Reflex(this);
    public final Memory memory = new Memory();
    public final Gear gear = new Gear(this);
    public final Aim aim = new Aim(this);

    /** Bot ticks since the game started (only counts unpaused ticks while active). */
    public long tick;
    public long heartbeat;

    private boolean active;
    /** Top of the task tree; null while idle. */
    private Task root;
    private String status = "idle";
    private String lastLogged = "";
    /** Ticks until the next respawn attempt while dead. */
    private int respawnCooldown;
    /** Ticks to stand still after a respawn or a dimension change. */
    private int graceTicks;
    private ResourceKey<Level> lastDim;
    /** The user's "pause on lost focus" setting, restored when the bot stops. */
    private boolean savedPauseOnLostFocus = true;
    public int deaths;

    private Bot() {}

    public boolean isActive() {
        return active;
    }

    /** One-line description of what the bot is doing right now (shown on the HUD). */
    public String status() {
        return status;
    }

    public LocalPlayer player() {
        return mc.player;
    }

    public ClientLevel level() {
        return mc.level;
    }

    /** The integrated server's copy of the dimension the player is in, or null. */
    public ServerLevel serverLevel() {
        IntegratedServer server = mc.getSingleplayerServer();
        return server == null || mc.level == null ? null : server.getLevel(mc.level.dimension());
    }

    /**
     * Block position of the player's feet. The small upward nudge makes
     * standing on slightly lower blocks (soul sand, farmland, paths) count as
     * the block above the floor.
     */
    public BlockPos feet() {
        LocalPlayer p = mc.player;
        return BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
    }

    /** True while there is a player in a world hosted by the integrated server. */
    public boolean canRun() {
        return mc.player != null && mc.level != null && mc.hasSingleplayerServer()
                && mc.getSingleplayerServer() != null;
    }

    /**
     * Debug entry point: starts the bot but replaces the normal task tree with
     * a single task that just walks to {@code goal}.
     *
     * @return an error message, or null on success
     */
    public String startDebugNav(Goal goal) {
        String err = start();
        if (err != null) {
            return err;
        }
        root = new Task(this) {
            @Override
            public boolean tick() {
                Bot.this.setStatus("debug nav to " + goal);
                Nav.Status st = Bot.this.nav.goTo(goal);
                if (st != Nav.Status.RUNNING) {
                    Bot.this.dbg("debug nav finished: " + st);
                }
                return st != Nav.Status.RUNNING;
            }
        };
        return null;
    }

    /**
     * Starts playing.
     *
     * @return an error message if the bot cannot start, or null on success
     */
    public String start() {
        if (!canRun()) {
            return "The bot only works in a singleplayer world.";
        }
        if (active) {
            return "Bot is already running.";
        }
        active = true;
        // Keep the game running when the window loses focus, so the bot can play in the background.
        savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        root = new BeatGameTask(this);
        nav.reset();
        say("Bot started. I'll try to beat the game. Use /bot stop to take back control.");
        return null;
    }

    /** Stops playing and hands the controls back to the keyboard. */
    public void stop() {
        if (!active) {
            return;
        }
        active = false;
        mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
        root = null;
        nav.reset();
        act.stopBreaking();
        reflex.release();
        input.clear();
        if (mc.player != null && mc.player.input == input) {
            mc.player.input = new KeyboardInput(mc.options);
        }
        status = "idle";
        if (mc.player != null) {
            say("Bot stopped.");
        }
    }

    /** Sets the HUD status line; each new status is also written to the log once. */
    public void setStatus(String s) {
        status = s;
        if (!s.equals(lastLogged)) {
            lastLogged = s;
            DonutClient.LOGGER.info("[bot] {}", s);
        }
    }

    /** Log-only debug message. */
    public void dbg(String msg) {
        DonutClient.LOGGER.info("[bot:dbg] {}", msg);
    }

    /** Message to the log and to the player's chat. */
    public void say(String msg) {
        DonutClient.LOGGER.info("[bot] {}", msg);
        if (mc.player != null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[Bot] " + msg));
        }
    }

    /** Main loop, called at the start of every client tick. */
    public void onTick() {
        if (!active) return;
        if (!canRun()) { active = false; root = null; status = "idle"; return; }
        if (mc.isPaused()) return;
        tick++;
        LocalPlayer p = mc.player;

        // Dead: remember where, respawn (retrying every 30 ticks) and restart the current task.
        if (p.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen) {
            if (respawnCooldown-- <= 0) {
                respawnCooldown = 30;
                deaths++;
                int near = 0;
                for (BlockPos d : memory.dangerZones) if (d.distSqr(p.blockPosition()) < 400.0) near++;
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
                graceTicks = 60;
            }
            return;
        }
        respawnCooldown = 0;
        // World still loading (after login, respawn or a portal).
        if (!p.connection.hasClientLoaded()) return;
        if (graceTicks > 0) { graceTicks--; input.clear(); if (p.input != input) p.input = input; return; }

        if (lastDim != mc.level.dimension()) {
            // Changed dimension: stand still for two seconds and plan afresh.
            lastDim = mc.level.dimension();
            graceTicks = 40;
            nav.reset();
            return;
        }
        // First tick in the Nether: this is where our portal is.
        if (memory.netherPortal == null && mc.level.dimension() == Level.NETHER) {
            memory.netherPortal = feet();
        }

        ClientInput cur = p.input;
        if (cur != input) p.input = input;
        input.clear();
        act.breakingRequested = false;

        if (tick % 20 == 0) inv.dropJunk();

        try {
            // Reflexes (survival reactions) pre-empt the task tree for this tick.
            if (!reflex.tick() && root != null && root.tick()) {
                say("Done: the game is beaten!");
                stop();
                return;
            }
        } catch (RuntimeException e) {
            // A bug in one task must not crash the game: log it and let the task tree start over.
            DonutClient.LOGGER.error("[bot] task error", e);
            nav.reset();
            if (root != null) root.onInterrupted();
        }
        // Nobody asked to keep mining this tick: let go of the block.
        if (!act.breakingRequested) act.stopBreaking();
    }
}
