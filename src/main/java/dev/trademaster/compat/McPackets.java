package dev.trademaster.compat;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
//? if <26.3 {
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
//?}
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/** Version-gated raw packet sends. */
public final class McPackets {

	/** Sends a position-only move packet. 1.21.2 added the trailing
	 * {@code horizontalCollision} flag to {@code Pos}. */
	public static void sendMovePlayerPos(ClientPacketListener connection, double x, double y, double z,
			boolean onGround, boolean horizontalCollision) {
		//? if >=1.21.2 {
		connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, onGround, horizontalCollision));
		//?} else {
		/*connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, onGround));*/
		//?}
	}

	/** Swings visibly and notifies the server. 26.3's swing() takes the animation and sends
	 * the swing itself (ServerboundSwingPacket was removed); before that the packet is sent
	 * explicitly, as the mod has always done. */
	public static void swing(LocalPlayer player, InteractionHand hand) {
		//? if >=26.3 {
		/*player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);*/
		//?} else {
		player.swing(hand, true);
		player.connection.send(new ServerboundSwingPacket(hand));
		//?}
	}

	/** Swings visibly WITHOUT the explicit swing packet (the pre-26.3 code path that only
	 * called swing(hand, true)). */
	public static void swingOnly(LocalPlayer player, InteractionHand hand) {
		//? if >=26.3 {
		/*player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);*/
		//?} else {
		player.swing(hand, true);
		//?}
	}

	/** Sends only the swing packet (26.3 removed it: swing() carries the animation). */
	public static void sendSwingPacket(LocalPlayer player, InteractionHand hand) {
		//? if <26.3 {
		player.connection.send(new ServerboundSwingPacket(hand));
		//?}
	}

	private McPackets() {}
}
