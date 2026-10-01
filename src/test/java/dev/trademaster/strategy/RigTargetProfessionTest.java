package dev.trademaster.strategy;

import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.testutil.McTest;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Profession resolution and the "unusable" rule that decides summon vs keep. */
class RigTargetProfessionTest {

	private JsonObject snapshot;

	@BeforeAll
	static void boot() {
		McTest.boot();
	}

	@BeforeEach
	void snap() {
		snapshot = ConfigTest.snapshot();
	}

	@AfterEach
	void restore() {
		ConfigTest.restore(snapshot);
	}

	private TradeMasterConfig c() {
		return TradeMasterConfig.INSTANCE;
	}

	@Test
	void defaultKeyIsLibrarianAndKnown() {
		c().professionId = "minecraft:librarian";
		assertEquals(VillagerProfession.LIBRARIAN, RigTarget.professionKey());
		assertTrue(RigTarget.professionKnown());
	}

	@Test
	void namespacelessResolves() {
		c().professionId = "farmer";
		assertEquals(VillagerProfession.FARMER, RigTarget.professionKey());
		assertTrue(RigTarget.professionKnown());
	}

	@Test
	void blankFallsBackToLibrarian() {
		c().professionId = "";
		assertEquals(VillagerProfession.LIBRARIAN, RigTarget.professionKey());
		c().professionId = "!!!";
		assertEquals(VillagerProfession.LIBRARIAN, RigTarget.professionKey());
	}

	@Test
	void syntacticallyValidButUnknownIdIsReportedUnknown() {
		c().professionId = "unknownmod:weird_job";
		ResourceKey<VillagerProfession> key = RigTarget.professionKey();
		assertEquals("unknownmod", key.identifier().getNamespace());
		assertFalse(RigTarget.professionKnown());
	}

	@Test
	void isTargetMatchesTheConfiguredProfession() {
		c().professionId = "minecraft:librarian";
		assertTrue(RigTarget.isTarget(McTest.profession(VillagerProfession.LIBRARIAN)));
		assertFalse(RigTarget.isTarget(McTest.profession(VillagerProfession.FARMER)));
	}

	@Test
	void unusableRuleUnderDefaultLibrarian() {
		c().professionId = "minecraft:librarian";
		// a nitwit never works
		assertTrue(RigTarget.isUnusable(McTest.profession(VillagerProfession.NITWIT)));
		// unemployed is usable: it can still take the job
		assertFalse(RigTarget.isUnusable(McTest.profession(VillagerProfession.NONE)));
		// already the target profession
		assertFalse(RigTarget.isUnusable(McTest.profession(VillagerProfession.LIBRARIAN)));
		// employed elsewhere
		assertTrue(RigTarget.isUnusable(McTest.profession(VillagerProfession.FARMER)));
	}

	@Test
	void unusableRuleFollowsTheConfiguredProfession() {
		c().professionId = "minecraft:farmer";
		assertFalse(RigTarget.isUnusable(McTest.profession(VillagerProfession.FARMER)));
		assertTrue(RigTarget.isUnusable(McTest.profession(VillagerProfession.LIBRARIAN)));
	}

	@Test
	void nitwitIsNonTradeableButUnemployedIsNot() {
		c().nonTradeableProfessions.clear();
		assertTrue(RigTarget.isNonTradeable(VillagerProfession.NITWIT));
		// unemployment can still take the configured job, so it stays usable
		assertFalse(RigTarget.isNonTradeable(VillagerProfession.NONE));
		assertFalse(RigTarget.isNonTradeable(VillagerProfession.LIBRARIAN));
		assertFalse(RigTarget.isNonTradeable(VillagerProfession.FARMER));
	}

	@Test
	void configuredNonTradeableListIsHonoured() {
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("minecraft:cleric");
		c().nonTradeableProfessions.add("some_mod:broken_job");
		assertTrue(RigTarget.isNonTradeable(VillagerProfession.CLERIC));
		assertTrue(RigTarget.isNonTradeable(
				ResourceKey.create(Registries.VILLAGER_PROFESSION,
						Identifier.fromNamespaceAndPath("some_mod", "broken_job"))));
		assertFalse(RigTarget.isNonTradeable(VillagerProfession.LIBRARIAN));
		// a namespaceless entry means minecraft:
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("cleric");
		assertTrue(RigTarget.isNonTradeable(VillagerProfession.CLERIC));
	}

	@Test
	void aListedProfessionIsUnusableLikeANitwit() {
		c().professionId = "minecraft:librarian";
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("minecraft:cleric");
		assertTrue(RigTarget.isUnusable(McTest.profession(VillagerProfession.CLERIC)));
		assertFalse(RigTarget.isUnusable(McTest.profession(VillagerProfession.LIBRARIAN)));
	}

	@Test
	void aNonTradeableTargetDegradesToTheLibrarian() {
		c().nonTradeableProfessions.clear();
		// a nitwit can never take a job, so targeting it would make every villager unusable
		c().professionId = "minecraft:nitwit";
		assertEquals(VillagerProfession.LIBRARIAN, RigTarget.professionKey());
		assertTrue(RigTarget.professionConfiguredNonTradeable());

		c().nonTradeableProfessions.add("minecraft:cleric");
		c().professionId = "minecraft:cleric";
		assertEquals(VillagerProfession.LIBRARIAN, RigTarget.professionKey());
		assertTrue(RigTarget.professionConfiguredNonTradeable());

		// a plain tradeable profession is used as configured
		c().professionId = "minecraft:farmer";
		assertEquals(VillagerProfession.FARMER, RigTarget.professionKey());
		assertFalse(RigTarget.professionConfiguredNonTradeable());
	}
}
