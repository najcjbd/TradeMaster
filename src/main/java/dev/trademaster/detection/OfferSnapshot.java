package dev.trademaster.detection;

import net.minecraft.world.item.trading.MerchantOffers;

/** One observation of the villager's current trade list. */
public record OfferSnapshot(MerchantOffers offers) {
	// the EVENT channel hands us the villager's LIVE offer list, which the
	// server can rewrite on restock/profession change; copy so the snapshot
	// stays valid even if it is consumed a tick later than today (R20-12)
	public OfferSnapshot {
		offers = dev.trademaster.compat.McTrade.copyOffers(offers);
	}
}
