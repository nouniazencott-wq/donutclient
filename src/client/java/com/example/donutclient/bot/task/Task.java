package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;

/**
 * Base class of everything the bot does. A task is ticked once per client tick until it reports that it is finished.
 * A task may delegate to a single child task ("sub") via {@link #run(Task)} and {@link #busy()}.
 */
public abstract class Task {
	protected final Bot bot;
	/** The child task currently running on behalf of this task, or null. */
	private Task sub;

	protected Task(Bot bot) {
		this.bot = bot;
	}

	/** Whether a more urgent task may take over right now. By default that is the case unless a child objects. */
	public boolean interruptible() {
		return sub == null || sub.interruptible();
	}

	/**
	 * Advances the task by one tick.
	 *
	 * @return true once the task is finished (successfully or not)
	 */
	public abstract boolean tick();

	/** Called when the task is taken off the bot before it finished; drops the child task. */
	public void onInterrupted() {
		sub = null;
	}

	/**
	 * Ticks the child task, if any.
	 *
	 * @return true while the child is still running (the caller should then return false from its own tick)
	 */
	protected boolean busy() {
		if (sub == null) {
			return false;
		}
		if (sub.tick()) {
			sub = null;
			return false;
		}
		return true;
	}

	/** Starts a child task; it is advanced by {@link #busy()}. */
	protected void run(Task child) {
		sub = child;
	}

	protected boolean hasSub() {
		return sub != null;
	}
}
