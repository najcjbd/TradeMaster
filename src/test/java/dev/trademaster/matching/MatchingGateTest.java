package dev.trademaster.matching;

import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.testutil.McTest;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "can this ever be offered" gates that keep an unmatchable target/good out of the
 * needs table (otherwise the run would reroll forever for it). */
class MatchingGateTest {

	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");
	private static final Identifier AIR = TradeOfferSpec.AIR;

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

	private void spec(Identifier id, List<TradeOfferSpec.TradeCost> costs) {
		c().tradeSpecs.put(id, new TradeOfferSpec(costs, TradeOfferSpec.ENCHANTED_BOOK));
	}

	private EnchantmentTarget target() {
		return new EnchantmentTarget(MENDING, true, 5, 20);
	}

	@Test
	void defaultSpecHasAPayableSlot() {
		c().tradeSpecs.clear();
		assertTrue(TradeMatcher.targetHasPayableCost(target()));
	}

	@Test
	void allAirOrZeroSlotsAreNotPayable() {
		spec(MENDING, List.of(new TradeOfferSpec.TradeCost(AIR, 0), new TradeOfferSpec.TradeCost(AIR, -1)));
		assertFalse(TradeMatcher.targetHasPayableCost(target()));

		spec(MENDING, List.of(new TradeOfferSpec.TradeCost(AIR, 0), new TradeOfferSpec.TradeCost(
				TradeOfferSpec.BOOK, 0)));
		assertFalse(TradeMatcher.targetHasPayableCost(target()));
	}

	@Test
	void aRealPriceSlotIsPayableEvenWithCountZero() {
		// slot 1's count is ignored: its amount is the target's price
		spec(MENDING, List.of(new TradeOfferSpec.TradeCost(TradeOfferSpec.EMERALD, 0),
				new TradeOfferSpec.TradeCost(TradeOfferSpec.BOOK, 0)));
		assertTrue(TradeMatcher.targetHasPayableCost(target()));
	}

	@Test
	void aSecondSlotWithALimitIsPayableOnItsOwn() {
		spec(MENDING, List.of(new TradeOfferSpec.TradeCost(AIR, 0), new TradeOfferSpec.TradeCost(
				TradeOfferSpec.BOOK, -1)));
		assertTrue(TradeMatcher.targetHasPayableCost(target()));
	}

	@Test
	void anUnknownItemSlotCannotBePaid() {
		spec(MENDING, List.of(new TradeOfferSpec.TradeCost(
				Identifier.withDefaultNamespace("definitely_not_an_item"), 1)));
		assertFalse(TradeMatcher.targetHasPayableCost(target()));
	}

	@Test
	void goodNeedsAKnownNonAirResultSomePriceAndAPayableSlot() {
		assertTrue(TradeMatcher.goodMatchable(new GoodSpec(
				Identifier.withDefaultNamespace("composter"), 1, true, 10,
				TradeOfferSpec.defaultGoodCosts())));

		// price cap below one emerald
		assertFalse(TradeMatcher.goodMatchable(new GoodSpec(
				Identifier.withDefaultNamespace("composter"), 1, true, 0,
				TradeOfferSpec.defaultGoodCosts())));

		// the result item must exist
		assertFalse(TradeMatcher.goodMatchable(new GoodSpec(
				Identifier.withDefaultNamespace("definitely_not_an_item"), 1, true, 10,
				TradeOfferSpec.defaultGoodCosts())));

		// costs that declare "pays nothing"
		assertFalse(TradeMatcher.goodMatchable(new GoodSpec(
				Identifier.withDefaultNamespace("composter"), 1, true, 10,
				List.of(new TradeOfferSpec.TradeCost(AIR, 0), new TradeOfferSpec.TradeCost(AIR, -1)))));
	}

	@Test
	void aGoodSharingAnyTargetIdIsRejected() {
		Identifier composter = Identifier.withDefaultNamespace("composter");
		GoodSpec good = new GoodSpec(composter, 1, true, 10, TradeOfferSpec.defaultGoodCosts());
		c().enchantments.clear();
		// no target with that id: the good is fine
		assertTrue(TradeMatcher.goodMatchable(good));
		// ANY same-id target refuses the good: target-first, and the id-keyed
		// vouchers/marks cannot distinguish the two kinds
		c().enchantments.put(composter, new EnchantmentTarget(composter, true, 1, 20));
		assertFalse(TradeMatcher.goodMatchable(good));
		c().enchantments.put(composter, new EnchantmentTarget(composter, false, 1, 20));
		assertFalse(TradeMatcher.goodMatchable(good));
	}

	@Test
	void goodWithAirResultIsRejected() {
		assertFalse(TradeMatcher.goodMatchable(new GoodSpec(AIR, 1, true, 10,
				TradeOfferSpec.defaultGoodCosts())));
	}
}
