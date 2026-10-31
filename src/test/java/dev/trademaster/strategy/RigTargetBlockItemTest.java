package dev.trademaster.strategy;

import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.testutil.McTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Block/item resolution plus the unknown-id fallback that keeps the break/place loop
 * from wedging on a bad config. */
class RigTargetBlockItemTest {

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
	void defaultIsTheLectern() {
		c().workBlockId = "minecraft:lectern";
		assertEquals(Blocks.LECTERN, RigTarget.workBlock());
		assertEquals(Items.LECTERN, RigTarget.workItem());
	}

	@Test
	void namespacelessAndOtherBlocksResolve() {
		c().workBlockId = "composter";
		assertEquals(Blocks.COMPOSTER, RigTarget.workBlock());
		assertEquals(Items.COMPOSTER, RigTarget.workItem());

		c().workBlockId = "minecraft:barrel";
		assertEquals(Blocks.BARREL, RigTarget.workBlock());
		assertEquals(Items.BARREL, RigTarget.workItem());
	}

	@Test
	void unknownOrAirIdFallsBackToTheLectern() {
		c().workBlockId = "unknownmod:nope";
		assertEquals(Blocks.LECTERN, RigTarget.workBlock());
		c().workBlockId = "minecraft:air";
		assertEquals(Blocks.LECTERN, RigTarget.workBlock());
		c().workBlockId = "";
		assertEquals(Blocks.LECTERN, RigTarget.workBlock());
		c().workBlockId = "!!!";
		assertEquals(Blocks.LECTERN, RigTarget.workBlock());
	}

	@Test
	void blockWithoutAnItemHasNoWorkItem() {
		// water resolves as a block but has no item: workItem() must be null, not air
		c().workBlockId = "minecraft:water";
		assertEquals(Blocks.WATER, RigTarget.workBlock());
		assertNull(RigTarget.workItem());
	}

	@Test
	void isWorkBlockAtAndStateAtOnAnEmptyLevel() {
		c().workBlockId = "minecraft:lectern";
		// EmptyBlockGetter reports air everywhere
		assertFalse(RigTarget.isWorkBlockAt(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
		// and stateAt falls back to the configured block's default state
		assertTrue(RigTarget.stateAt(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).is(Blocks.LECTERN));
	}
}
