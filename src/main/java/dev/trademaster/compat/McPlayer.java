package dev.trademaster.compat;

import dev.trademaster.compat.McPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Version-gated player/inventory accessors.
 *
 * <p>26.1 renamed the hotbar selection API on {@code Inventory} ({@code selected} /
 * {@code getSelected()} / {@code setSelectedHotbarSlot} became {@code getSelectedSlot()} /
 * {@code getSelectedItem()} / {@code setSelectedSlot()}), renamed the chat line
 * ({@code displayClientMessage} -&gt; {@code sendSystemMessage}) and added
 * {@code isWithinBlockInteractionRange}. All are hidden here.
 */
public final class McPlayer {

	/** The selected hotbar slot (0-8). */
	public static int selectedSlot(Player player) {
		//? if >=1.21.5 {
		/*return McPlayer.selectedSlot(player);*/
		//?} else {
		return player.getInventory().selected;
		//?}
	}

	/** Selects a hotbar slot (0-8). */
	public static void setSelectedSlot(Player player, int slot) {
		//? if >=1.21.5 {
		/*McPlayer.setSelectedSlot(player, slot);*/
		//?} else {
		player.getInventory().selected = slot;
		//?}
	}

	/** The stack in the selected hotbar slot. */
	public static ItemStack selectedItem(Player player) {
		//? if >=1.21.5 {
		/*return McPlayer.selectedItem(player);*/
		//?} else {
		return player.getInventory().getSelected();
		//?}
	}

	/** Sends a chat/action-bar line to the player (client-side). */
	public static void sendSystemMessage(Player player, Component message) {
		//? if >=26.1 {
		/*player.sendSystemMessage(message);*/
		//?} else {
		player.displayClientMessage(message, false);
		//?}
	}

	/** Whether {@code pos} is within {@code range} of the player (the pre-26.1 fallback
	 * is the centre-distance test {@code isWithinBlockInteractionRange} performs). */
	public static boolean withinBlockInteractionRange(Player player, BlockPos pos, double range) {
		//? if >=26.1 {
		/*return player.isWithinBlockInteractionRange(pos, range);*/
		//?} else {
		return player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= range * range;
		//?}
	}

	/** Whether the player has infinite building materials (creative). 1.20.5 renamed
	 * {@code isCreative} to {@code hasInfiniteMaterials}. */
	public static boolean hasInfiniteMaterials(Player player) {
		//? if >=1.20.5 {
		return player.hasInfiniteMaterials();
		//?} else {
		/*return player.isCreative();*/
		//?}
	}

	private McPlayer() {}
}
