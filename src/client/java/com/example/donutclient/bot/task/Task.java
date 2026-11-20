package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;

/** A unit of behaviour. tick() is called every client tick until it returns true. */
public abstract class Task {
    protected final Bot bot;
    private Task sub;

    protected Task(Bot bot) { this.bot = bot; }

    /** Whether the planner may abandon this task mid-way for a more urgent one. */
    public boolean interruptible() { return sub == null || sub.interruptible(); }

    /** @return true when the task is complete */
    public abstract boolean tick();

    /** Called when the bot died or an error interrupted the flow; drop transient state. */
    public void onInterrupted() {
        sub = null;
    }

    /** Runs a child task to completion. Returns true while the child is still busy. */
    protected boolean busy() {
        if (sub == null) return false;
        if (sub.tick()) { sub = null; return false; }
        return true;
    }

    protected void run(Task child) { this.sub = child; }

    protected boolean hasSub() { return sub != null; }
}
