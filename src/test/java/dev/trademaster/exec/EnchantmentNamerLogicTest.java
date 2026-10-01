package dev.trademaster.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The villager-name text transforms. A mismatch between the naming side and the deduction
 * side silently makes books non-deductable, so these run as pure functions. */
class EnchantmentNamerLogicTest {

	@Test
	void sanitizeReplacesPlusWithStar() {
		assertEquals("A*B", EnchantmentNamer.sanitizeAlias("A+B"));
		assertEquals("**", EnchantmentNamer.sanitizeAlias("++"));
	}

	@Test
	void sanitizeTurnsDigitsIntoSuperscripts() {
		assertEquals("Power ²", EnchantmentNamer.sanitizeAlias("Power 2"));
		assertEquals("Sh⁰⁹", EnchantmentNamer.sanitizeAlias("Sh09"));
	}

	@Test
	void sanitizeLeavesCleanNamesAlone() {
		assertEquals("Mending", EnchantmentNamer.sanitizeAlias("Mending"));
		assertEquals("", EnchantmentNamer.sanitizeAlias(""));
		assertEquals("Protection IV", EnchantmentNamer.sanitizeAlias("Protection IV"));
	}

	@Test
	void canonicalCollapsesWhitespaceRuns() {
		assertEquals("a b c", EnchantmentNamer.canonical("a   b \t c"));
		assertEquals("a b", EnchantmentNamer.canonical("a\n\nb"));
		assertEquals("a b", EnchantmentNamer.canonical("a\r\f b"));
	}

	@Test
	void canonicalTrimsBothEnds() {
		assertEquals("a b", EnchantmentNamer.canonical("  a b  "));
		assertEquals("a", EnchantmentNamer.canonical("\t a \n"));
	}

	@Test
	void canonicalEmptyAndWhitespaceOnly() {
		assertEquals("", EnchantmentNamer.canonical(""));
		assertEquals("", EnchantmentNamer.canonical("    \t\n"));
	}

	@Test
	void romanNumeralsCommonValues() {
		assertEquals("I", EnchantmentNamer.romanNumerals(1));
		assertEquals("II", EnchantmentNamer.romanNumerals(2));
		assertEquals("IV", EnchantmentNamer.romanNumerals(4));
		assertEquals("V", EnchantmentNamer.romanNumerals(5));
		assertEquals("IX", EnchantmentNamer.romanNumerals(9));
		assertEquals("X", EnchantmentNamer.romanNumerals(10));
		assertEquals("XIV", EnchantmentNamer.romanNumerals(14));
		assertEquals("XL", EnchantmentNamer.romanNumerals(40));
		assertEquals("XC", EnchantmentNamer.romanNumerals(90));
		assertEquals("CD", EnchantmentNamer.romanNumerals(400));
		assertEquals("CM", EnchantmentNamer.romanNumerals(900));
		assertEquals("M", EnchantmentNamer.romanNumerals(1000));
	}

	@Test
	void romanNumeralsBounds() {
		assertEquals("MMMCMXCIX", EnchantmentNamer.romanNumerals(3999));
		// beyond MMM the roman form would not round-trip: falls back to decimal
		assertEquals("4000", EnchantmentNamer.romanNumerals(4000));
		assertEquals("0", EnchantmentNamer.romanNumerals(0));
		assertEquals("-1", EnchantmentNamer.romanNumerals(-1));
	}
}
