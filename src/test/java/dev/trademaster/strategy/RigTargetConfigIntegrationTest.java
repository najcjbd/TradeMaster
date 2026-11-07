package dev.trademaster.strategy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McIds;
import dev.trademaster.compat.McVillager;
import dev.trademaster.config.ConfigTest;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.testutil.McTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-feature: a config file drives the work block, the profession, the tool and the
 * dispenser at once. A single-feature test cannot show that these stay coherent — e.g. that
 * changing the work block in the file also releases a tool lock chosen for the old block.
 */
class RigTargetConfigIntegrationTest {

	private static final Object RNG = McTest.random(7L);

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

	private static ResourceKey<VillagerProfession> key(String id) {
		return ResourceKey.create(McBuiltIn.villagerProfessionKey(), McIds.tryParse(id));
	}

	private void read(String json) {
		ConfigTest.read(c(), JsonParser.parseString(json).getAsJsonObject());
	}

	@Test
	void aComposterFarmerRigResolvesEndToEnd() {
		read("""
				{"villagers":{"workBlock":"composter","profession":"farmer"},
				 "positions":{"dispenser":[17,-58,7]},
				 "antiCheat":{"preventAxeBreaking":false}}""");

		assertEquals(Blocks.COMPOSTER, RigTarget.workBlock());
		assertEquals(Items.COMPOSTER, RigTarget.workItem());
		assertEquals(key("minecraft:farmer"), RigTarget.professionKey());
		assertTrue(RigTarget.professionKnown());
		assertTrue(RigTarget.isJobSiteBlock(RigTarget.workBlock()));
		assertEquals(new BlockPos(17, -58, 7), c().dispenserPos);

		// the profession gate follows the config
		assertFalse(RigTarget.isUnusable(McTest.profession(key("minecraft:farmer"))));
		assertTrue(RigTarget.isUnusable(McTest.profession(McVillager.librarian())));

		//? if >=1.20.5 {
		// the tool is DERIVED from the block's pool, not hardcoded to an axe
		ItemStack axe = McTest.tool(Items.IRON_AXE, 6, 100, 0);
		ItemStack hoe = McTest.tool(Items.DIAMOND_HOE, 9, 100, 0);
		assertSame(Items.DIAMOND_HOE, RigTarget.bestToolItem(RigTarget.workBlock().defaultBlockState(),
				List.of(axe, hoe)));
		//?}
	}

	@Test
	void aNonJobSiteWorkBlockFailsValidation() {
		read("{\"villagers\":{\"workBlock\":\"minecraft:stone\",\"profession\":\"farmer\"}}");
		assertEquals(Blocks.STONE, RigTarget.workBlock());
		assertFalse(RigTarget.isJobSiteBlock(RigTarget.workBlock()));
	}

	@Test
	void anUnknownProfessionIsReportedUnknownAndEverythingLooksUnusable() {
		read("{\"villagers\":{\"workBlock\":\"minecraft:lectern\",\"profession\":\"unknownmod:weird_job\"}}");
		assertFalse(RigTarget.professionKnown());
		// nothing matches the configured key, so no villager can ever qualify
		assertFalse(RigTarget.isTarget(McTest.profession(McVillager.librarian())));
		assertTrue(RigTarget.isUnusable(McTest.profession(McVillager.librarian())));
	}

	//? if >=1.20.5 {
	@Test
	void changingTheWorkBlockInTheFileReleasesAToolLock() {
		read("{\"villagers\":{\"workBlock\":\"minecraft:lectern\"},\"antiCheat\":{\"preventAxeBreaking\":false}}");
		ItemStack a = McTest.tool(Items.IRON_AXE, 8, 100, 0);
		ItemStack b = McTest.tool(Items.IRON_PICKAXE, 8, 100, 0);
		RigTarget.chooseTool(RigTarget.workBlock().defaultBlockState(), List.of(a, b), RNG);

		// the file now names a different work block: the lock is scoped to the block, so the
		// next choice may pick the (now faster) tool instead of the stale locked one
		read("{\"villagers\":{\"workBlock\":\"minecraft:composter\"},\"antiCheat\":{\"preventAxeBreaking\":false}}");
		ItemStack fast = McTest.tool(Items.NETHERITE_PICKAXE, 20, 100, 0);
		assertSame(Items.NETHERITE_PICKAXE, RigTarget.chooseTool(RigTarget.workBlock().defaultBlockState(),
				List.of(a, b, fast), RNG));
	}
	//?}

	@Test
	void theModelWritesBackTheVillagersAndPositionsItRead() {
		read("""
				{"villagers":{"workBlock":"minecraft:barrel","profession":"minecraft:fisherman"},
				 "positions":{"dispenser":[1,2,3]}}""");
		JsonObject written = ConfigTest.write(c());
		JsonObject villagers = written.getAsJsonObject("villagers");
		assertEquals("minecraft:barrel", villagers.get("workBlock").getAsString());
		assertEquals("minecraft:fisherman", villagers.get("profession").getAsString());
		assertEquals(3, written.getAsJsonObject("positions").getAsJsonArray("dispenser").get(2).getAsInt());
	}

	@Test
	void unknownProfessionStillRoundTripsSoItIsNotSilentlyLost() {
		read("{\"villagers\":{\"profession\":\"unknownmod:weird_job\"}}");
		JsonObject written = ConfigTest.write(c());
		assertEquals("unknownmod:weird_job",
				written.getAsJsonObject("villagers").get("profession").getAsString());
	}

	@Test
	void airWorkBlockFallsBackSoTheBreakLoopCannotWedge() {
		read("{\"villagers\":{\"workBlock\":\"minecraft:air\"}}");
		// the config keeps what was typed; the fallback is RigTarget's job
		assertEquals(McIds.mc("air"), c().workBlockIdentifier());
		assertEquals(Blocks.LECTERN, RigTarget.workBlock());
	}
}
