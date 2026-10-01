package dev.trademaster.gui;

import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.testutil.McTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Villagers tab's inline id validation. Headless, because the decision is what the
 * commit refuses on: the registry lookup here must NOT go through the defaulting
 * {@code getValue()}, or every typo would read as "known" and the red hint would never show.
 */
class VillagerIdValidationTest {

	@BeforeAll
	static void boot() {
		McTest.boot();
	}

	@Test
	void knownBlockPasses() {
		assertNull(VillagersListWidget.workBlockError("minecraft:lectern"));
		assertNull(VillagersListWidget.workBlockError("minecraft:composter"));
	}

	@Test
	void unknownBlockIsReported() {
		assertEquals("trademaster.villagers.unknown_block",
				VillagersListWidget.workBlockError("unknownmod:nope"));
		assertEquals("trademaster.villagers.unknown_block",
				VillagersListWidget.workBlockError("minecraft:definitely_not_a_block"));
	}

	@Test
	void airIsReportedAsAnUnusableWorkBlock() {
		assertEquals("trademaster.villagers.unknown_block",
				VillagersListWidget.workBlockError("minecraft:air"));
	}

	@Test
	void knownProfessionPasses() {
		assertNull(VillagersListWidget.professionError("minecraft:librarian"));
		assertNull(VillagersListWidget.professionError("minecraft:farmer"));
	}

	@Test
	void unknownProfessionIsReported() {
		assertEquals("trademaster.villagers.unknown_profession",
				VillagersListWidget.professionError("unknownmod:weird_job"));
	}

	@Test
	void aNonTradeableProfessionCannotBeTheTarget() {
		TradeMasterConfig.INSTANCE.nonTradeableProfessions.clear();
		try {
			// the built-in never-trades profession
			assertEquals("trademaster.villagers.not_tradeable",
					VillagersListWidget.professionError("minecraft:nitwit"));
			// and one the user listed as non-tradeable
			TradeMasterConfig.INSTANCE.nonTradeableProfessions.add("minecraft:cleric");
			assertEquals("trademaster.villagers.not_tradeable",
					VillagersListWidget.professionError("minecraft:cleric"));
			// a normal profession is still fine
			assertNull(VillagersListWidget.professionError("minecraft:librarian"));
		} finally {
			TradeMasterConfig.INSTANCE.nonTradeableProfessions.clear();
		}
	}

	@Test
	void listEntriesRejectTheTargetAndTheUnemployedId() {
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		String savedProfession = c.professionId;
		c.nonTradeableProfessions.clear();
		try {
			c.professionId = "minecraft:librarian";
			// a normal other profession is fine to list
			assertNull(VillagersListWidget.nonTradeableEntryError("minecraft:cleric"));
			assertNull(VillagersListWidget.nonTradeableEntryError("minecraft:nitwit"));
			// unemployment must stay usable, and the target may not be listed
			assertEquals("trademaster.villagers.list_none",
					VillagersListWidget.nonTradeableEntryError("minecraft:none"));
			assertEquals("trademaster.villagers.list_target",
					VillagersListWidget.nonTradeableEntryError("minecraft:librarian"));
			// an unknown id is still just unknown
			assertEquals("trademaster.villagers.unknown_profession",
					VillagersListWidget.nonTradeableEntryError("unknownmod:job"));
			// and the "target" follows the config
			c.professionId = "minecraft:cleric";
			assertEquals("trademaster.villagers.list_target",
					VillagersListWidget.nonTradeableEntryError("minecraft:cleric"));
			assertNull(VillagersListWidget.nonTradeableEntryError("minecraft:librarian"));
		} finally {
			c.professionId = savedProfession;
			c.nonTradeableProfessions.clear();
		}
	}

	@Test
	void theUnemployedProfessionCannotBeTheTarget() {
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		c.nonTradeableProfessions.clear();
		try {
			// it sells no trade, so as a target the run could never be satisfied
			assertEquals("trademaster.villagers.profession_unemployed",
					VillagersListWidget.professionError("minecraft:none"));
			assertNull(VillagersListWidget.professionError("minecraft:librarian"));
		} finally {
			c.nonTradeableProfessions.clear();
		}
	}

	@Test
	void theListComparesTheEffectiveTarget() {
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		String savedProfession = c.professionId;
		List<String> savedList = List.copyOf(c.nonTradeableProfessions);
		try {
			c.nonTradeableProfessions.clear();
			// the RAW target may not be listed, even when it degrades to something else
			c.professionId = "minecraft:nitwit";
			assertEquals("trademaster.villagers.list_target",
					VillagersListWidget.nonTradeableEntryError("minecraft:nitwit"));
			// and neither may the EFFECTIVE target the degrade lands on
			assertEquals("trademaster.villagers.list_target",
					VillagersListWidget.nonTradeableEntryError("minecraft:librarian"));
			// a profession that is neither the raw nor the effective target is fine
			assertNull(VillagersListWidget.nonTradeableEntryError("minecraft:cleric"));
		} finally {
			c.professionId = savedProfession;
			c.nonTradeableProfessions.clear();
			c.nonTradeableProfessions.addAll(savedList);
		}
	}

	@Test
	void anOverlongListIsRefused() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i <= TradeMasterConfig.MAX_NON_TRADEABLE; i++) {
			if (i > 0) text.append(',');
			text.append('a').append(i);
		}
		// the reader truncates at the cap, so the GUI must not write past it
		assertNull(VillagersListWidget.parseList(text.toString()));
		assertTrue(VillagersListWidget.isOverlong(text.toString()));
		assertFalse(VillagersListWidget.isOverlong("a,b,c"));
	}

	@Test
	void listParsingNormalisesAndDeduplicates() {
		assertEquals(List.of("minecraft:a", "minecraft:b", "minecraft:c"),
				VillagersListWidget.parseList("a, b   C"));
		assertEquals(List.of("some_mod:job"), VillagersListWidget.parseList("some_mod:job"));
		assertEquals(List.of("minecraft:a"), VillagersListWidget.parseList("a, a"));
		// an empty field is a valid empty list, garbage is not
		assertEquals(List.of(), VillagersListWidget.parseList("   "));
		assertEquals(List.of(), VillagersListWidget.parseList(""));
		assertNull(VillagersListWidget.parseList("!!!"));
		assertNull(VillagersListWidget.parseList("minecraft:nitwit, !!!"));
	}
}
