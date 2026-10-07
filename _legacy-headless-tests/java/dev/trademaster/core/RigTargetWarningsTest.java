package dev.trademaster.core;

import com.google.gson.JsonObject;
import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.matching.TradeMatcher;
import dev.trademaster.strategy.RigTarget;
import dev.trademaster.testutil.McTest;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start-time rig-target warnings. The conditions run headless; the check is that
 * {@link RerollController#rigTargetWarnings()} produces the right messages in the right
 * cases — exactly the wiring that was silently broken when {@code professionKnown()} never
 * reported an unknown profession.
 */
class RigTargetWarningsTest {

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

	private static boolean has(List<Component> warnings, String key) {
		return warnings.stream().anyMatch(w -> w.getString().contains(key));
	}

	@Test
	void aHealthyRigTargetHasNoWarnings() {
		c().workBlockId = "minecraft:lectern";
		c().professionId = "minecraft:librarian";
		c().enchantments.clear();
		assertTrue(RerollController.rigTargetWarnings().isEmpty());
	}

	@Test
	void aNonJobSiteWorkBlockIsReported() {
		c().workBlockId = "minecraft:stone";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.workblock_invalid"),
				"expected a workblock_invalid warning, got " + warnings);
	}

	@Test
	void anUnknownWorkBlockIdIsReported() {
		c().workBlockId = "unknownmod:nope";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.workblock_unknown"),
				"expected a workblock_unknown warning, got " + warnings);
	}

	@Test
	void anAirWorkBlockIsReportedAsUnknown() {
		c().workBlockId = "minecraft:air";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.workblock_unknown"),
				"expected a workblock_unknown warning, got " + warnings);
	}

	@Test
	void anUnknownProfessionIsReported() {
		c().workBlockId = "minecraft:lectern";
		c().professionId = "unknownmod:weird_job";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.profession_invalid"),
				"expected a profession_invalid warning, got " + warnings);
	}

	@Test
	void aNonLibrarianProfessionWithEnabledTargetsWarnsAboutTheGrammar() {
		c().workBlockId = "minecraft:composter";
		c().professionId = "minecraft:farmer";
		c().enchantments.clear();
		Identifier mending = Identifier.withDefaultNamespace("mending");
		c().enchantments.put(mending, new EnchantmentTarget(mending, true, 1, 20));

		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.profession_not_book"),
				"expected a profession_not_book warning, got " + warnings);
		// the composter/farmer rig itself is valid
		assertFalse(has(warnings, "trademaster.message.workblock_invalid"));
	}

	@Test
	void aNonLibrarianProfessionWithNoEnabledTargetsDoesNotWarn() {
		c().workBlockId = "minecraft:composter";
		c().professionId = "minecraft:farmer";
		c().enchantments.clear();
		assertTrue(RerollController.rigTargetWarnings().isEmpty());
	}

	@Test
	void workBlockAndProfessionProblemsAreReportedTogether() {
		c().workBlockId = "minecraft:stone";
		c().professionId = "unknownmod:nope";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertEquals(2, warnings.size(), "expected both warnings, got " + warnings);
		assertTrue(has(warnings, "trademaster.message.workblock_invalid"));
		assertTrue(has(warnings, "trademaster.message.profession_invalid"));
	}

	@Test
	void aWorkBlockThatIsNotThatProfessionsJobSiteIsReported() {
		// a composter IS a job site (for a farmer), just never for a librarian: without this
		// warning the run would wait for a profession that can never happen
		c().workBlockId = "minecraft:composter";
		c().professionId = "minecraft:librarian";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertEquals(List.of("trademaster.message.workblock_not_for_profession"),
				warningKeys(warnings), "got " + warnings);

		// the unemployed target needs no job site at all, so it must stay quiet
		c().professionId = "minecraft:none";
		assertFalse(RerollController.rigTargetWarnings().stream()
				.anyMatch(w -> w.getString().contains("workblock_not_for_profession")));

		// an UNKNOWN profession reports just its own problem, not a bogus pairing one
		c().professionId = "unknownmod:nope";
		List<Component> unknown = RerollController.rigTargetWarnings();
		assertEquals(1, unknown.size(), "got " + unknown);
		assertTrue(has(unknown, "trademaster.message.profession_invalid"));
	}

	@Test
	void anUnknownProfessionIsNotAlsoWarnedAboutTheBookGrammar() {
		c().workBlockId = "minecraft:lectern";
		c().professionId = "unknownmod:weird_job";
		c().enchantments.clear();
		Identifier mending = Identifier.withDefaultNamespace("mending");
		c().enchantments.put(mending, new EnchantmentTarget(mending, true, 1, 20));
		List<Component> warnings = RerollController.rigTargetWarnings();
		// the id is unknown: only that is actionable, the goods hint would be noise
		assertEquals(1, warnings.size(), "got " + warnings);
		assertTrue(has(warnings, "trademaster.message.profession_invalid"));
	}

	@Test
	void anUnemployedTargetIsReported() {
		c().workBlockId = "minecraft:lectern";
		c().professionId = "minecraft:none";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.profession_unemployed"), "got " + warnings);
	}

	@Test
	void aBlankProfessionIdIsSilent() {
		c().workBlockId = "minecraft:lectern";
		c().professionId = "   ";
		c().enchantments.clear();
		// blank means "use the librarian" (the documented default): nothing to warn about
		assertTrue(RerollController.rigTargetWarnings().isEmpty(),
				"got " + RerollController.rigTargetWarnings());
	}

	@Test
	void aNonTradeableTargetIsReportedAndDegradesToTheLibrarian() {
		c().workBlockId = "minecraft:lectern";
		c().nonTradeableProfessions.clear();
		c().professionId = "minecraft:nitwit";
		c().enchantments.clear();
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.profession_not_tradeable"), "got " + warnings);
		assertEquals(VillagerProfession.LIBRARIAN, RigTarget.professionKey());
	}

	@Test
	void aBlankPriceSlotIsDetected() {
		// slot 0 declares no item: only a trade charging nothing there can match
		assertTrue(RerollController.blankPriceSlot(new TradeOfferSpec(
				List.of(new TradeOfferSpec.TradeCost(TradeOfferSpec.AIR, 0)), TradeOfferSpec.ENCHANTED_BOOK)));
		assertTrue(RerollController.blankPriceSlot(new TradeOfferSpec(
				List.of(new TradeOfferSpec.TradeCost(null, 0)), TradeOfferSpec.ENCHANTED_BOOK)));
		assertTrue(RerollController.blankPriceSlot(new TradeOfferSpec(
				List.of(), TradeOfferSpec.ENCHANTED_BOOK)));
		// a real price item is fine
		assertFalse(RerollController.blankPriceSlot(new TradeOfferSpec(
				List.of(new TradeOfferSpec.TradeCost(TradeOfferSpec.EMERALD, 0)), TradeOfferSpec.ENCHANTED_BOOK)));
	}

	@Test
	void theUnemployedTargetIsNotAlsoWarnedAsNotBook() {
		c().workBlockId = "minecraft:lectern";
		c().nonTradeableProfessions.clear();
		c().professionId = "minecraft:none";
		Identifier mending = Identifier.withDefaultNamespace("mending");
		c().enchantments.clear();
		c().enchantments.put(mending, new EnchantmentTarget(mending, true, 1, 20));
		List<Component> warnings = RerollController.rigTargetWarnings();
		assertTrue(has(warnings, "trademaster.message.profession_unemployed"), "got " + warnings);
		// unemployed sells nothing at all, so the "use the goods table" hint must not fire
		assertFalse(has(warnings, "trademaster.message.profession_not_book"), "got " + warningKeys(warnings));
	}

	@Test
	void anEmergencyBookVouchersOnlyTheTargetsItFulfils() {
		Identifier mending = Identifier.withDefaultNamespace("mending");
		Identifier silk = Identifier.withDefaultNamespace("silk_touch");
		c().enchantments.clear();
		c().enchantments.put(mending, new EnchantmentTarget(mending, true, 1, 20));
		c().enchantments.put(silk, new EnchantmentTarget(silk, true, 2, 20));

		// a bought book carrying mending I + silk I: mending (needs 1) is fulfilled, silk
		// (needs 2) is NOT — vouching silk here would mask the genuinely-needed level 2
		assertEquals(List.of(mending), RerollController.fulfilledTargetIds(book(List.of(
				new TradeMatcher.MatchPart(mending, 1), new TradeMatcher.MatchPart(silk, 1)))));
		// at level 2 both are fulfilled
		assertEquals(List.of(mending, silk), RerollController.fulfilledTargetIds(book(List.of(
				new TradeMatcher.MatchPart(mending, 1), new TradeMatcher.MatchPart(silk, 2)))));
		// a disabled target is never vouched
		c().enchantments.put(mending, new EnchantmentTarget(mending, false, 1, 20));
		assertEquals(List.of(silk), RerollController.fulfilledTargetIds(book(List.of(
				new TradeMatcher.MatchPart(mending, 1), new TradeMatcher.MatchPart(silk, 2)))));
	}

	private static TradeMatcher.MatchedTrade book(List<TradeMatcher.MatchPart> parts) {
		TradeMatcher.MatchedBook b = new TradeMatcher.MatchedBook(parts, parts, 5, 0, List.of(), ItemStack.EMPTY);
		return new TradeMatcher.MatchedTrade(List.of(b), List.of(), 0);
	}

	/** The warning keys in order, so a test can assert the exact set. */
	private static List<String> warningKeys(List<Component> warnings) {
		return warnings.stream().map(w -> {
			var contents = w.getContents();
			return contents instanceof net.minecraft.network.chat.contents.TranslatableContents t
					? t.getKey() : "<literal>";
		}).toList();
	}
}
