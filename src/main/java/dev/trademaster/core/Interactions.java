package dev.trademaster.core;

import dev.trademaster.compat.McPlayer;
import dev.trademaster.compat.McPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;

/** Small packet-level player actions shared between core and exec. */
public final class Interactions {

	private Interactions() {}

	/** Interacts with an entity, swinging visibly and notifying the server. */
	public static InteractionResult interactEntity(Minecraft mc, LocalPlayer player, Entity entity) {
		//? if >=26.1 {
		/*InteractionResult result = mc.gameMode.interact(player, entity,
				new EntityHitResult(entity), InteractionHand.MAIN_HAND);*/
		//?} else {
		InteractionResult result = mc.gameMode.interact(player, entity, InteractionHand.MAIN_HAND);
		//?}
		McPackets.swing(player, InteractionHand.MAIN_HAND);
		return result;
	}

	/** Selects a hotbar slot (0-8) client-side and tells the server. */
	public static void selectHotbarSlot(LocalPlayer player, int slot) {
		McPlayer.setSelectedSlot(player, slot);
		player.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}
}
