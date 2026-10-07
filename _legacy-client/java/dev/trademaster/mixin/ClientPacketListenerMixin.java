package dev.trademaster.mixin;

import dev.trademaster.detection.DetectionHub;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Event detection channel, part 2: the merchant offers have been applied to
 * the open menu. Injection point deliberately differs from LPCTools'
 * WrapOperation on {@code setCanRestock} to avoid conflicts when both mods
 * are installed.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

	@Inject(method = "handleMerchantOffers", at = @At("TAIL"))
	private void tradeMaster$onMerchantOffersHandled(ClientboundMerchantOffersPacket packet, CallbackInfo ci) {
		var client = net.minecraft.client.Minecraft.getInstance();
		// vanilla discards packets whose containerId mismatches but still returns
		// normally, so the TAIL fires too: re-apply vanilla's own check, otherwise
		// a stale packet would deliver the trader's pre-reroll offers
		if (client.player != null && client.player.containerMenu instanceof MerchantMenu menu
				&& packet.getContainerId() == menu.containerId) {
			DetectionHub.onMerchantMenuOffersUpdated(menu);
		}
	}
}
