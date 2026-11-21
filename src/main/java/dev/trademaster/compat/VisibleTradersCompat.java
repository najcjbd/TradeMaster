package dev.trademaster.compat;

import dev.trademaster.TradeMasterClient;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.mixin.MerchantMenuAccessor;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Optional coexistence with Ramixin's VisibleTraders (server-side mod).
 *
 * <p>VisibleTraders appends a villager's still-locked FUTURE trade sets to the
 * CLIENT's open {@link MerchantMenu} and wraps {@code getOffers()} to expose that
 * combined list while the screen renders or handles mouse input. Those extra
 * entries exist only on the client and cannot be bought before the villager levels
 * up; clicking one sends an index the server does not know. Match and lock paths
 * therefore read the menu's real trader list ({@code Merchant#getOffers()}), which
 * VisibleTraders does not wrap, instead of the combined list.
 *
 * <p>The server sends {@code visibletraders:locked_trades} whenever a merchant menu
 * opens, so seeing that payload (on 1.20.5+, where the decoded payload id is
 * exposed) proves the OTHER side has the mod. It is only a diagnostic: the
 * real-offer read is chosen by the config switch, which also makes the behavior
 * safe when an older VisibleTraders build uses another payload id, when only the
 * client side is installed (no payload at all), or on the older nodes where the
 * payload id cannot be read without a remapped accessor.
 */
public final class VisibleTradersCompat {

	public static final String LOCKED_TRADES_CHANNEL = "visibletraders:locked_trades";

	private static volatile boolean serverPayloadSeen = false;

	/** A new connection starts without knowing whether the server has the mod. */
	public static void reset() {
		serverPayloadSeen = false;
	}

	/** Called from the connection mixin for every custom payload, on the netty thread. */
	public static void onServerPayload(String channel) {
		if (serverPayloadSeen || !LOCKED_TRADES_CHANNEL.equals(channel)) return;
		if (!TradeMasterConfig.INSTANCE.compatibleWithVisibleTraders) return;
		serverPayloadSeen = true;
		TradeMasterClient.LOGGER.info(
				"VisibleTraders detected on the server; its locked (pre-revealed) trades are ignored for matching and locking");
	}

	/**
	 * The payload id of a raw custom-payload packet. From 1.20.5 the decoded
	 * {@code CustomPacketPayload} is exposed, so the server's VisibleTraders payload
	 * can be recognised. VisibleTraders itself targets modern versions; on the older
	 * nodes the safe server-confirmed-offer read is unconditional and this diagnostic
	 * is skipped (its private field is remapped in production, so a literal
	 * reflection lookup would silently never work there).
	 */
	public static void onServerCustomPayload(Object packet) {
		//? if >=1.20.5 {
		if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom
				&& custom.payload() != null) {
			onServerPayload(custom.payload().type().id().toString());
		}
		//?}
	}

	/** Whether the current server has proven (via its payload) that VisibleTraders is installed. */
	public static boolean serverHasVisibleTraders() {
		return serverPayloadSeen;
	}

	/**
	 * The offers the SERVER actually knows about. With the compatibility switch on
	 * this bypasses VisibleTraders' wrapped {@code MerchantMenu#getOffers()}, whose
	 * combined view can include pre-revealed entries that cannot be traded.
	 */
	public static MerchantOffers menuOffers(MerchantMenu menu) {
		if (TradeMasterConfig.INSTANCE.compatibleWithVisibleTraders) {
			Merchant trader = ((MerchantMenuAccessor) menu).getTrader();
			if (trader != null) {
				MerchantOffers offers = trader.getOffers();
				if (offers != null) return offers;
			}
		}
		return menu.getOffers();
	}

	private VisibleTradersCompat() {}
}
