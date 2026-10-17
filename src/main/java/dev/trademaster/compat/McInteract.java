package dev.trademaster.compat;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;

/** Version-gated interaction helpers. {@code MultiPlayerGameMode.useItemOn} took the
 * ClientLevel explicitly before 1.19. */
public final class McInteract {

	public static void useItemOn(MultiPlayerGameMode gameMode, LocalPlayer player,
			InteractionHand hand, BlockHitResult hit) {
		//? if >=1.19 {
		gameMode.useItemOn(player, hand, hit);
		//?} else {
		/*gameMode.useItemOn(player, (net.minecraft.client.multiplayer.ClientLevel) player.level, hand, hit);*/
		//?}
	}

	private McInteract() {}
}
