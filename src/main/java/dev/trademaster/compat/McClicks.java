package dev.trademaster.compat;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.entity.player.Player;

/**
 * Version-gated container-click plumbing.
 *
 * <p>26.1 renamed the click mode ({@code ClickType} -&gt; {@code ContainerInput}) and the
 * "apply a click" method ({@code handleInventoryMouseClick} -&gt;
 * {@code handleContainerInput}); the same release replaced the raw {@code ItemStack}
 * carried/changed-slot data of {@code ServerboundContainerClickPacket} with
 * {@code HashedStack}, which also reordered the constructor arguments. Both are hidden
 * behind this helper so the shared code names neither.
 */
public final class McClicks {

	/** The container click modes the mod uses. */
	public enum Click { PICKUP, QUICK_MOVE, SWAP }

	/** Applies a container click locally and sends it (the game mode seals both). */
	public static void click(MultiPlayerGameMode gameMode, int containerId, int slot, int button,
			Click click, Player player) {
		//? if >=26.1 {
		/*gameMode.handleContainerInput(containerId, slot, button, input(click), player);*/
		//?} else {
		gameMode.handleInventoryMouseClick(containerId, slot, button, clickType(click), player);
		//?}
	}

	/** Sends a bare {@code ServerboundContainerClickPacket} with an unchanged-slot map, used
	 * to advance the menu state id for the restock path's follow-up clicks. */
	public static void sendContainerClick(LocalPlayer player, int containerId, int stateId, int slot,
			int button, Click click) {
		//? if >=26.1 {
		/*player.connection.send(new ServerboundContainerClickPacket(
				containerId, stateId, (short) slot, (byte) button, input(click),
				new Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));*/
		//?} elif >=1.21.5 {
		/*player.connection.send(new ServerboundContainerClickPacket(
				containerId, stateId, (short) slot, (byte) button, clickType(click),
				new Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY));*/
		//?} else {
		player.connection.send(new ServerboundContainerClickPacket(
				containerId, stateId, slot, button, clickType(click),
				net.minecraft.world.item.ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
		//?}
	}

	//? if >=26.1 {
	/*private static net.minecraft.world.inventory.ContainerInput input(Click click) {
		return switch (click) {
			case PICKUP -> net.minecraft.world.inventory.ContainerInput.PICKUP;
			case QUICK_MOVE -> net.minecraft.world.inventory.ContainerInput.QUICK_MOVE;
			case SWAP -> net.minecraft.world.inventory.ContainerInput.SWAP;
		};
	}*/
	//?} else {
	private static net.minecraft.world.inventory.ClickType clickType(Click click) {
		return switch (click) {
			case PICKUP -> net.minecraft.world.inventory.ClickType.PICKUP;
			case QUICK_MOVE -> net.minecraft.world.inventory.ClickType.QUICK_MOVE;
			case SWAP -> net.minecraft.world.inventory.ClickType.SWAP;
		};
	}
	//?}

	private McClicks() {}
}
