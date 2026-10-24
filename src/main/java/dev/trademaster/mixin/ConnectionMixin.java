package dev.trademaster.mixin;

import dev.trademaster.detection.DetectionHub;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet detection channel (LibrarianTradeFinder style): feeds the offers
 * packet into the {@link DetectionHub} and suppresses the merchant screen
 * while the PACKET channel is authoritative.
 */
@Mixin(Connection.class)
public class ConnectionMixin {

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void tradeMaster$onChannelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ClientboundOpenScreenPacket openScreen) {
			if (openScreen.getType() == MenuType.MERCHANT) {
				// ONE read of the armed state: the hub to bind and the suppress
				// decision must describe the same moment, or a re-arm in between
				// would bind a stale screen to the next check's hub
				DetectionHub.ScreenInterception interception = DetectionHub.screenInterception();
				if (interception.suppress()) {
					DetectionHub hub = interception.hub();
					Minecraft.getInstance().execute(() -> {
						DetectionHub.onMerchantScreenSuppressed(hub, openScreen.getContainerId());
						ClientPacketListener connection = Minecraft.getInstance().getConnection();
						if (connection != null) {
							connection.send(new ServerboundContainerClosePacket(openScreen.getContainerId()));
						}
					});
					ci.cancel();
				}
			}
		} else if (packet instanceof ClientboundMerchantOffersPacket offersPacket) {
			DetectionHub.onOffersPacket(offersPacket.getOffers(), offersPacket.getContainerId());
		}
	}
}
