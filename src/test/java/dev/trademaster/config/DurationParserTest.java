package dev.trademaster.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationParserTest {

	@Test
	void plainNumberIsTicks() {
		assertEquals(1, DurationParser.parse("1"));
		assertEquals(3600, DurationParser.parse("3600"));
	}

	@Test
	void unitSuffixes() {
		assertEquals(20, DurationParser.parse("20t"));
		assertEquals(20, DurationParser.parse("20tick"));
		assertEquals(20, DurationParser.parse("20ticks"));
		assertEquals(20, DurationParser.parse("1s"));
		assertEquals(1800, DurationParser.parse("90s"));
		assertEquals(1800, DurationParser.parse("90sec"));
		assertEquals(1800, DurationParser.parse("90seconds"));
		assertEquals(1200, DurationParser.parse("1m"));
		assertEquals(1200, DurationParser.parse("1min"));
		assertEquals(2400, DurationParser.parse("2minutes"));
		assertEquals(72000, DurationParser.parse("1h"));
		assertEquals(72000, DurationParser.parse("1hr"));
		assertEquals(144000, DurationParser.parse("2hours"));
	}

	@Test
	void compoundForms() {
		assertEquals(72001, DurationParser.parse("1h1"));
		assertEquals(108000, DurationParser.parse("1h30m"));
		assertEquals(108021, DurationParser.parse("1h30m1s1"));
		assertEquals(75600, DurationParser.parse("1h3min"));
	}

	@Test
	void caseAndSpaceInsensitive() {
		assertEquals(108000, DurationParser.parse("1H30M"));
		assertEquals(108000, DurationParser.parse(" 1h 30min "));
		assertEquals(72001, DurationParser.parse("1H 1"));
	}

	@Test
	void invalidInputs() {
		assertEquals(-1, DurationParser.parse(null));
		assertEquals(-1, DurationParser.parse(""));
		assertEquals(-1, DurationParser.parse("   "));
		assertEquals(-1, DurationParser.parse("abc"));
		assertEquals(-1, DurationParser.parse("h"));
		assertEquals(-1, DurationParser.parse("1x"));
		assertEquals(-1, DurationParser.parse("0"));
		assertEquals(-1, DurationParser.parse("1h1x"));
		assertEquals(-1, DurationParser.parse("1 2 3 4 5 s t"));
	}

	@Test
	void overflowRejected() {
		assertEquals(-1, DurationParser.parse(String.valueOf(Long.MAX_VALUE)));
	}

	@Test
	void formatShortestForm() {
		assertEquals("0", DurationParser.format(0));
		assertEquals("7t", DurationParser.format(7));
		assertEquals("1s", DurationParser.format(20));
		assertEquals("1min30s", DurationParser.format(1800));
		assertEquals("1h", DurationParser.format(72000));
		assertEquals("1h3min", DurationParser.format(75600));
		assertEquals("1h30min", DurationParser.format(108000));
		assertEquals("1h3min1s1t", DurationParser.format(75621));
	}

	@Test
	void formatParseRoundTrip() {
		int[] samples = { 1, 7, 19, 20, 1199, 1200, 1800, 71999, 72000, 75600, 75621, 20 * 60 * 60 };
		for (int ticks : samples) {
			assertEquals(ticks, DurationParser.parse(DurationParser.format(ticks)),
					"round trip failed for " + ticks);
		}
	}
}
