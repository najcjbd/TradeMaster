package dev.trademaster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-target smoke test. Runs on EVERY Stonecutter node's {@code test} task, so a
 * version that loses a core class fails at build time instead of only in-game.
 * Version-specific probes (components vs NBT, the enchantment registry, trading) are
 * added here with {@code //? if <version>} gating as subsystems migrate.
 */
class VersionProbeTest {

	@Test
	void coreMinecraftClassesAreOnTheTestClasspath() {
		// classes present in every supported version (1.16.5 -> 26.3)
		assertNotNull(net.minecraft.SharedConstants.class);
		assertNotNull(net.minecraft.world.item.ItemStack.class);
	}

	@Test
	void theResourceLocationClassResolvesOnThisVersion() {
		// the global ResourceLocation -> Identifier replacement must produce a live class
		assertNotNull(dev.trademaster.probe.IdProbe.idClass());
	}

	@Test
	void offerCostSlotsUseTheTypeMcTradeExpects() {
		// catches the ItemCost boundary drifting: on 1.21+ the cost fields are ItemCost,
		// before that ItemStack. With a wrong gate no declared field is recognised.
		boolean recognised = java.util.Arrays.stream(
						net.minecraft.world.item.trading.MerchantOffer.class.getDeclaredFields())
				.map(java.lang.reflect.Field::getType)
				.anyMatch(dev.trademaster.compat.McTrade::isCostType);
		assertTrue(recognised, "no MerchantOffer field matches McTrade's cost type");
	}

	@Test
	void enchantmentTagKeysExistOnThisVersion() {
		// 1.21+ reads the vanilla EnchantmentTags constants; earlier versions build the
		// keys by hand. Either branch must yield a usable key. Before 1.21 the key is
		// created against Registry.ENCHANTMENT_REGISTRY, whose class needs Bootstrap.
		dev.trademaster.testutil.McTest.boot();
		assertNotNull(dev.trademaster.compat.McEnchantTags.tradeable());
		assertNotNull(dev.trademaster.compat.McEnchantTags.doubleTradePrice());
	}

	@Test
	void offerCostSlotsExcludeTheTradeResultOnOldVersions() {
		//? if <1.21 {
		/*// before 1.21 both cost fields and the result are ItemStacks: type-based slot
		// detection must not count the result as a third cost
		dev.trademaster.testutil.McTest.boot();
		var offer = new net.minecraft.world.item.trading.MerchantOffer(
				new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 5),
				new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK), 12, 2, 0.05f);
		assertEquals(2, dev.trademaster.matching.OfferCosts.slots(offer).size(),
				"the trade result must not be counted as a cost slot");*/
		//?} else {
		// 1.21+ costs are ItemCost, so the reflective scan cannot include the result —
		// prove that on a real offer instead of only asserting the class exists
		dev.trademaster.testutil.McTest.boot();
		//? if >=26.1 {
		/*// 26.1+ binds item components from a datapack initializer a headless test does
		// not run: bind the minimal set the ItemCost and the result stack need
		net.minecraft.core.component.DataComponentMap map =
				net.minecraft.core.component.DataComponentMap.builder()
						.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build();
		net.minecraft.world.item.Items.EMERALD.builtInRegistryHolder().bindComponents(map);
		net.minecraft.world.item.Items.ENCHANTED_BOOK.builtInRegistryHolder().bindComponents(map);
		*///?}
		var offer = new net.minecraft.world.item.trading.MerchantOffer(
				new net.minecraft.world.item.trading.ItemCost(net.minecraft.world.item.Items.EMERALD, 5),
				new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ENCHANTED_BOOK), 12, 2, 0.05f);
		assertEquals(2, dev.trademaster.matching.OfferCosts.slots(offer).size(),
				"the trade result must not be counted as a cost slot");
		//?}
	}

	@Test
	void playerBlockRangeUsesAnAccessorThatExistsOnThisVersion() throws Exception {
		//? if >=26.1 {
		/*assertNotNull(net.minecraft.world.entity.player.Player.class.getMethod(
				"isWithinBlockInteractionRange", net.minecraft.core.BlockPos.class, double.class));*/
		//?} elif >=1.21 {
		assertNotNull(net.minecraft.world.entity.player.Player.class.getMethod(
				"canInteractWithBlock", net.minecraft.core.BlockPos.class, double.class));
		//?} else {
		/*assertNotNull(net.minecraft.world.entity.player.Player.class.getMethod("getEyePosition"));*/
		//?}
	}
}
