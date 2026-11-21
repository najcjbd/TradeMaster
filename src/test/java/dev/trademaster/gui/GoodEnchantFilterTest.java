package dev.trademaster.gui;

import dev.trademaster.compat.McIds;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.testutil.McTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoodEnchantFilterTest {

	@BeforeAll
	static void boot() {
		McTest.boot();
	}

	@Test
	void presenceExactAndAtLeastTokens() {
		var presence = OffersListWidget.parseGoodEnchants("minecraft:sharpness");
		assertNotNull(presence);
		assertEquals(1, presence.include().size());
		assertEquals(0, presence.include().get(0).level());
		assertFalse(presence.include().get(0).atLeast());

		var zero = OffersListWidget.parseGoodEnchants("minecraft:sharpness:0");
		assertNotNull(zero);
		assertEquals(0, zero.include().get(0).level());

		var exact = OffersListWidget.parseGoodEnchants("minecraft:sharpness:5");
		assertNotNull(exact);
		assertEquals(5, exact.include().get(0).level());
		assertFalse(exact.include().get(0).atLeast());

		var atLeast = OffersListWidget.parseGoodEnchants("minecraft:sharpness:5..");
		assertNotNull(atLeast);
		assertEquals(5, atLeast.include().get(0).level());
		assertTrue(atLeast.include().get(0).atLeast());
	}

	@Test
	void exclusionsAndOverlaps() {
		var parsed = OffersListWidget.parseGoodEnchants(
				"minecraft:sharpness:5, minecraft:mending, !minecraft:fire_aspect");
		assertNotNull(parsed);
		assertEquals(2, parsed.include().size());
		assertEquals(1, parsed.exclude().size());
		assertEquals(McIds.mc("fire_aspect"), parsed.exclude().get(0).id());
		assertNull(OffersListWidget.parseGoodEnchants("minecraft:sharpness:5, !minecraft:sharpness"));
		assertNull(OffersListWidget.parseGoodEnchants("minecraft:sharpness:5, minecraft:sharpness:6"));
		assertNull(OffersListWidget.parseGoodEnchants("!minecraft:sharpness:5"));
	}

	@Test
	void malformedTokensAreRejected() {
		assertNull(OffersListWidget.parseGoodEnchants("minecraft:sharpness:5..6"));
		assertNull(OffersListWidget.parseGoodEnchants("minecraft:sharpness:.."));
		assertNull(OffersListWidget.parseGoodEnchants("minecraft:sharpness:not-a-level"));
	}

	@Test
	void tokenFormattingRoundTrips() {
		var parsed = OffersListWidget.parseGoodEnchants(
				"minecraft:sharpness:5.., minecraft:mending:0, !minecraft:fire_aspect");
		assertNotNull(parsed);
		String formatted = OffersListWidget.formatGoodEnchants(parsed.include(), parsed.exclude());
		var again = OffersListWidget.parseGoodEnchants(formatted);
		assertNotNull(again);
		assertEquals(parsed.include(), again.include());
		assertEquals(parsed.exclude(), again.exclude());
	}

	@Test
	void levelMatchingSemantics() {
		var presence = new GoodSpec.GoodEnchant(McIds.mc("sharpness"), 0);
		assertTrue(presence.levelMatches(1));
		assertTrue(presence.levelMatches(10));
		var exact = new GoodSpec.GoodEnchant(McIds.mc("sharpness"), 5);
		assertTrue(exact.levelMatches(5));
		assertFalse(exact.levelMatches(4));
		assertFalse(exact.levelMatches(6));
		var atLeast = new GoodSpec.GoodEnchant(McIds.mc("sharpness"), 5, true);
		assertTrue(atLeast.levelMatches(5));
		assertTrue(atLeast.levelMatches(6));
		assertFalse(atLeast.levelMatches(4));
	}
}
