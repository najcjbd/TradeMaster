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
	void compatibleWithVisibleTradersDefaultsOnAndRoundTrips() {
		assertTrue(TradeMasterConfig.defaults().compatibleWithVisibleTraders);

		c().compatibleWithVisibleTraders = false;
		JsonObject root = ConfigTest.write(c());
		assertFalse(root.getAsJsonObject("mechanism").get("compatibleWithVisibleTraders").getAsBoolean());

		c().compatibleWithVisibleTraders = true;
		ConfigTest.read(c(), root);
		assertFalse(c().compatibleWithVisibleTraders);

		// an absent key keeps the CURRENT value (the loader's fallback convention)
		c().compatibleWithVisibleTraders = true;
		ConfigTest.read(c(), parse("{\"mechanism\":{}}"));
		assertTrue(c().compatibleWithVisibleTraders);
		c().compatibleWithVisibleTraders = false;
		ConfigTest.read(c(), parse("{\"mechanism\":{}}"));
		assertFalse(c().compatibleWithVisibleTraders);
	}

	@Test
	void discountedPriceAsSalePriceRoundTripsAndDefaultsOff() {		assertFalse(TradeMasterConfig.defaults().discountedPriceAsSalePrice);

		c().discountedPriceAsSalePrice = true;
		JsonObject root = ConfigTest.write(c());
		assertEquals(2, root.get("configVersion").getAsInt());
		assertTrue(root.getAsJsonObject("mechanism").get("discountedPriceAsSalePrice").getAsBoolean());
		// downgrade compatibility mirror: older builds read the INVERSE key
		assertFalse(root.getAsJsonObject("mechanism").get("basePriceAsSalePrice").getAsBoolean());

		// the mirror in the other direction too
		c().discountedPriceAsSalePrice = false;
		JsonObject baseRoot = ConfigTest.write(c());
		assertFalse(baseRoot.getAsJsonObject("mechanism").get("discountedPriceAsSalePrice").getAsBoolean());
		assertTrue(baseRoot.getAsJsonObject("mechanism").get("basePriceAsSalePrice").getAsBoolean());

		// absent keys keep the CURRENT value (the loader's fallback convention); the
		// model default itself is false
		c().discountedPriceAsSalePrice = true;
		ConfigTest.read(c(), parse("{\"mechanism\":{}}"));
		assertTrue(c().discountedPriceAsSalePrice);

		ConfigTest.read(c(), root);
		assertTrue(c().discountedPriceAsSalePrice);

		// migration from the old "base price as sale" option
		c().discountedPriceAsSalePrice = true;
		ConfigTest.read(c(), parse("{\"mechanism\":{\"basePriceAsSalePrice\":true}}"));
		assertFalse(c().discountedPriceAsSalePrice);
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"mechanism\":{\"basePriceAsSalePrice\":false}}"));
		assertTrue(c().discountedPriceAsSalePrice);

		// a pre-v2 file with disagreeing keys: the old key (last written by a
		// downgraded build) wins
		c().discountedPriceAsSalePrice = true;
		ConfigTest.read(c(), parse("{\"configVersion\":1,\"mechanism\":{"
				+ "\"discountedPriceAsSalePrice\":false,\"basePriceAsSalePrice\":true}}"));
		assertFalse(c().discountedPriceAsSalePrice);
		// v2: the new key wins
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":2,\"mechanism\":{"
				+ "\"discountedPriceAsSalePrice\":true,\"basePriceAsSalePrice\":true}}"));
		assertTrue(c().discountedPriceAsSalePrice);
		// a malformed new key falls back to the old key
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":2,\"mechanism\":{"
				+ "\"discountedPriceAsSalePrice\":null,\"basePriceAsSalePrice\":false}}"));
		assertTrue(c().discountedPriceAsSalePrice);
		// a flat root (no mechanism section) still migrates the old key
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":1,\"basePriceAsSalePrice\":false}"));
		assertTrue(c().discountedPriceAsSalePrice);
		// a v2 file carrying ONLY the old key still migrates (downgrade/upgrade mix)
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":2,\"mechanism\":{\"basePriceAsSalePrice\":false}}"));
		assertTrue(c().discountedPriceAsSalePrice);
		// a v1 file carrying ONLY the new key still reads it
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":1,\"mechanism\":{\"discountedPriceAsSalePrice\":true}}"));
		assertTrue(c().discountedPriceAsSalePrice);
		// a malformed old key must not override a valid new one
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":2,\"mechanism\":{"
				+ "\"discountedPriceAsSalePrice\":true,\"basePriceAsSalePrice\":\"yes\"}}"));
		assertTrue(c().discountedPriceAsSalePrice);
		// string booleans are readable too (readBool accepts "true"/"false")
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":1,\"mechanism\":{\"basePriceAsSalePrice\":\"false\"}}"));
		assertTrue(c().discountedPriceAsSalePrice);
		c().discountedPriceAsSalePrice = false;
		ConfigTest.read(c(), parse("{\"configVersion\":2,\"mechanism\":{"
				+ "\"discountedPriceAsSalePrice\":\"true\",\"basePriceAsSalePrice\":true}}"));
		assertTrue(c().discountedPriceAsSalePrice);
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
	void anOverCappedAirListIsTruncatedToTheCapWithTheSentinel() {
		StringBuilder json = new StringBuilder("{\"offers\":{\"airItems\":[");
		for (int i = 0; i < TradeMasterConfig.MAX_AIR_ITEMS; i++) {
			if (i > 0) json.append(',');
			json.append("\"mod:item").append(i).append('"');
		}
		json.append("]}}");
		ConfigTest.read(c(), parse(json.toString()));
		assertEquals(TradeMasterConfig.MAX_AIR_ITEMS, c().airItems.size());
		assertTrue(c().airItems.contains("minecraft:air"));
	}

	@Test
	void airItemsAreNormalisedAndDeduped() {
		ConfigTest.read(c(), parse("{\"offers\":{\"airItems\":[\"AIR\",\"minecraft:air\",\"\",\"  \",\"minecraft:void_air\"]}}"));
		assertEquals(List.of("minecraft:air", "minecraft:void_air"), c().airItems);
	}

	@Test
	void goodFilterAndVariantKeyRoundTrip() {
		c().goods.clear();
		ResourceLocation key = McIds.mc("good_7");
		ResourceLocation result = McIds.mc("diamond_sword");
		c().goods.add(new GoodSpec(key, result, 1, true, 30,
				TradeOfferSpec.defaultGoodCosts(), "Excalibur",
				List.of(new GoodSpec.GoodEnchant(McIds.mc("sharpness"), 3),
						new GoodSpec.GoodEnchant(McIds.mc("mending"), 0),
						GoodSpec.GoodEnchant.present(McIds.mc("efficiency"))),
				"{Damage:0}", List.of(GoodSpec.GoodEnchant.present(McIds.mc("unbreaking"))), "{Damage:1}"));

		JsonObject root = ConfigTest.write(c());
		JsonObject written = root.getAsJsonObject("offers")
				.getAsJsonArray("goods").get(0).getAsJsonObject();
		assertEquals(key.toString(), written.get("id").getAsString());
		assertEquals("Excalibur", written.get("name").getAsString());
		assertTrue(written.getAsJsonArray("enchantments").toString().contains("sharpness:3"));
		assertTrue(written.getAsJsonArray("enchantments").toString().contains("mending:0"));
		assertTrue(written.getAsJsonArray("enchantments").toString().contains("efficiency"));
		assertEquals("minecraft:unbreaking", written.getAsJsonArray("excludeEnchantments").get(0).getAsString());
		assertEquals("{Damage:0}", written.get("nbt").getAsString());

		c().goods.clear();
		ConfigTest.read(c(), root);
		assertEquals(1, c().goods.size());
		GoodSpec read = c().goods.get(0);
		assertEquals(key, read.id());
		assertEquals(result, read.result());
		assertEquals("Excalibur", read.filterName());
		assertEquals(3, read.filterEnchantments().size());
		assertEquals(3, read.filterEnchantments().get(0).minLevel());
		assertEquals(3, read.filterEnchantments().get(0).maxLevel());
		assertTrue(read.filterEnchantments().get(1).absent());
		assertEquals(1, read.filterEnchantments().get(2).minLevel());
		assertEquals(0, read.filterEnchantments().get(2).maxLevel());
		assertEquals("{Damage:0}", read.filterNbt());
		assertEquals(1, read.excludedEnchantments().size());
		assertEquals("{Damage:1}", read.filterNbtExclude());
		assertTrue(read.filterValid());
	}

	@Test
	void safeTpDefaultsOnAndRoundTrips() {
		assertTrue(TradeMasterConfig.defaults().safeTp);
		c().safeTp = false;
		JsonObject root = ConfigTest.write(c());
		assertFalse(root.getAsJsonObject("antiCheat").get("safeTp").getAsBoolean());
		c().safeTp = true;
		ConfigTest.read(c(), root);
		assertFalse(c().safeTp);
	}

	@Test
	void pendingUnprovenRoundTripsAndClears() {
		c().pendingUnprovenMatches.clear();
		c().pendingUnprovenMatches.add(McIds.mc("sharpness"));
		c().pendingUnprovenMatches.add(McIds.mc("mending"));
		c().pendingUnprovenDimension = McIds.mc("overworld");
		c().pendingUnprovenWorld = "sp:rig";
		JsonObject root = ConfigTest.write(c());
		JsonObject pending = root.getAsJsonObject("pendingUnproven");
		assertEquals("sp:rig", pending.get("world").getAsString());
		assertEquals("minecraft:overworld", pending.get("dimension").getAsString());
		assertTrue(pending.getAsJsonArray("targets").toString().contains("minecraft:sharpness"));

		c().pendingUnprovenMatches.clear();
		c().pendingUnprovenDimension = null;
		c().pendingUnprovenWorld = null;
		ConfigTest.read(c(), root);
		assertEquals(2, c().pendingUnprovenMatches.size());
		assertEquals(McIds.mc("overworld"), c().pendingUnprovenDimension);
		assertEquals("sp:rig", c().pendingUnprovenWorld);

		c().pendingUnprovenMatches.clear();
		c().pendingUnprovenDimension = null;
		c().pendingUnprovenWorld = null;
		assertFalse(ConfigTest.write(c()).has("pendingUnproven"));
	}

	@Test
	void aliasChoicesRoundTrip() {
		c().aliasChoices.clear();
		c().aliasChoices.put("diamond sword", McIds.mc("trade_good"));
		JsonObject root = ConfigTest.write(c());
		assertEquals("minecraft:trade_good", root.getAsJsonObject("naming")
				.getAsJsonObject("aliasChoices").get("diamond sword").getAsString());
		c().aliasChoices.clear();
		ConfigTest.read(c(), root);
		assertEquals(McIds.mc("trade_good"), c().aliasChoices.get("diamond sword"));
	}

	@Test
	void rawInvalidEnchantFilterSurvivesALegacyResultEdit() {
		// a pre-id entry, an unparseable filter, then the result is changed: the
		// writer must not depend on finding the old entry to keep the raw text
		JsonObject root = parse("""
				{"offers":{"goods":[{"result":"minecraft:diamond_sword",
				  "enchantments":["minecraft:sharpness:3..1"]}]}}
				""");
		ConfigTest.read(c(), root);
		GoodSpec edited = c().goods.get(0).withResult(McIds.mc("barrel"));
		c().goods.set(0, edited);
		ConfigTest.writeInto(c(), root);
		JsonObject written = root.getAsJsonObject("offers")
				.getAsJsonArray("goods").get(0).getAsJsonObject();
		assertEquals("minecraft:barrel", written.get("result").getAsString());
		assertTrue(written.getAsJsonArray("enchantments").toString().contains("sharpness:3..1"));

		c().goods.clear();
		ConfigTest.read(c(), root);
		assertEquals(McIds.mc("barrel"), c().goods.get(0).result());
		assertTrue(c().goods.get(0).filterInvalid());
		assertFalse(c().goods.get(0).filterValid());
	}

	@Test
	void invalidGoodEnchantmentTokenMarksTheWholeFilterUnusableAndIsPreserved() {
		JsonObject root = parse("""
				{"offers":{"goods":[{"id":"trademaster:bad","result":"minecraft:diamond_sword",
				  "enchantments":["minecraft:sharpness:3..1","minecraft:mending"],
				  "excludeEnchantments":["minecraft:fire_aspect"]}]}}
				""");
		ConfigTest.read(c(), root);
		assertEquals(1, c().goods.size());
		GoodSpec bad = c().goods.get(0);
		assertEquals("trademaster:bad", bad.id().toString());
		// the bad token must never degrade into "no filter" (match every sword)
		assertFalse(bad.filterValid());
		assertTrue(bad.filterInvalid());

		// a real save seeds the on-disk entry, so the unparseable text survives for
		// the user to fix instead of being erased
		ConfigTest.writeInto(c(), root);
		JsonObject written = root.getAsJsonObject("offers")
				.getAsJsonArray("goods").get(0).getAsJsonObject();
		assertTrue(written.getAsJsonArray("enchantments").toString().contains("sharpness:3..1"));
		assertTrue(written.getAsJsonArray("excludeEnchantments").toString().contains("fire_aspect"));
	}

	@Test
	void objectEnchantmentFormsHonourAtLeastAndExplicitRanges() {
		JsonObject root = parse("""
				{"offers":{"goods":[{"result":"minecraft:diamond_sword","enchantments":[
				  {"id":"minecraft:sharpness","level":5,"atLeast":true},
				  {"id":"minecraft:mending","minLevel":1,"maxLevel":2},
				  {"id":"minecraft:unbreaking","level":0}]}]}}
				""");
		ConfigTest.read(c(), root);
		assertEquals(1, c().goods.size());
		GoodSpec good = c().goods.get(0);
		assertTrue(good.filterValid());
		assertEquals(List.of(5, 1, 0), good.filterEnchantments().stream()
				.map(GoodSpec.GoodEnchant::minLevel).toList());
		assertEquals(List.of(0, 2, 0), good.filterEnchantments().stream()
				.map(GoodSpec.GoodEnchant::maxLevel).toList());
		// level 0 is absence, per the documented syntax
		assertTrue(good.filterEnchantments().get(2).absent());
	}

	@Test
	void wrongTypedNestedLevelFieldsMarkTheFilterInvalid() {
		for (String entry : java.util.List.of(
				"{\"id\":\"minecraft:mending\",\"level\":\"high\"}",
				"{\"id\":\"minecraft:sharpness\",\"minLevel\":\"x\"}",
				"{\"id\":\"minecraft:sharpness\",\"atLeast\":\"yes\"}",
				"{\"id\":\"minecraft:sharpness\",\"level\":true}")) {
			ConfigTest.read(c(), parse("{\"offers\":{\"goods\":[{\"result\":\"minecraft:diamond_sword\","
					+ "\"enchantments\":[" + entry + "]}]}}"));
			assertFalse(c().goods.get(0).filterValid(), entry);
			assertTrue(c().goods.get(0).filterInvalid(), entry);
		}
	}

	@Test
	void wrongTypedEnchantmentFieldMarksTheGoodUnusable() {
		JsonObject root = parse("""
				{"offers":{"goods":[{"result":"minecraft:diamond_sword",
				  "enchantments":"minecraft:mending"}]}}
				""");
		ConfigTest.read(c(), root);
		assertFalse(c().goods.get(0).filterValid());
		assertTrue(c().goods.get(0).filterInvalid());
		ConfigTest.writeInto(c(), root);
		c().goods.clear();
		ConfigTest.read(c(), root);
		assertFalse(c().goods.get(0).filterValid());
		assertTrue(c().goods.get(0).filterInvalid());

		root = parse("""
				{"offers":{"goods":[{"result":"minecraft:diamond_sword",
				  "enchantments":["minecraft:mending",5]}]}}
				""");
		ConfigTest.read(c(), root);
		assertFalse(c().goods.get(0).filterValid());
		assertTrue(c().goods.get(0).filterInvalid());
		ConfigTest.writeInto(c(), root);
		c().goods.clear();
		ConfigTest.read(c(), root);
		assertFalse(c().goods.get(0).filterValid());
	}

	@Test
	void aReversedObjectRangeMarksTheFilterInvalid() {
		JsonObject root = parse("""
				{"offers":{"goods":[{"result":"minecraft:diamond_sword","enchantments":[
				  {"id":"minecraft:sharpness","minLevel":5,"maxLevel":2}]}]}}
				""");
		ConfigTest.read(c(), root);
		GoodSpec good = c().goods.get(0);
		assertFalse(good.filterValid());
	}

	@Test
	void goodsRejectAirAndBookAndAllowSameItemVariants() {
		// air and enchanted-book results are skipped; two entries with the same result
		// are now two variants, each with its own stable key
		ConfigTest.read(c(), parse("""
				{"offers":{"goods":[
				  {"result":"minecraft:air","costs":[]},
				  {"result":"minecraft:enchanted_book","costs":[]},
				  {"result":"minecraft:composter","maxPrice":9,"costs":[]},
				  {"result":"minecraft:composter","maxPrice":1,"costs":[]}
				]}}"""));
		assertEquals(2, c().goods.size());
		GoodSpec good = c().goods.get(0);
		assertEquals(McIds.mc("composter"), good.id());
		assertEquals(9, good.maxPrice());
		// a good written with no costs falls back to the "price slot only" default shape
		assertEquals(TradeOfferSpec.EMERALD, good.costs().get(0).item());
		assertEquals(TradeOfferSpec.AIR, good.costs().get(1).item());
		GoodSpec variant = c().goods.get(1);
		assertEquals(McIds.mc("composter"), variant.result());
		assertFalse(variant.id().equals(good.id()));
		assertEquals(1, variant.maxPrice());
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
