package dev.trademaster.config;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The offer-shape rules: the subset match, the slot caps, and normalisation. */
class TradeOfferSpecTest {

	private static final Identifier EMERALD = TradeOfferSpec.EMERALD;
	private static final Identifier BOOK = TradeOfferSpec.BOOK;
	private static final Identifier AIR = TradeOfferSpec.AIR;

	@Test
	void defaultsAreEmeraldPlusBook() {
		TradeOfferSpec d = TradeOfferSpec.defaults();
		assertEquals(TradeOfferSpec.ENCHANTED_BOOK, d.result());
		assertEquals(2, d.costs().size());
		assertEquals(EMERALD, d.costs().get(0).item());
		assertEquals(0, d.costs().get(0).count());
		assertEquals(BOOK, d.costs().get(1).item());
		assertEquals(TradeOfferSpec.UNLIMITED, d.costs().get(1).count());
	}

	@Test
	void defaultGoodCostsPayEmeraldsAndNoSecondSlot() {
		List<TradeOfferSpec.TradeCost> costs = TradeOfferSpec.defaultGoodCosts();
		assertEquals(EMERALD, costs.get(0).item());
		assertEquals(AIR, costs.get(1).item());
		assertEquals(TradeOfferSpec.UNLIMITED, costs.get(1).count());
	}

	@Test
	void slotZeroCapIsThePrice() {
		// slot 1's stored count is ignored: its amount is the target's own price
		assertEquals(7, TradeOfferSpec.capFor(0, new TradeOfferSpec.TradeCost(EMERALD, 999), 7));
		assertEquals(0, TradeOfferSpec.capFor(0, null, 0));
		assertEquals(12, TradeOfferSpec.capFor(0, new TradeOfferSpec.TradeCost(EMERALD, -5), 12));
	}

	@Test
	void laterSlotCaps() {
		assertEquals(0, TradeOfferSpec.capFor(1, null, 99));
		assertEquals(TradeOfferSpec.UNLIMITED_CAP,
				TradeOfferSpec.capFor(1, new TradeOfferSpec.TradeCost(BOOK, -1), 99));
		assertEquals(0, TradeOfferSpec.capFor(1, new TradeOfferSpec.TradeCost(BOOK, 0), 99));
		assertEquals(5, TradeOfferSpec.capFor(1, new TradeOfferSpec.TradeCost(BOOK, 5), 99));
		assertEquals(TradeOfferSpec.UNLIMITED_CAP,
				TradeOfferSpec.capFor(2, new TradeOfferSpec.TradeCost(AIR, -3), 99));
	}

	@Test
	void costAtBounds() {
		TradeOfferSpec spec = new TradeOfferSpec(
				List.of(new TradeOfferSpec.TradeCost(EMERALD, 0)), TradeOfferSpec.ENCHANTED_BOOK);
		assertEquals(EMERALD, spec.costAt(0).item());
		assertNull(spec.costAt(1));
		assertNull(spec.costAt(-1));
		assertNull(spec.costAt(99));
	}

	@Test
	void normalizedFillsMissingItemsWithAir() {
		TradeOfferSpec spec = new TradeOfferSpec(
				List.of(new TradeOfferSpec.TradeCost(null, 3), new TradeOfferSpec.TradeCost(BOOK, 1)),
				null);
		TradeOfferSpec n = spec.normalized();
		assertEquals(AIR, n.costs().get(0).item());
		assertEquals(BOOK, n.costs().get(1).item());
		assertEquals(TradeOfferSpec.ENCHANTED_BOOK, n.result());
	}

	@Test
	void normalizedKeepsPositionsAndClampsCounts() {
		TradeOfferSpec spec = new TradeOfferSpec(List.of(
				new TradeOfferSpec.TradeCost(EMERALD, 2),
				new TradeOfferSpec.TradeCost(BOOK, -999),
				new TradeOfferSpec.TradeCost(BOOK, 2_000_000),
				new TradeOfferSpec.TradeCost(BOOK, 4)), EMERALD);
		TradeOfferSpec n = spec.normalized();
		assertEquals(4, n.costs().size());
		assertEquals(2, n.costs().get(0).count());
		assertEquals(TradeOfferSpec.UNLIMITED, n.costs().get(1).count());
		assertEquals(TradeOfferSpec.UNLIMITED_CAP, n.costs().get(2).count());
		assertEquals(4, n.costs().get(3).count());
		assertEquals(EMERALD, n.costs().get(0).item());
		assertEquals(BOOK, n.costs().get(3).item());
	}

	@Test
	void normalizedNullCostBecomesAirZero() {
		TradeOfferSpec spec = new TradeOfferSpec(java.util.Arrays.asList(null, null), EMERALD);
		TradeOfferSpec n = spec.normalized();
		assertEquals(AIR, n.costs().get(0).item());
		assertEquals(0, n.costs().get(0).count());
	}

	@Test
	void normalizedResultFallsBackToEnchantedBook() {
		TradeOfferSpec n = new TradeOfferSpec(List.of(), null).normalized();
		assertSame(TradeOfferSpec.ENCHANTED_BOOK, n.result());
	}
}
