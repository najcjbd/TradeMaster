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
		/*return player.getInventory().getSelectedSlot();*/
		//?} else {
		return player.getInventory().selected;
		//?}
	}

	/** Selects a hotbar slot (0-8). */
	public static void setSelectedSlot(Player player, int slot) {
		//? if >=1.21.5 {
		/*player.getInventory().setSelectedSlot(slot);*/
		//?} else {
		player.getInventory().selected = slot;
		//?}
	}

	/** The stack in the selected hotbar slot. */
	public static ItemStack selectedItem(Player player) {
		//? if >=1.21.5 {
		/*return player.getInventory().getSelectedItem();*/
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

	/** Whether {@code pos} is within the player's block reach plus {@code range}. 26.1
	 * renamed {@code canInteractWithBlock}; the same eye-to-block-box test exists from
	 * 1.21. Before that the 4.5-block vanilla reach is reproduced explicitly rather than
	 * using a centre-distance test, which was far stricter than the game itself. */
	public static boolean withinBlockInteractionRange(Player player, BlockPos pos, double range) {
		//? if >=26.1 {
		/*return player.isWithinBlockInteractionRange(pos, range);*/
		//?} elif >=1.21 {
		return player.canInteractWithBlock(pos, range);
		//?} else {
		/*double reach = 4.5 + range;
		net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
		double dx = Math.max(0.0, Math.max(pos.getX() - eye.x, eye.x - (pos.getX() + 1)));
		double dy = Math.max(0.0, Math.max(pos.getY() - eye.y, eye.y - (pos.getY() + 1)));
		double dz = Math.max(0.0, Math.max(pos.getZ() - eye.z, eye.z - (pos.getZ() + 1)));
		return dx * dx + dy * dy + dz * dz < reach * reach;*/
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
