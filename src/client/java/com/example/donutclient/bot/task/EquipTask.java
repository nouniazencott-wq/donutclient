package com.example.donutclient.bot.task;

import com.example.donutclient.bot.Bot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Puts on any iron armor lying in the inventory and moves a shield into the off hand. */
public final class EquipTask extends Task {
	/** Pairs of {armor slot, item that belongs there}. */
	private static final Object[][] ARMOR = {
			{EquipmentSlot.HEAD, Items.IRON_HELMET},
			{EquipmentSlot.CHEST, Items.IRON_CHESTPLATE},
			{EquipmentSlot.LEGS, Items.IRON_LEGGINGS},
			{EquipmentSlot.FEET, Items.IRON_BOOTS},
	};

	/** Ticks to wait between two inventory clicks. */
	private int cooldown;
	private int ticks;

	public EquipTask(Bot bot) {
		super(bot);
	}

	/** True if an armor slot is empty while the matching piece is carried, or a shield is carried but not held. */
	public static boolean needed(Bot bot) {
		LocalPlayer p = bot.player();
		for (Object[] a : ARMOR) {
			if (p.getItemBySlot((EquipmentSlot) a[0]).isEmpty() && bot.inv.count((Item) a[1]) > 0) {
				return true;
			}
		}
		return !p.getOffhandItem().is(Items.SHIELD) && bot.inv.find(s -> s.is(Items.SHIELD)) >= 0;
	}

	/** Converts a player-inventory index (0-8 hotbar, 9-35 main) to a slot index of the inventory menu. */
	private static int menuSlot(int invSlot) {
		return invSlot < 9 ? 36 + invSlot : invSlot;
	}

	@Override
	public boolean interruptible() {
		return false;
	}

	@Override
	public boolean tick() {
		LocalPlayer p = bot.player();
		// give up after 10 seconds
		if (++ticks > 200 || !needed(bot)) {
			return true;
		}
		if (!bot.inv.menuIsInventory()) {
			p.closeContainer();
			return false;
		}
		if (cooldown-- > 0) {
			return false;
		}
		cooldown = 4;
		int id = p.inventoryMenu.containerId;
		for (Object[] a : ARMOR) {
			if (!p.getItemBySlot((EquipmentSlot) a[0]).isEmpty()) {
				continue;
			}
			int slot = bot.inv.find(s -> s.is((Item) a[1]));
			if (slot >= 0) {
				// shift-click moves the armor piece straight into its slot
				bot.mc.gameMode.handleContainerInput(id, menuSlot(slot), 0, ContainerInput.QUICK_MOVE, p);
				return false;
			}
		}
		if (!p.getOffhandItem().is(Items.SHIELD)) {
			int slot = bot.inv.find(s -> s.is(Items.SHIELD));
			if (slot >= 0) {
				// swap with "hotbar button" 40, which is the off hand
				bot.mc.gameMode.handleContainerInput(id, menuSlot(slot), 40, ContainerInput.SWAP, p);
			}
		}
		return false;
	}
}
