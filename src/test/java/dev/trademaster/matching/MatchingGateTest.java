package dev.trademaster.matching;

import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.testutil.McTest;
import com.google.gson.JsonObject;
import dev.trademaster.compat.McIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
//? if >=1.21 {
import net.minecraft.world.item.trading.ItemCost;
//?}
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "can this ever be offered" gates that keep an unmatchable target/good out of the
 * needs table (otherwise the run would reroll forever for it). */
class MatchingGateTest {

	private static final ResourceLocation MENDING = McIds.mc("mending");
	private static final ResourceLocation AIR = TradeOfferSpec.AIR;

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

	private void spec(ResourceLocation id, List<TradeOfferSpec.TradeCost> costs) {
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
				McIds.mc("definitely_not_an_item"), 1)));
		assertFalse(TradeMatcher.targetHasPayableCost(target()));
	}

	@Test
	void goodNeedsAKnownNonAirResultSomePriceAndAPayableSlot() {
		assertTrue(TradeMatcher.goodMatchable(new GoodSpec(
				McIds.mc("composter"), 1, true, 10,
				TradeOfferSpec.defaultGoodCosts())));

		// price cap below one emerald
		assertFalse(TradeMatcher.goodMatchable(new GoodSpec(
				McIds.mc("composter"), 1, true, 0,
				TradeOfferSpec.defaultGoodCosts())));

		// the result item must exist
		assertFalse(TradeMatcher.goodMatchable(new GoodSpec(
				McIds.mc("definitely_not_an_item"), 1, true, 10,
				TradeOfferSpec.defaultGoodCosts())));

		// costs that declare "pays nothing"
		assertFalse(TradeMatcher.goodMatchable(new GoodSpec(
				McIds.mc("composter"), 1, true, 10,
				List.of(new TradeOfferSpec.TradeCost(AIR, 0), new TradeOfferSpec.TradeCost(AIR, -1)))));
	}

	@Test
	void aGoodSharingAnyTargetIdIsRejected() {
		ResourceLocation composter = McIds.mc("composter");
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

	/** A real offer whose charged price (base 10, special diff -4) differs from its base. */
	private static MerchantOffer pricedOffer(int base) {
		//? if >=26.1 {
		/*// 26.1+ binds item components from a datapack initializer a headless test does
		// not run: bind the minimal set these two items need
		net.minecraft.core.component.DataComponentMap map =
				net.minecraft.core.component.DataComponentMap.builder()
						.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build();
		Items.EMERALD.builtInRegistryHolder().bindComponents(map);
		Items.ENCHANTED_BOOK.builtInRegistryHolder().bindComponents(map);
		*///?}
		//? if >=1.21 {
		MerchantOffer offer = new MerchantOffer(new ItemCost(Items.EMERALD, base),
				new ItemStack(Items.ENCHANTED_BOOK), 12, 2, 0.05f);
		//?} else {
		/*MerchantOffer offer = new MerchantOffer(new ItemStack(Items.EMERALD, base),
				new ItemStack(Items.ENCHANTED_BOOK), 12, 2, 0.05f);*/
		//?}
		offer.setSpecialPriceDiff(-4);
		return offer;
	}

	@Test
	void priceBasisOptionSelectsBaseOrChargedCost() {
		MerchantOffer offer = pricedOffer(10);
		c().discountedPriceAsSalePrice = false;
		assertEquals(10, TradeMatcher.offerPrice(offer));
		assertEquals(10, OfferCosts.pricingSlots(offer).get(0).getCount());

		c().discountedPriceAsSalePrice = true;
		assertEquals(6, TradeMatcher.offerPrice(offer));
		assertEquals(6, OfferCosts.pricingSlots(offer).get(0).getCount());
		// IDENTITY slots never follow the option: fingerprints and channel comparison
		// must keep seeing the base cost while demand moves the charged one
		assertEquals(10, OfferCosts.slots(offer).get(0).getCount());
	}

	@Test
	void priceReachabilityUsesTheChargedPriceWhenTheOptionIsOn() {
		// level 5: vanilla base minimum is 2+3*5 = 17, above this cap
		EnchantmentTarget low = new EnchantmentTarget(MENDING, true, 5, 10);
		c().discountedPriceAsSalePrice = false;
		assertTrue(TradeMatcher.targetPriceUnreachable(low));
		assertTrue(TradeMatcher.minimumOfferPrice(low) > 10);

		// a cure / Hero discount can charge less than the base minimum: the same cap
		// is reachable, so the target must stay in the needs instead of being pruned
		c().discountedPriceAsSalePrice = true;
		assertFalse(TradeMatcher.targetPriceUnreachable(low));
		assertEquals(1, TradeMatcher.minimumOfferPrice(low));
	}
}
