package dev.trademaster.strategy;

import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.testutil.McTest;
import com.google.gson.JsonObject;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derived best-tool algorithm: grading, the deterministic precheck, the lock, and the
 * tie-break order. The pool-based overloads are the same code the runtime uses, only with
 * the carried stacks supplied directly.
 */
class RigTargetToolTest {

	private static final BlockState LECTERN = Blocks.LECTERN.defaultBlockState();
	private static final RandomSource RNG = RandomSource.create(42L);

	private JsonObject snapshot;

	@BeforeAll
	static void boot() {
		McTest.boot();
	}

	@BeforeEach
	void snap() {
		snapshot = ConfigTest.snapshot();
		RigTarget.clearToolLock();
	}

	@AfterEach
	void restore() {
		RigTarget.clearToolLock();
		ConfigTest.restore(snapshot);
	}

	private TradeMasterConfig c() {
		return TradeMasterConfig.INSTANCE;
	}

	@Test
	void nearlyBrokenThreshold() {
		assertTrue(RigTarget.isNearlyBroken(McTest.tool(Items.DIAMOND_AXE, 8, 100, 95)));
		assertFalse(RigTarget.isNearlyBroken(McTest.tool(Items.DIAMOND_PICKAXE, 8, 100, 94)));
		assertFalse(RigTarget.isNearlyBroken(McTest.plain(Items.STICK)));
	}

	@Test
	void remainingDurabilityAndUnbreaking() {
		assertEquals(100, RigTarget.remainingDurability(McTest.tool(Items.DIAMOND_SHOVEL, 8, 100, 0)));
		assertEquals(4, RigTarget.remainingDurability(McTest.tool(Items.DIAMOND_HOE, 8, 100, 96)));
		assertEquals(0, RigTarget.remainingDurability(McTest.plain(Items.PAPER)));

		assertEquals(3, RigTarget.unbreakingLevel(McTest.tool(Items.DIAMOND_SWORD, 8, 100, 0, 3)));
		assertEquals(0, RigTarget.unbreakingLevel(McTest.tool(Items.STONE_AXE, 8, 100, 0)));
	}

	@Test
	void bestToolIsDeterministicAndPicksHighestSpeed() {
		ItemStack axe = McTest.tool(Items.IRON_AXE, 8, 100, 0);
		ItemStack gold = McTest.tool(Items.GOLDEN_AXE, 12, 100, 0);
		List<ItemStack> pool = List.of(axe, gold);
		for (int i = 0; i < 20; i++) {
			assertSame(Items.GOLDEN_AXE, RigTarget.bestToolItem(LECTERN, pool));
		}
	}

	@Test
	void bestToolIsTheFirstOfEqualSpeedCandidates() {
		// the precheck only compares speed, so equal-speed tools resolve to the first one
		// (inventory order) — deterministic, no tie-break RNG involved
		ItemStack axe = McTest.tool(Items.IRON_AXE, 8, 100, 0);
		ItemStack pick = McTest.tool(Items.IRON_PICKAXE, 8, 100, 0);
		List<ItemStack> pool = List.of(axe, pick);
		for (int i = 0; i < 20; i++) {
			assertSame(Items.IRON_AXE, RigTarget.bestToolItem(LECTERN, pool));
		}
	}

	@Test
	void noToolWhenNothingBeatsTheHand() {
		assertNull(RigTarget.bestToolItem(LECTERN, List.of()));
		assertNull(RigTarget.bestToolItem(LECTERN,
				List.of(McTest.plain(Items.STICK), McTest.plain(Items.PAPER))));
	}

	@Test
	void guardPrefersHealthyOverFasterNearlyBroken() {
		ItemStack nearlyBrokenGold = McTest.tool(Items.GOLDEN_AXE, 12, 100, 96);
		ItemStack healthyIron = McTest.tool(Items.IRON_AXE, 8, 100, 0);
		List<ItemStack> pool = List.of(nearlyBrokenGold, healthyIron);

		c().preventAxeBreaking = true;
		assertSame(Items.IRON_AXE, RigTarget.bestToolItem(LECTERN, pool));

		// with the guard off the faster (nearly broken) tool wins
		c().preventAxeBreaking = false;
		assertSame(Items.GOLDEN_AXE, RigTarget.bestToolItem(LECTERN, pool));
	}

	@Test
	void guardFallsBackWhenEverythingIsNearlyBroken() {
		ItemStack brokenGold = McTest.tool(Items.GOLDEN_AXE, 12, 100, 98);
		ItemStack brokenIron = McTest.tool(Items.IRON_AXE, 8, 100, 98);
		c().preventAxeBreaking = true;
		assertSame(Items.GOLDEN_AXE, RigTarget.bestToolItem(LECTERN, List.of(brokenGold, brokenIron)));
	}

	@Test
	void tieBreakUsesRemainingDurabilityFirst() {
		c().preventAxeBreaking = false;
		ItemStack worn = McTest.tool(Items.IRON_AXE, 8, 100, 90);
		ItemStack fresh = McTest.tool(Items.IRON_PICKAXE, 8, 100, 0);
		// the tie-break (durability/unbreaking) applies to the chosen tool, not the precheck
		assertSame(Items.IRON_PICKAXE, RigTarget.chooseTool(LECTERN, List.of(worn, fresh), RNG));
	}

	@Test
	void tieBreakPrefersUnbreakingWhenDurabilityTies() {
		c().preventAxeBreaking = false;
		ItemStack plain = McTest.tool(Items.STONE_AXE, 8, 100, 0, 0);
		ItemStack enchanted = McTest.tool(Items.STONE_PICKAXE, 8, 100, 0, 3);
		assertSame(Items.STONE_PICKAXE, RigTarget.chooseTool(LECTERN, List.of(plain, enchanted), RNG));
	}

	@Test
	void remainingDurabilityOutranksUnbreaking() {
		c().preventAxeBreaking = false;
		// the fresher tool wins even though the other has Unbreaking
		ItemStack fresher = McTest.tool(Items.WOODEN_AXE, 8, 100, 0, 0);
		ItemStack enchantedButWorn = McTest.tool(Items.WOODEN_PICKAXE, 8, 100, 50, 3);
		assertSame(Items.WOODEN_AXE, RigTarget.chooseTool(LECTERN, List.of(fresher, enchantedButWorn), RNG));
	}

	@Test
	void chooseToolLocksUntilCleared() {
		c().preventAxeBreaking = false;
		ItemStack a = McTest.tool(Items.IRON_AXE, 8, 100, 0);
		ItemStack b = McTest.tool(Items.IRON_PICKAXE, 8, 100, 0);

		Item locked = RigTarget.chooseTool(LECTERN, List.of(a, b), RNG);
		assertTrue(locked == Items.IRON_AXE || locked == Items.IRON_PICKAXE);
		ItemStack lockedStack = locked == Items.IRON_AXE ? a : b;

		// a strictly better tool appears, but the lock keeps the chosen one
		ItemStack fast = McTest.tool(Items.NETHERITE_PICKAXE, 20, 100, 0);
		List<ItemStack> pool2 = List.of(lockedStack, fast);
		assertSame(locked, RigTarget.chooseTool(LECTERN, pool2, RNG));

		// clearing the lock lets the better tool be picked
		RigTarget.clearToolLock();
		assertSame(Items.NETHERITE_PICKAXE, RigTarget.chooseTool(LECTERN, pool2, RNG));
	}

	@Test
	void chooseToolRepicksWhenTheLockedItemIsGone() {
		c().preventAxeBreaking = false;
		ItemStack a = McTest.tool(Items.IRON_AXE, 8, 100, 0);
		ItemStack b = McTest.tool(Items.IRON_PICKAXE, 8, 100, 0);

		assertSame(Items.IRON_AXE, RigTarget.chooseTool(LECTERN, List.of(a), RNG));
		// the locked tool has left the pool: the next option is used
		assertSame(Items.IRON_PICKAXE, RigTarget.chooseTool(LECTERN, List.of(b), RNG));
	}

	@Test
	void toolWordingFollowsWhetherAToolExists() {
		ItemStack tool = McTest.tool(Items.IRON_AXE, 8, 100, 0);
		assertEquals("trademaster.message.tool_required", RigTarget.toolRequiredKey(LECTERN, List.of(tool)));
		assertEquals("trademaster.message.tool_breaking", RigTarget.toolBreakingKey(LECTERN, List.of(tool)));

		List<ItemStack> noTool = List.of(McTest.plain(Items.STICK));
		assertEquals("trademaster.message.axe_required", RigTarget.toolRequiredKey(LECTERN, noTool));
		assertEquals("trademaster.message.axe_breaking", RigTarget.toolBreakingKey(LECTERN, noTool));
		assertEquals("trademaster.message.axe_required", RigTarget.toolRequiredKey(LECTERN, List.of()));
	}
}
