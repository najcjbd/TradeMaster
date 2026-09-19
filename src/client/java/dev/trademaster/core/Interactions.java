package dev.trademaster.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
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
}
