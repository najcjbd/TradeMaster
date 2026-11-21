package dev.trademaster.gui;

import net.minecraft.resources.Identifier;
import dev.trademaster.config.GoodEnchantFilter;
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
	void presenceAbsenceExactAndRanges() {
		var presence = parse("minecraft:sharpness");
		assertEquals(1, presence.include().get(0).minLevel());
		assertEquals(0, presence.include().get(0).maxLevel());
		assertTrue(presence.include().get(0).levelMatches(1));
		assertTrue(presence.include().get(0).levelMatches(10));
		assertFalse(presence.include().get(0).levelMatches(0));

		var absence = parse("minecraft:sharpness:0");
		assertTrue(absence.include().get(0).absent());
		assertTrue(absence.include().get(0).levelMatches(0));
		assertFalse(absence.include().get(0).levelMatches(1));

		var exact = parse("minecraft:sharpness:5");
		assertEquals(5, exact.include().get(0).minLevel());
		assertEquals(5, exact.include().get(0).maxLevel());
		assertTrue(exact.include().get(0).levelMatches(5));
		assertFalse(exact.include().get(0).levelMatches(4));
		assertFalse(exact.include().get(0).levelMatches(6));

		var atLeast = parse("minecraft:sharpness:5..");
		assertEquals(5, atLeast.include().get(0).minLevel());
		assertEquals(0, atLeast.include().get(0).maxLevel());
		assertTrue(atLeast.include().get(0).levelMatches(5));
		assertTrue(atLeast.include().get(0).levelMatches(6));
		assertFalse(atLeast.include().get(0).levelMatches(4));

		var range = parse("minecraft:sharpness:1..2");
		assertEquals(1, range.include().get(0).minLevel());
		assertEquals(2, range.include().get(0).maxLevel());
		assertTrue(range.include().get(0).levelMatches(1));
		assertTrue(range.include().get(0).levelMatches(2));
		assertFalse(range.include().get(0).levelMatches(0));
		assertFalse(range.include().get(0).levelMatches(3));
	}

	@Test
	void openAndZeroLowerBoundsMeanPresent() {
		var openLow = parse("minecraft:sharpness:..2");
		assertEquals(1, openLow.include().get(0).minLevel());
		assertEquals(2, openLow.include().get(0).maxLevel());
		assertTrue(openLow.include().get(0).levelMatches(1));
		assertFalse(openLow.include().get(0).levelMatches(3));

		var zeroLow = parse("minecraft:sharpness:0..2");
		assertEquals(1, zeroLow.include().get(0).minLevel());
		assertEquals(2, zeroLow.include().get(0).maxLevel());

		var zeroOpen = parse("minecraft:sharpness:0..");
		assertEquals(1, zeroOpen.include().get(0).minLevel());
		assertEquals(0, zeroOpen.include().get(0).maxLevel());
		assertTrue(zeroOpen.include().get(0).levelMatches(99));

		var fullyOpen = parse("minecraft:sharpness:..");
		assertEquals(1, fullyOpen.include().get(0).minLevel());
		assertTrue(fullyOpen.include().get(0).levelMatches(1));
	}

	@Test
	void exclusionsAndInvalidCombinations() {
		var parsed = parse("minecraft:sharpness:1..2, minecraft:mending, !minecraft:fire_aspect:1..2");
		assertEquals(2, parsed.include().size());
		assertEquals(1, parsed.exclude().size());
		assertEquals(1, parsed.exclude().get(0).minLevel());
		assertEquals(2, parsed.exclude().get(0).maxLevel());
		assertNull(GoodEnchantFilter.parse("sharpness:5, sharpness:6"));
		assertNull(GoodEnchantFilter.parse("sharpness:5, !sharpness"));
		assertNull(GoodEnchantFilter.parse("sharpness:5..3"));
		assertNull(GoodEnchantFilter.parse("sharpness:5..6..7"));
		assertNull(GoodEnchantFilter.parse("sharpness:5.6"));
		assertNull(GoodEnchantFilter.parse("sharpness:-1"));
	}

	@Test
	void tokenFormattingRoundTrips() {
		var parsed = parse("minecraft:sharpness:..2, minecraft:mending, minecraft:unbreaking:0, !minecraft:fire_aspect:1..2");
		assertNotNull(parsed);
		String formatted = GoodEnchantFilter.format(parsed.include(), parsed.exclude());
		var again = GoodEnchantFilter.parse(formatted);
		assertNotNull(again);
		assertEquals(parsed.include(), again.include());
		assertEquals(parsed.exclude(), again.exclude());
	}

	private static GoodEnchantFilter.Parsed parse(String raw) {
		var parsed = GoodEnchantFilter.parse(raw);
		assertNotNull(parsed);
		return parsed;
	}
}
