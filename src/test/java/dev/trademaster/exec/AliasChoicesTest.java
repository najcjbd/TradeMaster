package dev.trademaster.exec;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AliasChoicesTest {

	private static final Identifier GOOD = Identifier.withDefaultNamespace("test_good");

	@Test
	void rawAndAliasFormsResolveToTheChosenOwner() {
		Map<String, Identifier> raw = Map.of("Fire 5", GOOD);
		Map<String, Identifier> canonical = AliasChoices.canonical(raw);
		assertEquals(GOOD, canonical.get("Fire 5"));
		String aliasForm = EnchantmentNamer.sanitizeAlias("Fire 5");
		assertNotEquals("Fire 5", aliasForm);
		assertEquals(GOOD, canonical.get(EnchantmentNamer.canonical(aliasForm)));
		assertEquals(GOOD, AliasChoices.resolve("Fire 5", raw));
		assertEquals(GOOD, AliasChoices.resolve(aliasForm, raw));
	}

	@Test
	void duplicateCanonicalNamesWithDifferentOwnersAreAmbiguous() {
		java.util.Map<String, Identifier> raw = new java.util.LinkedHashMap<>();
		raw.put("Diamond  Sword", GOOD);
		raw.put("Diamond Sword", Identifier.withDefaultNamespace("other_good"));
		assertNull(AliasChoices.resolve("Diamond Sword", raw));
		assertNull(AliasChoices.resolve("Diamond  Sword", raw));
	}

	@Test
	void unknownNamesAndStaleEntriesAreIgnored() {
		assertNull(AliasChoices.resolve("missing", Map.of("other", GOOD)));
		assertNull(AliasChoices.resolve(null, Map.of("other", GOOD)));
		assertNull(AliasChoices.resolve("other", Map.of()));
	}
}
