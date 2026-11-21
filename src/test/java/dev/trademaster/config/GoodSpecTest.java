package dev.trademaster.config;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoodSpecTest {

	private static final Identifier COMPOSTER = Identifier.withDefaultNamespace("composter");

	@Test
	void idIsTheResultItem() {
		GoodSpec good = new GoodSpec(COMPOSTER, 1, true, 10, TradeOfferSpec.defaultGoodCosts());
		assertEquals(COMPOSTER, good.id());
		assertEquals(COMPOSTER, good.spec().result());
	}

	@Test
	void normalizedClampsResultCountAndPrice() {
		GoodSpec zero = new GoodSpec(COMPOSTER, 0, true, -5, TradeOfferSpec.defaultGoodCosts()).normalized();
		assertEquals(1, zero.resultCount());
		assertEquals(0, zero.maxPrice());

		GoodSpec high = new GoodSpec(COMPOSTER, 999, true, 999, TradeOfferSpec.defaultGoodCosts()).normalized();
		assertEquals(64, high.resultCount());
		assertEquals(64, high.maxPrice());
	}

	@Test
	void normalizedCleansCostsThroughTheSpecSanitiser() {
		// a hand-written slot with no item must become air, never a null that would throw
		List<TradeOfferSpec.TradeCost> dirty = java.util.Arrays.asList(
				new TradeOfferSpec.TradeCost(null, 1),
				new TradeOfferSpec.TradeCost(Identifier.withDefaultNamespace("book"), 5000));
		GoodSpec good = new GoodSpec(COMPOSTER, 1, true, 10, dirty).normalized();
		assertEquals(TradeOfferSpec.AIR, good.costs().get(0).item());
		// 5000 is inside the slot-count range, so it is preserved (only the extremes clamp)
		assertEquals(5000, good.costs().get(1).count());
	}

	@Test
	void enchantmentLevelMatchingCoversPresenceExactAndRanges() {
		Identifier sharpness = Identifier.withDefaultNamespace("sharpness");
		assertTrue(GoodSpec.GoodEnchant.present(sharpness).levelMatches(1));
		assertTrue(GoodSpec.GoodEnchant.present(sharpness).levelMatches(5));
		assertFalse(GoodSpec.GoodEnchant.present(sharpness).levelMatches(0));
		assertTrue(GoodSpec.GoodEnchant.absent(sharpness).levelMatches(0));
		assertFalse(GoodSpec.GoodEnchant.absent(sharpness).levelMatches(1));
		assertTrue(GoodSpec.GoodEnchant.exact(sharpness, 5).levelMatches(5));
		assertFalse(GoodSpec.GoodEnchant.exact(sharpness, 5).levelMatches(4));
		assertTrue(GoodSpec.GoodEnchant.range(sharpness, 1, 2).levelMatches(2));
		assertFalse(GoodSpec.GoodEnchant.range(sharpness, 1, 2).levelMatches(3));
		assertTrue(GoodSpec.GoodEnchant.range(sharpness, 2, 0).levelMatches(9));
	}

	@Test
	void invalidFilterStaysUnusableUntilTheEnchantmentsAreEdited() {
		GoodSpec good = new GoodSpec(COMPOSTER, 1, true, 10, TradeOfferSpec.defaultGoodCosts())
				.withFilterInvalid(true);
		assertFalse(good.filterValid());
		assertTrue(good.withEnabled(false).filterInvalid());
		assertTrue(good.withTrade(2, 5, TradeOfferSpec.defaultGoodCosts()).filterInvalid());
		assertTrue(good.withResult(Identifier.withDefaultNamespace("barrel")).filterInvalid());
		assertTrue(good.withFilter("name", List.of(), "", List.of(), "").filterInvalid());
		assertTrue(good.withFilterInvalid(false).filterValid());
	}

	@Test
	void withEnabledKeepsEverythingElse() {
		GoodSpec good = new GoodSpec(COMPOSTER, 3, true, 20, TradeOfferSpec.defaultGoodCosts());
		GoodSpec off = good.withEnabled(false);
		assertEquals(false, off.enabled());
		assertEquals(3, off.resultCount());
		assertEquals(20, off.maxPrice());
		assertEquals(COMPOSTER, off.result());
	}
}
