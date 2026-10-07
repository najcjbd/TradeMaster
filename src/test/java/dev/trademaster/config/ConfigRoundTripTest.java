package dev.trademaster.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.trademaster.testutil.McTest;
import net.minecraft.core.BlockPos;
import dev.trademaster.compat.McIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config file round-trip. Combines several sections in one document, because the
 * interesting failures (a section reader clearing a sibling's state, a hand-written key
 * being dropped on save) only appear when features share the file.
 */
class ConfigRoundTripTest {

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

	private JsonObject parse(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test
	void villagersSectionIsWrittenAndRead() {
		c().workBlockId = "minecraft:composter";
		c().professionId = "minecraft:farmer";

		JsonObject root = ConfigTest.write(c());
		JsonObject villagers = root.getAsJsonObject("villagers");
		assertEquals("minecraft:composter", villagers.get("workBlock").getAsString());
		assertEquals("minecraft:farmer", villagers.get("profession").getAsString());

		// wipe, then read it back
		c().workBlockId = "minecraft:lectern";
		c().professionId = "minecraft:librarian";
		ConfigTest.read(c(), root);
		assertEquals("minecraft:composter", c().workBlockId);
		assertEquals("minecraft:farmer", c().professionId);
	}

	@Test
	void villagersIdsAreNormalisedOnRead() {
		ConfigTest.read(c(), parse("{\"villagers\":{\"workBlock\":\"COMPOSTER\",\"profession\":\"Farmer\"}}"));
		assertEquals("minecraft:composter", c().workBlockId);
		assertEquals("minecraft:farmer", c().professionId);
	}

	@Test
	void blankVillagerIdKeepsCurrentValue() {
		c().workBlockId = "minecraft:barrel";
		ConfigTest.read(c(), parse("{\"villagers\":{\"workBlock\":\"  \",\"profession\":\"\"}}"));
		assertEquals("minecraft:barrel", c().workBlockId);
		assertEquals("minecraft:librarian", c().professionId);
	}

	@Test
	void positionsIncludingDispenserRoundTrip() {
		c().lecternPos = new BlockPos(14, -60, 8);
		c().summonButtonPos = new BlockPos(17, -58, 8);
		c().sendAwayButtonPos = new BlockPos(15, -60, 8);
		c().dispenserPos = new BlockPos(17, -58, 7);
		c().chestPositions.clear();
		c().addChest(new BlockPos(14, -60, 11));

		JsonObject root = ConfigTest.write(c());
		JsonObject positions = root.getAsJsonObject("positions");
		assertEquals(3, positions.getAsJsonArray("dispenser").size());
		assertEquals(7, positions.getAsJsonArray("dispenser").get(2).getAsInt());

		c().lecternPos = null;
		c().dispenserPos = null;
		c().chestPositions.clear();
		ConfigTest.read(c(), root);
		assertEquals(new BlockPos(14, -60, 8), c().lecternPos);
		assertEquals(new BlockPos(17, -58, 7), c().dispenserPos);
		assertEquals(List.of(new BlockPos(14, -60, 11)), c().chestPositions);
	}

	@Test
	void nullDispenserRoundTripsAsNull() {
		c().dispenserPos = null;
		JsonObject root = ConfigTest.write(c());
		assertTrue(root.getAsJsonObject("positions").get("dispenser").isJsonNull());
		c().dispenserPos = new BlockPos(1, 2, 3);
		ConfigTest.read(c(), root);
		assertNull(c().dispenserPos);
	}

	@Test
	void airItemsAreNormalisedAndDeduped() {
		ConfigTest.read(c(), parse("{\"offers\":{\"airItems\":[\"AIR\",\"minecraft:air\",\"\",\"  \",\"minecraft:void_air\"]}}"));
		assertEquals(List.of("minecraft:air", "minecraft:void_air"), c().airItems);
	}

	@Test
	void goodsWithAirOrBookOrDuplicateResultAreRejected() {
		// air result, enchanted_book result, and two entries with the same result: only the
		// first non-special good survives
		ConfigTest.read(c(), parse("""
				{"offers":{"goods":[
				  {"result":"minecraft:air","costs":[]},
				  {"result":"minecraft:enchanted_book","costs":[]},
				  {"result":"minecraft:composter","maxPrice":9,"costs":[]},
				  {"result":"minecraft:composter","maxPrice":1,"costs":[]}
				]}}"""));
		assertEquals(1, c().goods.size());
		GoodSpec good = c().goods.get(0);
		assertEquals(McIds.mc("composter"), good.id());
		assertEquals(9, good.maxPrice());
		// a good written with no costs falls back to the "price slot only" default shape
		assertEquals(TradeOfferSpec.EMERALD, good.costs().get(0).item());
		assertEquals(TradeOfferSpec.AIR, good.costs().get(1).item());
	}

	@Test
	void handWrittenUnknownKeysSurviveASave() {
		// a note at the root, a note inside a section, and one nested in a spec entry
		JsonObject root = parse("""
				{
				  "futureTopLevel": {"x": 1},
				  "villagers": {"workBlock":"minecraft:lectern","note":"keep me"},
				  "offers": {"specs": {"minecraft:sharpness": {"enabled":true,"level":5,"maxPrice":20,"futureKey":"keep"}}},
				  "positions": {"lectern":[1,2,3],"futurePos":"keep"}
				}""");
		ConfigTest.read(c(), root);
		ConfigTest.writeInto(c(), root);
		JsonObject written = root;
		assertTrue(written.has("futureTopLevel"));
		assertEquals("keep me", written.getAsJsonObject("villagers").get("note").getAsString());
		assertEquals("keep", written.getAsJsonObject("positions").get("futurePos").getAsString());
		JsonObject spec = written.getAsJsonObject("offers").getAsJsonObject("specs")
				.getAsJsonObject("minecraft:sharpness");
		assertEquals("keep", spec.get("futureKey").getAsString());
	}

	@Test
	void flatRootVillagersObjectIsNotReadAsAnEnchantment() {
		// a pre-sections flat file: "villagers" must be skipped, not parsed as a target id
		ConfigTest.read(c(), parse("""
				{"villagers":{"workBlock":"minecraft:composter"},"minecraft:sharpness":{"enabled":true,"level":5,"maxPrice":20}}"""));
		assertTrue(c().enchantments.containsKey(McIds.mc("sharpness")));
		assertFalse(c().enchantments.containsKey(McIds.mc("villagers")));
		assertEquals("minecraft:composter", c().workBlockId);
	}

	@Test
	void handEditedValuesAreClampedLikeTheGui() {
		ConfigTest.read(c(), parse("""
				{"mechanism":{"minLockEmeralds":0,"timeoutTicks":"5t","villagerCheckInterval":999999,
				  "lecternHealGrace":"1t","professionHealCap":99},
				 "antiCheat":{"placeDelay":99,"interactDelay":-5},
				 "positions":{"villagerSphereRadius":999}}"""));
		assertEquals(1, c().minLockEmeralds);
		assertEquals(1200, c().timeoutTicks);
		assertEquals(72000, c().villagerCheckInterval);
		assertEquals(20, c().lecternHealGrace);
		assertEquals(20, c().professionHealCap);
		assertEquals(20, c().placeDelay);
		assertEquals(0, c().interactDelay);
		assertEquals(64, c().villagerSphereRadius);
	}

	@Test
	void autoDisableWinsOverHuntCheaper() {
		ConfigTest.read(c(), parse("{\"mechanism\":{\"autoDisableAfterLock\":true,\"huntCheaper\":true}}"));
		assertTrue(c().autoDisableAfterLock);
		assertFalse(c().huntCheaper);
	}

	@Test
	void offerSpecRoundTripsWithPositionsPreserved() {
		JsonObject root = parse("""
				{"offers":{"specs":{"minecraft:mending":{"result":"minecraft:enchanted_book",
				  "costs":[{"item":"minecraft:emerald","count":0},{"item":"minecraft:book","count":-1},{"item":"minecraft:diamond","count":3}]}}}}""");
		ConfigTest.read(c(), root);
		TradeOfferSpec spec = c().tradeSpecs.get(McIds.mc("mending"));
		assertEquals(3, spec.costs().size());
		assertEquals(3, spec.costs().get(2).count());
		assertEquals(McIds.mc("diamond"), spec.costs().get(2).item());

		JsonObject written = ConfigTest.write(c());
		JsonObject costs = written.getAsJsonObject("offers").getAsJsonObject("specs")
				.getAsJsonObject("minecraft:mending");
		assertEquals(3, costs.getAsJsonArray("costs").size());
	}

	@Test
	void specWithNoCostsFallsBackToDefaultShape() {
		ConfigTest.read(c(), parse("{\"offers\":{\"specs\":{\"minecraft:mending\":{\"costs\":[]}}}}"));
		TradeOfferSpec spec = c().tradeSpecs.get(McIds.mc("mending"));
		assertEquals(2, spec.costs().size());
		assertEquals(TradeOfferSpec.EMERALD, spec.costs().get(0).item());
		assertEquals(TradeOfferSpec.BOOK, spec.costs().get(1).item());
	}

	@Test
	void nonTradeableProfessionsRoundTrip() {
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("minecraft:cleric");
		c().nonTradeableProfessions.add("some_mod:broken_job");

		JsonObject root = ConfigTest.write(c());
		JsonObject villagers = root.getAsJsonObject("villagers");
		assertEquals(2, villagers.getAsJsonArray("nonTradeableProfessions").size());

		c().nonTradeableProfessions.clear();
		ConfigTest.read(c(), root);
		assertEquals(List.of("minecraft:cleric", "some_mod:broken_job"), c().nonTradeableProfessions);
	}

	@Test
	void nonTradeableProfessionsAreNormalisedAndDeduplicatedOnRead() {
		ConfigTest.read(c(),
				parse("{\"villagers\":{\"nonTradeableProfessions\":[\"CLERIC\",\"minecraft:cleric\",\"  \",\"a\"]}}"));
		assertEquals(List.of("minecraft:cleric", "minecraft:a"), c().nonTradeableProfessions);
	}

	@Test
	void anEmptyArrayClearsTheNonTradeableListButAnAbsentOneKeepsIt() {
		c().nonTradeableProfessions.clear();
		c().nonTradeableProfessions.add("minecraft:cleric");
		// a file without the array must keep the list (partial file)
		ConfigTest.read(c(), parse("{\"villagers\":{\"workBlock\":\"minecraft:lectern\"}}"));
		assertEquals(List.of("minecraft:cleric"), c().nonTradeableProfessions);
		// an explicit empty array clears it
		ConfigTest.read(c(), parse("{\"villagers\":{\"nonTradeableProfessions\":[]}}"));
		assertTrue(c().nonTradeableProfessions.isEmpty());
	}

	@Test
	void handWrittenNotesUnderSpecsSurviveASave() {
		JsonObject root = parse("""
				{"offers":{"specs":{"_note":"keep me","minecraft:mending":{"costs":[],"note":"and me"}}}}""");
		ConfigTest.read(c(), root);
		ConfigTest.writeInto(c(), root);
		JsonObject specs = root.getAsJsonObject("offers").getAsJsonObject("specs");
		// the note object (not shaped like an entry) survives
		assertEquals("keep me", specs.get("_note").getAsString());
		// and a note nested inside a real spec entry survives too
		assertEquals("and me", specs.getAsJsonObject("minecraft:mending").get("note").getAsString());
	}
}
