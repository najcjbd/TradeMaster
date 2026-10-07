package dev.trademaster.config;

import dev.trademaster.compat.McIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoodSpecTest {

	private static final ResourceLocation COMPOSTER = McIds.mc("composter");

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
				new TradeOfferSpec.TradeCost(McIds.mc("book"), 5000));
		GoodSpec good = new GoodSpec(COMPOSTER, 1, true, 10, dirty).normalized();
		assertEquals(TradeOfferSpec.AIR, good.costs().get(0).item());
		// 5000 is inside the slot-count range, so it is preserved (only the extremes clamp)
		assertEquals(5000, good.costs().get(1).count());
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
