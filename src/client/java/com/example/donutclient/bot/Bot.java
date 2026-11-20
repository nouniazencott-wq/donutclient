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

    /** True while the user has taken the controls back without ending the run (/bot pause). */
    private boolean paused;
    /** Set once the current death has been counted, so a slow respawn is not counted twice. */
    private boolean deathHandled;
    /** Task errors in a row (within a minute of each other); too many stop the bot. */
    private int errorStreak;
    private long lastErrorTick;
    /** Value of {@link #tick} when the current run started. */
    private long startTick;

    /** Name of the plan stage being worked on, for the HUD. Empty while there is none. */
    public String stageName = "";
    /** 1-based position of that stage in the plan, and the plan's length (0 while unknown). */
    public int stageIndex;
    public int stageCount;

    private Bot() {}

    public boolean isPaused() {
        return paused;
    }

    /**
     * Pauses or resumes a running bot. While paused the keyboard is back in control and the plan is kept; on resume
     * the bot re-plans from wherever the player now stands.
     */
    public void setPaused(boolean value) {
        if (!active || paused == value) {
            return;
        }
        paused = value;
        nav.reset();
        act.stopBreaking();
        reflex.release();
        input.clear();
        if (root != null) {
            root.onInterrupted();
        }
        if (paused) {
            if (mc.player != null && mc.player.input == input) {
                mc.player.input = new KeyboardInput(mc.options);
            }
            status = "paused";
            say("Paused. You have the controls. Use /bot resume to let me continue.");
        } else {
            say("Resuming.");
        }
    }

    /** Bot ticks played in the current run (20 per second, not counting pauses). */
    public long elapsedTicks() {
        return tick - startTick;
    }

    /** The current run's play time as m:ss or h:mm:ss. */
    public String elapsedText() {
        long seconds = elapsedTicks() / 20;
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** Called when the player leaves the world: stop, and forget everything learned about that world. */
    public void onWorldLeft() {
        stop();
        memory.reset();
        deaths = 0;
        lastDim = null;
    }

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
        stageName = "walking to " + goal;
        root = new Task(this) {
            @Override
            public boolean tick() {
                Bot.this.setStatus("walking to " + goal);
                Nav.Status st = Bot.this.nav.goTo(goal);
                if (st != Nav.Status.RUNNING) {
                    Bot.this.dbg("debug nav finished: " + st);
                    Bot.this.say(st == Nav.Status.DONE ? "Arrived." : "I couldn't find a way there.");
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
        paused = false;
        deathHandled = false;
        respawnCooldown = 0;
        graceTicks = 0;
        errorStreak = 0;
        startTick = tick;
        stageName = "";
        stageIndex = 0;
        stageCount = 0;
        lastDim = mc.level.dimension();
        memory.currentDim = lastDim;
        root = new BeatGameTask(this);
        nav.reset();
        say("Bot started. /bot pause to take over for a while, /bot stop to end.");
        return null;
    }

    /** Stops playing and hands the controls back to the keyboard. */
    public void stop() {
        if (!active) {
            return;
        }
        active = false;
        paused = false;
        stageName = "";
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
        if (paused) {
            // The user is playing: make sure the keyboard really is in control and do nothing else.
            if (mc.player.input == input) mc.player.input = new KeyboardInput(mc.options);
            return;
        }
        tick++;
        LocalPlayer p = mc.player;
        memory.currentDim = mc.level.dimension();

        // Dead: remember where (once per death), respawn (retrying every 30 ticks) and restart the current task.
        if (p.isDeadOrDying() || mc.gui.screen() instanceof DeathScreen) {
            if (!deathHandled) {
                deathHandled = true;
                respawnCooldown = 0;
                deaths++;
                BlockPos where = p.blockPosition();
                int near = memory.deathsNear(where, mc.level.dimension(), 20.0);
                memory.addDanger(where, mc.level.dimension());
                memory.deathPos = where;
                memory.deathDim = mc.level.dimension();
                memory.deathTick = tick;
                memory.deathsNearby = near;
                memory.diedRecovering = memory.recovering;
                memory.recovering = false;
                say("I died (" + deaths + "). Respawning and carrying on.");
                nav.reset();
                reflex.release();
                if (root != null) root.onInterrupted();
            }
            if (respawnCooldown-- <= 0) {
                respawnCooldown = 30;
                p.respawn();
                mc.gui.setScreen(null);
                graceTicks = 60;
            }
            return;
        }
        deathHandled = false;
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

        // Make room once a second, but never while a crafting table / furnace screen is open.
        if (tick % 20 == 0 && inv.menuIsInventory()) inv.dropJunk();

        try {
            // Reflexes (survival reactions) pre-empt the task tree for this tick.
            if (!reflex.tick() && root != null && root.tick()) {
                if (root instanceof BeatGameTask) {
                    say("Done: the game is beaten! Time " + elapsedText() + ", deaths " + deaths + ".");
                }
                stop();
                return;
            }
        } catch (RuntimeException e) {
            // A bug in one task must not crash the game: log it and let the task tree start over.
            DonutClient.LOGGER.error("[bot] task error", e);
            if (tick - lastErrorTick > 1200) errorStreak = 0;
            lastErrorTick = tick;
            errorStreak++;
            if (errorStreak >= 8) {
                // The same thing keeps failing: stopping beats spamming the log forever.
                say("I keep running into an internal error (" + e + "), so I'm stopping. The log has the details.");
                stop();
                return;
            }
            try {
                nav.reset();
                reflex.release();
                if (root != null) root.onInterrupted();
            } catch (RuntimeException e2) {
                DonutClient.LOGGER.error("[bot] error while resetting after a task error", e2);
            }
            // Back off a little longer after each repeat before trying again.
            graceTicks = Math.min(20 * errorStreak, 200);
        }
        // Nobody asked to keep mining this tick: let go of the block.
        if (!act.breakingRequested) act.stopBreaking();
    }
}
