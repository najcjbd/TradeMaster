package dev.trademaster.strategy;

import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McIds;
import dev.trademaster.compat.McRegistry;
import dev.trademaster.compat.McVillager;
import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.testutil.McTest;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.VillagerProfession;
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

	private static ResourceKey<VillagerProfession> key(String id) {
		return ResourceKey.create(McBuiltIn.villagerProfessionKey(), McIds.tryParse(id));
	}

	@Test
	void defaultKeyIsLibrarianAndKnown() {
		c().professionId = "minecraft:librarian";
		assertEquals(McVillager.librarian(), RigTarget.professionKey());
		assertTrue(RigTarget.professionKnown());
	}

	@Test
	void namespacelessResolves() {
		c().professionId = "farmer";
		assertEquals(key("minecraft:farmer"), RigTarget.professionKey());
		assertTrue(RigTarget.professionKnown());
	}

	@Test
	void blankFallsBackToLibrarian() {
		c().professionId = "";
		assertEquals(McVillager.librarian(), RigTarget.professionKey());
		c().professionId = "!!!";
		assertEquals(McVillager.librarian(), RigTarget.professionKey());
	}

	@Test
	void syntacticallyValidButUnknownIdIsReportedUnknown() {
		c().professionId = "unknownmod:weird_job";
		ResourceKey<VillagerProfession> key = RigTarget.professionKey();
		assertEquals("unknownmod", McRegistry.keyId(key).getNamespace());
		assertFalse(RigTarget.professionKnown());
	}

	@Test
	void isTargetMatchesTheConfiguredProfession() {
		c().professionId = "minecraft:librarian";
		assertTrue(RigTarget.isTarget(McTest.profession(key("minecraft:librarian"))));
		assertFalse(RigTarget.isTarget(McTest.profession(key("minecraft:farmer"))));
	}

	@Test
	void unusableRuleUnderDefaultLibrarian() {
		c().professionId = "minecraft:librarian";
		// a nitwit never works
		assertTrue(RigTarget.isUnusable(McTest.profession(key("minecraft:nitwit"))));
		// unemployed is usable: it can still take the job
		assertFalse(RigTarget.isUnusable(McTest.profession(key("minecraft:none"))));
		// already the target profession
		assertFalse(RigTarget.isUnusable(McTest.profession(key("minecraft:librarian"))));
		// employed elsewhere
		assertTrue(RigTarget.isUnusable(McTest.profession(key("minecraft:farmer"))));
	}

	@Test
	void unusableRuleFollowsTheConfiguredProfession() {
		c().professionId = "minecraft:farmer";
		assertFalse(RigTarget.isUnusable(McTest.profession(key("minecraft:farmer"))));
		assertTrue(RigTarget.isUnusable(McTest.profession(key("minecraft:librarian"))));
	}

	@Test
	void nitwitIsNonTradeableButUnemployedIsNot() {
		c().nonTradeableProfessions.clear();
		assertTrue(RigTarget.isNonTradeable(key("minecraft:nitwit")));
		// unemployment can still take the configured job, so it stays usable
		assertFalse(RigTarget.isNonTradeable(key("minecraft:none")));
		assertFalse(RigTarget.isNonTradeable(key("minecraft:librarian")));
		assertFalse(RigTarget.isNonTradeable(key("minecraft:farmer")));
	}

	@Test
	void configuredNonTradeableListIsHonoured() {
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("minecraft:cleric");
		c().nonTradeableProfessions.add("some_mod:broken_job");
		assertTrue(RigTarget.isNonTradeable(key("minecraft:cleric")));
		assertTrue(RigTarget.isNonTradeable(key("some_mod:broken_job")));
		assertFalse(RigTarget.isNonTradeable(key("minecraft:librarian")));
		// a namespaceless entry means minecraft:
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("cleric");
		assertTrue(RigTarget.isNonTradeable(key("minecraft:cleric")));
	}

	@Test
	void aListedProfessionIsUnusableLikeANitwit() {
		c().professionId = "minecraft:librarian";
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("minecraft:cleric");
		assertTrue(RigTarget.isUnusable(McTest.profession(key("minecraft:cleric"))));
		assertFalse(RigTarget.isUnusable(McTest.profession(key("minecraft:librarian"))));
	}

	@Test
	void unemploymentIsNeverNonTradeable() {
		c().nonTradeableProfessions.clear();
		// listing "none" must not make the candidate the rig needs unusable
		c().nonTradeableProfessions.add("minecraft:none");
		assertFalse(RigTarget.isNonTradeable(key("minecraft:none")));
		assertFalse(RigTarget.isUnusable(McTest.profession(key("minecraft:none"))));
	}

	@Test
	void theEffectiveTargetStaysUsableEvenWhenItsIdIsListed() {
		c().professionId = "minecraft:librarian";
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("minecraft:librarian");
		// professionKey() degrades the listed target to the librarian, so the villager the rig
		// wants must not be treated as a blocker (that contradiction could never lock)
		assertEquals(McVillager.librarian(), RigTarget.professionKey());
		assertFalse(RigTarget.isUnusable(McTest.profession(key("minecraft:librarian"))));
		// another listed profession is still unusable
		c().nonTradeableProfessions.add("minecraft:cleric");
		assertTrue(RigTarget.isUnusable(McTest.profession(key("minecraft:cleric"))));
	}

	@Test
	void aNonTradeableTargetDegradesToTheLibrarian() {
		c().nonTradeableProfessions.clear();
		// a nitwit can never take a job, so targeting it would make every villager unusable
		c().professionId = "minecraft:nitwit";
		assertEquals(McVillager.librarian(), RigTarget.professionKey());
		assertTrue(RigTarget.professionConfiguredNonTradeable());

		c().nonTradeableProfessions.add("minecraft:cleric");
		c().professionId = "minecraft:cleric";
		assertEquals(McVillager.librarian(), RigTarget.professionKey());
		assertTrue(RigTarget.professionConfiguredNonTradeable());

		// a plain tradeable profession is used as configured
		c().professionId = "minecraft:farmer";
		assertEquals(key("minecraft:farmer"), RigTarget.professionKey());
		assertFalse(RigTarget.professionConfiguredNonTradeable());
	}
}
