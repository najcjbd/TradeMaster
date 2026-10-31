package dev.trademaster.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

/** Small packet-level player actions shared between core and exec. */
public final class Interactions {

	private Interactions() {}

	/** Interacts with an entity, swinging visibly and notifying the server. */
	public static InteractionResult interactEntity(Minecraft mc, LocalPlayer player, Entity entity) {
		InteractionResult result = mc.gameMode.interact(player, entity,
				new EntityHitResult(entity), InteractionHand.MAIN_HAND);
		player.swing(InteractionHand.MAIN_HAND, true);
		player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
		return result;
	}

	/** Selects a hotbar slot (0-8) client-side and tells the server. */
	public static void selectHotbarSlot(LocalPlayer player, int slot) {
		player.getInventory().setSelectedSlot(slot);
		player.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}

	/** Whether a stack on the container cursor can be returned to the 36-slot
	 * inventory (mirrors Inventory.add: same-item partial stacks first, then empty
	 * slots). The server auto-closes the previous menu when a new one opens and
	 * DROPS a carried stack that does not fit. */
	public static boolean hasRoomForCarried(LocalPlayer player) {
		ItemStack carried = player.containerMenu.getCarried();
		if (carried.isEmpty()) return true;
		int remaining = carried.getCount();
		var inventory = player.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack slot = inventory.getItem(i);
			if (slot.isEmpty()) return true;
			if (ItemStack.isSameItemSameComponents(slot, carried)) {
				int room = slot.getMaxStackSize() - slot.getCount();
				if (room > 0) {
					remaining -= room;
					if (remaining <= 0) return true;
				}
			}
		}
		// the server can also top up a partial stack in the OFFHAND slot
		ItemStack offhand = inventory.getItem(40);
		if (!offhand.isEmpty() && ItemStack.isSameItemSameComponents(offhand, carried)) {
			remaining -= offhand.getMaxStackSize() - offhand.getCount();
		}
		return remaining <= 0;
	}

	/** Closes the open container only when its carried stack can be returned; with a
	 * full inventory, closing (or letting the server auto-close it) would drop it.
	 * @return true when no menu is open or it was closed safely */
	public static boolean closeMenuSafely(LocalPlayer player) {
		if (player.containerMenu == player.inventoryMenu) return true;
		if (!hasRoomForCarried(player)) return false;
		player.closeContainer();
		return true;
	}

	/** Resolves a dirty cursor before opening another menu: closes the current menu
	 * only when the server can return the stack; false means "do not open a menu"
	 * (the server would auto-close this one and drop the stack). */
	public static boolean resolveDirtyCursorBeforeMenuOpen(LocalPlayer player) {
		if (player.containerMenu == player.inventoryMenu) return true;
		if (player.containerMenu.getCarried().isEmpty()) return true;
		return closeMenuSafely(player);
	}
}
