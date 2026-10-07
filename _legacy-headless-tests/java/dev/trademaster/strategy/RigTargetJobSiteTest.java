package dev.trademaster.strategy;

import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.testutil.McTest;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The start-time job-site validation behind the workblock_invalid warning. */
class RigTargetJobSiteTest {

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

	@Test
	void realJobSiteBlocksPass() {
		assertTrue(RigTarget.isJobSiteBlock(Blocks.LECTERN));
		assertTrue(RigTarget.isJobSiteBlock(Blocks.COMPOSTER));
		assertTrue(RigTarget.isJobSiteBlock(Blocks.BLAST_FURNACE));
		assertTrue(RigTarget.isJobSiteBlock(Blocks.SMITHING_TABLE));
		assertTrue(RigTarget.isJobSiteBlock(Blocks.CARTOGRAPHY_TABLE));
		assertTrue(RigTarget.isJobSiteBlock(Blocks.BARREL));
	}

	@Test
	void plainBlocksFail() {
		assertFalse(RigTarget.isJobSiteBlock(Blocks.STONE));
		assertFalse(RigTarget.isJobSiteBlock(Blocks.DIRT));
		assertFalse(RigTarget.isJobSiteBlock(Blocks.CRAFTING_TABLE));
		assertFalse(RigTarget.isJobSiteBlock(Blocks.OAK_LOG));
	}

	@Test
	void airAndNullFail() {
		assertFalse(RigTarget.isJobSiteBlock(Blocks.AIR));
		assertFalse(RigTarget.isJobSiteBlock(null));
	}

	@Test
	void validationFollowsTheConfiguredWorkBlock() {
		// composter + farmer: a valid rig combo
		TradeMasterConfig.INSTANCE.workBlockId = "minecraft:composter";
		assertTrue(RigTarget.isJobSiteBlock(RigTarget.workBlock()));
		// stone: configured as the work block but not a job site
		TradeMasterConfig.INSTANCE.workBlockId = "minecraft:stone";
		assertFalse(RigTarget.isJobSiteBlock(RigTarget.workBlock()));
	}
}
