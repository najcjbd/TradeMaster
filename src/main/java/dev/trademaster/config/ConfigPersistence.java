package dev.trademaster.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.trademaster.TradeMasterClient;
import dev.trademaster.compat.McIds;
import dev.trademaster.platform.Platforms;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigPersistence {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int CONFIG_VERSION = 1;

	/** The top-level section keys this file uses. A file that predates sections stored
	 * the same keys at the top level, so a flat root must never be read as data (an
	 * enchantment id, a chest, ...): without this skip a missing/wrong-typed
	 * "enchantments" section would parse "mechanism", "naming", ... as bogus targets
	 * and replace the whole enchantment table. */
	private static final java.util.Set<String> SECTION_NAMES = java.util.Set.of(
			"mechanism", "naming", "positions", "grindstone", "villagers", "offers",
			"antiCheat", "enchantments", "hotkeys",
			// sub-keys an early flat layout stored at the root
			"specs", "customNames", "nameHistory", "airItems");

	private ConfigPersistence() {}

	public static Path configFile() {
		return Platforms.get().configDir().resolve("trade-master.json");
	}

	public static void load(TradeMasterConfig config) {
		Path file = configFile();
		if (!Files.isRegularFile(file)) {
			// crash between the temp write and the atomic move leaves the whole
			// config in the .tmp: recover it instead of starting from defaults
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			if (Files.isRegularFile(tmp)) {
				try {
					Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					TradeMasterClient.LOGGER.error("Failed to recover trade-master.json.tmp", e);
				}
			}
			if (!Files.isRegularFile(file)) {
				importLibrarianTradeFinder(config);
				return;
			}
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			readInto(config, root);
		} catch (Exception e) {
			TradeMasterClient.LOGGER.error("Failed to load trade-master.json", e);
			// quarantine the broken file: the next save must not silently
			// overwrite it with defaults while it could still be hand-salvaged.
			// Never clobber an earlier quarantine — pick a unique name instead
			Path quarantine = file.resolveSibling(file.getFileName() + ".corrupt");
			int copies = 1;
			for (int i = 1; Files.exists(quarantine); i++) {
				copies = i + 1;
				quarantine = file.resolveSibling(file.getFileName() + ".corrupt." + i);
			}
			if (copies > 5) {
				// a persistently broken file (e.g. a disk that never saves) must not
				// accumulate unbounded .corrupt.N copies; leave it in place instead
				TradeMasterClient.LOGGER.error("Too many quarantined trade-master.json copies, leaving the broken file in place");
			} else {
				try {
					Files.move(file, quarantine);
					TradeMasterClient.LOGGER.error("Quarantined broken config as {}", quarantine.getFileName());
				} catch (IOException io) {
					// the move failed but the file still holds salvageable content:
					// copy it aside so the next save cannot destroy it
					try {
						Files.copy(file, quarantine, StandardCopyOption.REPLACE_EXISTING);
						TradeMasterClient.LOGGER.error("Copied broken config to {} (move failed)", quarantine.getFileName());
					} catch (IOException copyError) {
						TradeMasterClient.LOGGER.error("Failed to quarantine broken trade-master.json", copyError);
					}
				}
			}
		}
	}

	/**
	 * First-launch migration: when trade-master.json does not exist yet, pull
	 * the anti-cheat flags and enchantment targets over from a
	 * LibrarianTradeFinder config so upgrading users keep their setup.
	 */
	private static void importLibrarianTradeFinder(TradeMasterConfig c) {
		Path file = Platforms.get().configDir().resolve("librarian-trade-finder.json");
		if (!Files.isRegularFile(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			boolean found = root.has("preventAxeBreaking") || root.has("tpToVillager")
					|| root.has("legitMode") || root.has("slowMode")
					|| root.has("enchantments");
			c.preventAxeBreaking = readBool(root, "preventAxeBreaking", c.preventAxeBreaking);
			c.tpToVillager = readBool(root, "tpToVillager", c.tpToVillager);
			c.legitMode = readBool(root, "legitMode", c.legitMode);
			c.slowMode = readBool(root, "slowMode", c.slowMode);
			// same coupling as the settings UIs: slow mode requires legit
			if (c.slowMode) c.legitMode = true;
			int imported = 0;
			JsonObject enchantments = getSection(root, "enchantments");
			if (enchantments != null) {
				for (Map.Entry<String, JsonElement> entry : enchantments.entrySet()) {
					ResourceLocation id = McIds.tryParse(entry.getKey());
					if (id == null || !entry.getValue().isJsonObject()) continue;
					JsonObject o = entry.getValue().getAsJsonObject();
					c.enchantments.put(id, new EnchantmentTarget(
							id,
							readBool(o, "enabled", false),
							Math.max(1, Math.min(99, readInt(o, "level", 1))),
							Math.max(1, Math.min(64, readInt(o, "maxPrice", 64)))));
					imported++;
				}
			}
			if (!found) {
				// nothing recognisable in it: do not write a config file on its behalf
				TradeMasterClient.LOGGER.info("librarian-trade-finder.json holds nothing to import, keeping defaults");
				return;
			}
			save(c);
			TradeMasterClient.LOGGER.info("Imported settings from librarian-trade-finder.json ({} enchantment targets)", imported);
		} catch (Exception e) {
			TradeMasterClient.LOGGER.error("Failed to import librarian-trade-finder.json", e);
		}
	}

	public static void save(TradeMasterConfig config) {
		// seed with the file as it is on disk: hand-written keys (and keys written by
		// a NEWER version) must survive a save instead of being silently dropped
		JsonObject root = existingRoot();
		if (root == null) root = new JsonObject();
		writeFrom(config, root);
		Path file = configFile();
		try {
			Path dir = file.getParent();
			if (dir != null && !Files.exists(dir)) {
				Files.createDirectories(dir);
			}
			// atomic replace: a crash mid-write must never truncate the config
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			TradeMasterClient.LOGGER.error("Failed to save trade-master.json", e);
		}
	}

	/** The config as currently on disk, or null when there is none or it cannot be
	 * parsed (the write must still succeed then). */
	private static @Nullable JsonObject existingRoot() {
		Path file = configFile();
		if (!Files.isRegularFile(file)) return null;
		try {
			JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** Serialises the model into {@code root} (merging, see {@link #save}). Package-private
	 * so a test can round-trip the model through JSON without a FabricLoader config dir. */
	static void writeFrom(TradeMasterConfig c, JsonObject root) {
		root.addProperty("configVersion", CONFIG_VERSION);

		JsonObject mechanism = sectionForWrite(root, "mechanism");
		mechanism.addProperty("reroll", c.rerollMechanism.name());
		mechanism.addProperty("detection", c.detectionMode.name());
		mechanism.addProperty("reserveCheaperTraders", c.reserveCheaperTraders);
		mechanism.addProperty("minLockEmeralds", c.minLockEmeralds);
		mechanism.addProperty("timeoutEnabled", c.timeoutEnabled);
		mechanism.addProperty("timeoutTicks", DurationParser.format(c.timeoutTicks));
		mechanism.addProperty("phaseTimeoutsEnabled", c.phaseTimeoutsEnabled);
		mechanism.addProperty("villagerCheckInterval", DurationParser.format(c.villagerCheckInterval));
		mechanism.addProperty("lecternHealGrace", DurationParser.format(c.lecternHealGrace));
		mechanism.addProperty("professionHealCapEnabled", c.professionHealCapEnabled);
		mechanism.addProperty("professionHealCap", c.professionHealCap);
		mechanism.addProperty("autoDisableAfterLock", c.autoDisableAfterLock);
		mechanism.addProperty("huntCheaper", c.huntCheaper);
		mechanism.addProperty("lockMaxRetries", c.lockMaxRetries);
		mechanism.addProperty("postDispatchBreakRetries", c.postDispatchBreakRetries);
		mechanism.addProperty("offhandSwapRetries", c.offhandSwapRetries);
		mechanism.addProperty("multiEnchantProtection", c.multiEnchantProtection);
		mechanism.addProperty("displayRolls", c.displayRolls);
		mechanism.addProperty("debugLog", c.debugLog);
		root.add("mechanism", mechanism);

		JsonObject naming = sectionForWrite(root, "naming");
		naming.addProperty("enabled", c.namingEnabled);
		naming.addProperty("forceNamePriceOne", c.forceNamePriceOne);
		JsonObject customNames = new JsonObject();
		for (Map.Entry<ResourceLocation, String> entry : c.customEnchantNames.entrySet()) {
			customNames.addProperty(entry.getKey().toString(), entry.getValue());
		}
		naming.add("customNames", customNames);
		JsonObject nameHistory = new JsonObject();
		for (Map.Entry<String, ResourceLocation> entry : c.nameHistory.entrySet()) {
			nameHistory.addProperty(entry.getKey(), entry.getValue().toString());
		}
		naming.add("nameHistory", nameHistory);
		root.add("naming", naming);

		JsonObject positions = sectionForWrite(root, "positions");
		positions.add("lectern", writePos(c.lecternPos));
		positions.add("summonButton", writePos(c.summonButtonPos));
		positions.add("sendAwayButton", writePos(c.sendAwayButtonPos));
		positions.add("dispenser", writePos(c.dispenserPos));
		positions.add("villagerRegionOrigin", writePos(c.villagerRegionOrigin));
		positions.add("villagerRegionDelta", writePos(c.villagerRegionDelta));
		positions.addProperty("villagerSphereRadius", c.villagerSphereRadius);
		JsonArray chests = new JsonArray();
		for (net.minecraft.core.BlockPos chest : c.chestPositions) {
			chests.add(writePos(chest));
		}
		positions.add("chests", chests);
		root.add("positions", positions);

		JsonObject villagers = sectionForWrite(root, "villagers");
		villagers.addProperty("workBlock", c.workBlockId);
		villagers.addProperty("profession", c.professionId);
		JsonArray nonTradeable = new JsonArray();
		for (String id : c.nonTradeableProfessions) {
			if (id != null && !id.isBlank()) nonTradeable.add(id);
		}
		villagers.add("nonTradeableProfessions", nonTradeable);
		root.add("villagers", villagers);

		JsonObject grindstone = sectionForWrite(root, "grindstone");
		grindstone.addProperty("enabled", c.grindstoneEnabled);
		grindstone.add("pos", writePos(c.grindstonePos));
		grindstone.addProperty("openRetries", c.grindstoneOpenRetries);
		// the section object is REUSED from disk, so a nulled direction must be REMOVED:
		// skipping the write would leave the old key in place and re-emit it forever
		if (c.curseDropYaw != null) grindstone.addProperty("curseDropYaw", c.curseDropYaw);
		else grindstone.remove("curseDropYaw");
		if (c.curseDropPitch != null) grindstone.addProperty("curseDropPitch", c.curseDropPitch);
		else grindstone.remove("curseDropPitch");
		JsonArray customCurses = new JsonArray();
		for (String curse : c.customCurseEnchantments) {
			customCurses.add(curse);
		}
		grindstone.add("customCurses", customCurses);
		root.add("grindstone", grindstone);

		root.add("offers", writeOffers(c, root));

		JsonObject antiCheat = sectionForWrite(root, "antiCheat");
		antiCheat.addProperty("legitMode", c.legitMode);
		antiCheat.addProperty("slowMode", c.slowMode);
		antiCheat.addProperty("tpToVillager", c.tpToVillager);
		antiCheat.addProperty("tpToItem", c.tpToItem);
		antiCheat.addProperty("preventAxeBreaking", c.preventAxeBreaking);
		antiCheat.addProperty("stopOnPlayerMove", c.stopOnPlayerMove);
		antiCheat.addProperty("placeDelay", c.placeDelay);
		antiCheat.addProperty("interactDelay", c.interactDelay);
		root.add("antiCheat", antiCheat);

		// reuse the on-disk section so a key a hand-writer put directly under it survives,
		// but drop stale TARGET entries (an id-parseable key the model no longer has would
		// be re-read as a bogus target). "Target entry" = the value carries one of the
		// entry keys; a note object WITHOUT them is left alone.
		JsonObject enchantments = sectionForWrite(root, "enchantments");
		List<String> stale = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : enchantments.entrySet()) {
			ResourceLocation parsed = McIds.tryParse(e.getKey());
			if (parsed != null && !c.enchantments.containsKey(parsed) && looksLikeTargetEntry(e.getValue())) {
				stale.add(e.getKey());
			}
		}
		for (String key : stale) enchantments.remove(key);
		for (Map.Entry<ResourceLocation, EnchantmentTarget> entry : c.enchantments.entrySet()) {
			EnchantmentTarget t = entry.getValue();
			// seed from the entry on disk so a hand-written key inside it survives
			JsonObject o = entryObject(enchantments, entry.getKey());
			o.addProperty("enabled", t.enabled());
			o.addProperty("level", t.level());
			o.addProperty("maxPrice", t.maxPrice());
			enchantments.add(entry.getKey().toString(), o);
		}
		// one target, one key: a hand-written namespaceless key ("sharpness") that
		// duplicates a REAL target is the same id as "minecraft:sharpness" and would
		// otherwise stay alongside it forever. Only target-shaped entries are dropped — a
		// note object carrying none of the entry keys is left alone.
		List<String> aliases = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : enchantments.entrySet()) {
			ResourceLocation parsed = McIds.tryParse(e.getKey());
			if (parsed != null && c.enchantments.containsKey(parsed)
					&& !e.getKey().equals(parsed.toString()) && looksLikeTargetEntry(e.getValue())) {
				aliases.add(e.getKey());
			}
		}
		for (String key : aliases) enchantments.remove(key);
		root.add("enchantments", enchantments);

		// merge the adapter's keys into the on-disk object: same reason as above
		JsonObject hotkeys = sectionForWrite(root, "hotkeys");
		for (Map.Entry<String, JsonElement> entry : MaliLibAdapter.writeHotkeys().entrySet()) {
			hotkeys.add(entry.getKey(), entry.getValue());
		}
		root.add("hotkeys", hotkeys);
	}

	/** Reads {@code root} into the model (see {@link #load}). Package-private so a test can
	 * round-trip the model through JSON without a FabricLoader config dir. */
	static void readInto(TradeMasterConfig c, JsonObject root) {
		JsonObject mechanism = sectionOrRoot(root, "mechanism");
		if (mechanism != null) {
			c.rerollMechanism = readEnum(mechanism, "reroll", c.rerollMechanism);
			c.detectionMode = readEnum(mechanism, "detection", c.detectionMode);
			c.reserveCheaperTraders = readBool(mechanism, "reserveCheaperTraders", c.reserveCheaperTraders);
			c.minLockEmeralds = readInt(mechanism, "minLockEmeralds", c.minLockEmeralds);
			c.timeoutEnabled = readBool(mechanism, "timeoutEnabled", c.timeoutEnabled);
			c.timeoutTicks = readDuration(mechanism, "timeoutTicks", c.timeoutTicks);
			c.phaseTimeoutsEnabled = readBool(mechanism, "phaseTimeoutsEnabled", c.phaseTimeoutsEnabled);
			c.villagerCheckInterval = readDuration(mechanism, "villagerCheckInterval", c.villagerCheckInterval);
			c.lecternHealGrace = readDuration(mechanism, "lecternHealGrace", c.lecternHealGrace);
			c.professionHealCapEnabled = readBool(mechanism, "professionHealCapEnabled", c.professionHealCapEnabled);
			c.professionHealCap = readInt(mechanism, "professionHealCap", c.professionHealCap);
			c.autoDisableAfterLock = readBool(mechanism, "autoDisableAfterLock", c.autoDisableAfterLock);
			c.huntCheaper = readBool(mechanism, "huntCheaper", c.huntCheaper);
			// hand-edited file may carry both: autoDisableAfterLock wins, same as maLiLib
			if (c.autoDisableAfterLock) c.huntCheaper = false;
			c.lockMaxRetries = readInt(mechanism, "lockMaxRetries", c.lockMaxRetries);
			c.postDispatchBreakRetries = Math.min(Math.max(-1,
					readInt(mechanism, "postDispatchBreakRetries", c.postDispatchBreakRetries)), 999);
			c.offhandSwapRetries = readInt(mechanism, "offhandSwapRetries", c.offhandSwapRetries);
			c.multiEnchantProtection = readBool(mechanism, "multiEnchantProtection", c.multiEnchantProtection);
			c.displayRolls = readBool(mechanism, "displayRolls", c.displayRolls);
			c.debugLog = readBool(mechanism, "debugLog", c.debugLog);
		}

		JsonObject naming = sectionOrRoot(root, "naming");
		if (naming != null) {
			// a FLAT file (no sections) cannot say which "enabled" it meant: reading the
			// root key here AND for the grindstone let one section switch off the other.
			// Only a real section object may carry it.
			if (naming != root) c.namingEnabled = readBool(naming, "enabled", c.namingEnabled);
			c.forceNamePriceOne = readBool(naming, "forceNamePriceOne", c.forceNamePriceOne);
			JsonObject customNames = getSection(naming, "customNames");
			// only clear when the file actually carries the section: a partial
			// file without it must keep the in-memory values
			if (customNames != null) {
				c.customEnchantNames.clear();
				for (Map.Entry<String, JsonElement> entry : customNames.entrySet()) {
					ResourceLocation id = McIds.tryParse(entry.getKey());
					if (id != null && entry.getValue().isJsonPrimitive()) {
						// trim: villager names are parsed with trim(), so a leading
						// space would make generated names unmatchable forever
						String name = entry.getValue().getAsString().trim();
						if (!name.isEmpty()) c.customEnchantNames.put(id, name);
					}
				}
			}
			JsonObject nameHistory = getSection(naming, "nameHistory");
			if (nameHistory != null) {
				c.nameHistory.clear();
				for (Map.Entry<String, JsonElement> entry : nameHistory.entrySet()) {
					String name = entry.getKey().trim();
					if (name.isEmpty() || !entry.getValue().isJsonPrimitive()) continue;
					ResourceLocation id = McIds.tryParse(entry.getValue().getAsString());
					if (id != null) c.nameHistory.put(name, id);
				}
				// a hand-edited file may exceed the cap: keep the newest entries
				while (c.nameHistory.size() > 256) {
					c.nameHistory.remove(c.nameHistory.keySet().iterator().next());
				}
			}
		}

		JsonObject positions = sectionOrRoot(root, "positions");
		if (positions != null) {
			c.lecternPos = readPos(positions.get("lectern"));
			c.summonButtonPos = readPos(positions.get("summonButton"));
			c.sendAwayButtonPos = readPos(positions.get("sendAwayButton"));
			c.dispenserPos = readPos(positions.get("dispenser"));
			c.villagerRegionOrigin = readPos(positions.get("villagerRegionOrigin"));
			c.villagerRegionDelta = readPos(positions.get("villagerRegionDelta"));
			c.villagerSphereRadius = Math.max(1, Math.min(64,
					readInt(positions, "villagerSphereRadius", c.villagerSphereRadius)));
			JsonElement chests = positions.get("chests");
			if (chests != null && chests.isJsonArray()) {
				// clear only when the file carries a chests array: a partial file
				// without it must keep the bound chests
				c.chestPositions.clear();
				for (JsonElement e : chests.getAsJsonArray()) {
					if (c.chestPositions.size() >= TradeMasterConfig.MAX_CHESTS) {
						TradeMasterClient.LOGGER.warn("Ignoring chests beyond the first {} in trade-master.json",
								TradeMasterConfig.MAX_CHESTS);
						break;
					}
					BlockPos pos = readPos(e);
					if (pos != null) c.addChest(pos);
				}
			}
		}

		JsonObject villagers = sectionOrRoot(root, "villagers");
		if (villagers != null) {
			c.workBlockId = readIdString(villagers, "workBlock", c.workBlockId);
			c.professionId = readIdString(villagers, "profession", c.professionId);
			JsonElement nonTradeable = villagers.get("nonTradeableProfessions");
			if (nonTradeable != null && nonTradeable.isJsonArray()) {
				// clear only when the file carries the array: a partial file must keep the list
				c.nonTradeableProfessions.clear();
				for (JsonElement e : nonTradeable.getAsJsonArray()) {
					if (!e.isJsonPrimitive()) continue;
					if (c.nonTradeableProfessions.size() >= TradeMasterConfig.MAX_NON_TRADEABLE) {
						TradeMasterClient.LOGGER.warn("Ignoring profession ids beyond the first {} in trade-master.json",
								TradeMasterConfig.MAX_NON_TRADEABLE);
						break;
					}
					// the same normalisation readIdString applies: a namespaceless entry means
					// minecraft:, a hand-written "NITWIT" would otherwise never match
					ResourceLocation id = TradeMasterConfig.parseId(e.getAsString());
					if (id != null && !c.nonTradeableProfessions.contains(id.toString())) {
						c.nonTradeableProfessions.add(id.toString());
					}
				}
			}
		}

		JsonObject grindstone = sectionOrRoot(root, "grindstone");
		if (grindstone != null) {
			// see the naming branch: a flat file's single "enabled" must not drive both
			if (grindstone != root) c.grindstoneEnabled = readBool(grindstone, "enabled", c.grindstoneEnabled);
			c.grindstonePos = readPos(grindstone.get("pos"));
			c.grindstoneOpenRetries = Math.min(Math.max(-1,
					readInt(grindstone, "openRetries", c.grindstoneOpenRetries)), 999);
			c.curseDropYaw = readFloat(grindstone.get("curseDropYaw"));
			c.curseDropPitch = readFloat(grindstone.get("curseDropPitch"));
			JsonElement customCurses = grindstone.get("customCurses");
			if (customCurses != null && customCurses.isJsonArray()) {
				c.customCurseEnchantments.clear();
				for (JsonElement e : customCurses.getAsJsonArray()) {
					if (!e.isJsonPrimitive()) continue;
					ResourceLocation id = McIds.tryParse(e.getAsString());
					// vanilla ids are forbidden: vanilla curses are detected via
					// the curse tag, a hand-entered one could never add anything
					if (id != null && !"minecraft".equals(id.getNamespace())) {
						c.customCurseEnchantments.add(id.toString());
					}
				}
			}
		}

		JsonObject offers = sectionOrRoot(root, "offers");
		if (offers != null) {
			JsonElement air = offers.get("airItems");
			if (air != null && air.isJsonArray()) {
				c.airItems.clear();
				for (JsonElement e : air.getAsJsonArray()) {
					if (!e.isJsonPrimitive()) continue;
					String raw = e.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
					if (raw.isEmpty()) continue;
					// the same normalisation the offers tab uses: a namespaceless entry
					// means minecraft:, or a hand-written "AIR" would never match
					ResourceLocation id = raw.indexOf(':') >= 0
							? McIds.tryParse(raw)
							: McIds.tryParse("minecraft:" + raw);
					if (id != null && !c.airItems.contains(id.toString())) c.airItems.add(id.toString());
				}
			}
			JsonObject specs = getSection(offers, "specs");
			if (specs != null) {
				c.tradeSpecs.clear();
				for (Map.Entry<String, JsonElement> entry : specs.entrySet()) {
					ResourceLocation id = McIds.tryParse(entry.getKey());
					if (id == null || !entry.getValue().isJsonObject()) continue;
					c.tradeSpecs.put(id, readOfferSpec(entry.getValue().getAsJsonObject()));
				}
			}
			JsonElement disposal = offers.get("nonBookDisposal");
			if (disposal != null && disposal.isJsonPrimitive()) {
				c.nonBookDisposal = readEnum(offers, "nonBookDisposal", c.nonBookDisposal);
			}
			JsonElement goods = offers.get("goods");
			if (goods != null && goods.isJsonArray()) {
				c.goods.clear();
				for (JsonElement e : goods.getAsJsonArray()) {
					if (!e.isJsonObject()) continue;
					JsonObject o = e.getAsJsonObject();
					JsonElement resultElement = o.get("result");
					ResourceLocation result = resultElement != null && resultElement.isJsonPrimitive()
							? McIds.tryParse(resultElement.getAsString())
							: null;
					if (result == null || result.equals(TradeOfferSpec.ENCHANTED_BOOK)) continue;
					// an air result can never be produced (an offer's air result has
					// count 0): accept it and the good becomes an unsatisfiable need
					if (c.isAirItem(result)) continue;
					if (c.goodFor(result) != null) continue;   // one good per result item
					// a good written with no costs would declare "pays nothing" and match
					// nothing: fall back to the good's default shape (emerald price slot,
					// no second cost), exactly like a good added from the offers tab
					List<TradeOfferSpec.TradeCost> costs = readCosts(o.get("costs"));
					if (costs.isEmpty()) costs = TradeOfferSpec.defaultGoodCosts();
					// normalised HERE, not only on write: a hand-written slot without an
					// item must become air immediately, or the GUI would NPE on it
					c.goods.add(new GoodSpec(result,
							Math.max(1, Math.min(64, readInt(o, "resultCount", 1))),
							readBool(o, "enabled", true),
							Math.max(0, Math.min(64, readInt(o, "maxPrice", 64))),
							costs).normalized());
				}
			}
		}

		JsonObject antiCheat = sectionOrRoot(root, "antiCheat");
		if (antiCheat != null) {
			c.legitMode = readBool(antiCheat, "legitMode", c.legitMode);
			c.slowMode = readBool(antiCheat, "slowMode", c.slowMode);
			// same coupling as the legacy flat path: slow mode without legit is meaningless
			if (c.slowMode) c.legitMode = true;
			c.tpToVillager = readBool(antiCheat, "tpToVillager", c.tpToVillager);
			c.tpToItem = readBool(antiCheat, "tpToItem", c.tpToItem);
			c.preventAxeBreaking = readBool(antiCheat, "preventAxeBreaking", c.preventAxeBreaking);
			c.stopOnPlayerMove = readBool(antiCheat, "stopOnPlayerMove", c.stopOnPlayerMove);
			c.placeDelay = readInt(antiCheat, "placeDelay", c.placeDelay);
			c.interactDelay = readInt(antiCheat, "interactDelay", c.interactDelay);
		}

		JsonObject enchantments = sectionOrRoot(root, "enchantments");
		if (enchantments != null) {
			// parse first, replace only when the file actually yielded targets: a
			// wrong-typed or absent section resolves to the flat root and finds nothing,
			// and clearing the whole table then would reset every enabled/level/price
			// choice to the registry defaults and persist that loss
			Map<ResourceLocation, EnchantmentTarget> parsed = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : enchantments.entrySet()) {
				// a flat-root fallback must not read its sibling sections as targets
				if (SECTION_NAMES.contains(entry.getKey())) continue;
				ResourceLocation id = McIds.tryParse(entry.getKey());
				if (id == null || !entry.getValue().isJsonObject()) continue;
				JsonObject o = entry.getValue().getAsJsonObject();
				// a note object (none of the target keys) is not an entry: skip it, or it
				// becomes a bogus target id
				if (!looksLikeTargetEntry(o)) continue;
				parsed.put(id, new EnchantmentTarget(
						id,
						readBool(o, "enabled", false),
						Math.max(1, Math.min(99, readInt(o, "level", 1))),
						Math.max(1, Math.min(64, readInt(o, "maxPrice", 64)))));
			}
			if (!parsed.isEmpty()) {
				c.enchantments.clear();
				c.enchantments.putAll(parsed);
			}
		}

		// hand-edited values must land in the same ranges the GUIs enforce
		c.minLockEmeralds = Math.min(Math.max(c.minLockEmeralds, 1), 64);
		c.timeoutTicks = Math.max(c.timeoutTicks, 1200);
		c.villagerCheckInterval = Math.min(Math.max(c.villagerCheckInterval, 1), 72000);
		c.lecternHealGrace = Math.min(Math.max(c.lecternHealGrace, 20), 1200);
		c.professionHealCap = Math.min(Math.max(c.professionHealCap, 1), 20);
		c.lockMaxRetries = Math.min(Math.max(c.lockMaxRetries, -1), 999);
		c.offhandSwapRetries = Math.min(Math.max(c.offhandSwapRetries, -1), 999);
		c.postDispatchBreakRetries = Math.min(Math.max(c.postDispatchBreakRetries, -1), 999);
		c.placeDelay = Math.min(Math.max(c.placeDelay, 0), 20);
		c.interactDelay = Math.min(Math.max(c.interactDelay, 0), 20);
		// an out-of-range drop direction is a typo: drop it (unset) instead of
		// wrapping, or cursed books would fly somewhere the user never aimed
		if (c.curseDropYaw != null && (c.curseDropYaw < -180f || c.curseDropYaw > 180f)) c.curseDropYaw = null;
		if (c.curseDropPitch != null && (c.curseDropPitch < -90f || c.curseDropPitch > 90f)) c.curseDropPitch = null;
		// the delta is a size bounded ±64 in both GUIs
		if (c.villagerRegionDelta != null
				&& (Math.abs(c.villagerRegionDelta.getX()) > 64
				|| Math.abs(c.villagerRegionDelta.getY()) > 64
				|| Math.abs(c.villagerRegionDelta.getZ()) > 64)) {
			c.villagerRegionDelta = null;
		}
		c.villagerSphereRadius = Math.max(1, Math.min(64, c.villagerSphereRadius));

		MaliLibAdapter.readHotkeys(sectionOrRoot(root, "hotkeys"));
	}

	/** Tolerates hand-edited files where a section has the wrong JSON type
	 * (e.g. "mechanism": []): skip the section instead of throwing and
	 * aborting every later section. */
	/** Serialises the per-target trade shapes plus the shared "air item" list. Slot
	 * positions are preserved (slot 2 stays slot 2), so hand-added slots 3..N survive
	 * a save and are matched positionally. */
	private static JsonObject writeOffers(TradeMasterConfig c, JsonObject root) {
		// update the section IN PLACE: a hand-written key inside it (a note, a key from
		// a newer version) must survive a save, exactly like the rest of the file
		JsonObject offers = getSection(root, "offers");
		if (offers == null) offers = new JsonObject();
		JsonArray air = new JsonArray();
		for (String item : c.airItems) {
			if (item != null && !item.isBlank()) air.add(item.trim());
		}
		offers.add("airItems", air);
		// seed each entry from the object already on disk so a hand-written key INSIDE
		// an entry survives too: the section-level merge above only protects keys
		// directly under "offers", and rebuilding these sub-objects from scratch used
		// to drop a note/forward-compat key nested one level deeper
		JsonObject oldSpecs = getSection(offers, "specs");
		JsonObject specs = new JsonObject();
		// seed with the on-disk keys the model does NOT own: a hand-written note (an object
		// carrying neither entry key) survives a save, exactly like the enchantments section.
		// A stale SPEC entry is dropped instead of kept: the reader would turn it back into a
		// target the model no longer has.
		if (oldSpecs != null) {
			for (Map.Entry<String, JsonElement> e : oldSpecs.entrySet()) {
				if (looksLikeSpecEntry(e.getValue())) continue;
				specs.add(e.getKey(), e.getValue());
			}
		}
		for (Map.Entry<ResourceLocation, TradeOfferSpec> entry : c.tradeSpecs.entrySet()) {
			TradeOfferSpec spec = entry.getValue().normalized();
			JsonObject o = entryObject(oldSpecs, entry.getKey());
			o.add("costs", writeCosts(spec.costs()));
			o.addProperty("result", spec.result().toString());
			specs.add(entry.getKey().toString(), o);
		}
		offers.add("specs", specs);

		JsonArray oldGoods = getArray(offers, "goods");
		JsonArray goods = new JsonArray();
		for (GoodSpec good : c.goods) {
			GoodSpec g = good.normalized();
			JsonObject o = goodEntryObject(oldGoods, g.result());
			o.addProperty("result", g.result().toString());
			o.addProperty("resultCount", g.resultCount());
			o.addProperty("enabled", g.enabled());
			o.addProperty("maxPrice", g.maxPrice());
			o.add("costs", writeCosts(g.costs()));
			goods.add(o);
		}
		offers.add("goods", goods);
		offers.addProperty("nonBookDisposal", c.nonBookDisposal.name());
		return offers;
	}

	/** Whether a JSON value looks like a spec entry (the writer always emits these keys), so a
	 * hand-written note can be told apart from an entry to preserve or drop. */
	private static boolean looksLikeSpecEntry(JsonElement value) {
		if (value == null || !value.isJsonObject()) return false;
		JsonObject o = value.getAsJsonObject();
		return o.has("costs") || o.has("result");
	}

	private static JsonArray writeCosts(List<TradeOfferSpec.TradeCost> costs) {
		JsonArray array = new JsonArray();
		for (TradeOfferSpec.TradeCost cost : costs) {
			JsonObject o = new JsonObject();
			o.addProperty("item", cost.item().toString());
			o.addProperty("count", cost.count());
			array.add(o);
		}
		return array;
	}

	/** Reads a cost slot list. A slot may be a plain item string ("minecraft:book",
	 * meaning one) or {item, count}; a slot whose item is missing or unparsable becomes
	 * air, i.e. "not required". Positions are never dropped or reordered. */
	private static List<TradeOfferSpec.TradeCost> readCosts(@Nullable JsonElement element) {
		List<TradeOfferSpec.TradeCost> costs = new ArrayList<>();
		if (element == null || !element.isJsonArray()) return costs;
		for (JsonElement e : element.getAsJsonArray()) {
			ResourceLocation item = null;
			int count = 0;
			if (e.isJsonPrimitive()) {
				item = McIds.tryParse(e.getAsString());
				count = 1;
			} else if (e.isJsonObject()) {
				JsonObject co = e.getAsJsonObject();
				JsonElement itemElement = co.get("item");
				if (itemElement != null && itemElement.isJsonPrimitive()) {
					item = McIds.tryParse(itemElement.getAsString());
				}
				count = readInt(co, "count", 1);
			}
			costs.add(new TradeOfferSpec.TradeCost(item, count));
		}
		return costs;
	}

	/** Reads one spec. A slot may be written as a plain item string ("minecraft:book",
	 * meaning one) or as {item, count}; a slot whose item is missing or unparsable
	 * becomes air, i.e. "not required". Positions are never dropped or reordered.
	 * A spec written with NO costs at all is a mistake that would declare "pays
	 * nothing" and match nothing: it falls back to the default shape, exactly as an
	 * absent spec does. */
	private static TradeOfferSpec readOfferSpec(JsonObject o) {
		List<TradeOfferSpec.TradeCost> costs = readCosts(o.get("costs"));
		if (costs.isEmpty()) costs = TradeOfferSpec.defaults().costs();
		ResourceLocation result = TradeOfferSpec.ENCHANTED_BOOK;
		JsonElement resultElement = o.get("result");
		if (resultElement != null && resultElement.isJsonPrimitive()) {
			ResourceLocation parsed = McIds.tryParse(resultElement.getAsString());
			if (parsed != null) result = parsed;
		}
		return new TradeOfferSpec(List.copyOf(costs), result).normalized();
	}

	/** A named section, or the root itself for a file that predates sections (the
	 * early flat layout wrote the same keys at the top level, and the reads fall back
	 * to their defaults when a key is absent either way). */
	private static JsonObject sectionOrRoot(JsonObject root, String name) {
		JsonObject section = getSection(root, name);
		return section != null ? section : root;
	}

	private static @Nullable JsonObject getSection(JsonObject root, String key) {
		JsonElement e = root.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static @Nullable JsonArray getArray(JsonObject root, String key) {
		JsonElement e = root.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
	}

	/** The named section already on disk, reused so hand-written keys this version does
	 * not know about survive a save; a fresh object when there is none (or it has the
	 * wrong type). Mirrors how the root itself is seeded from disk. */
	private static JsonObject sectionForWrite(JsonObject root, String name) {
		JsonObject existing = getSection(root, name);
		return existing != null ? existing : new JsonObject();
	}

	/** Whether a JSON value looks like a target entry (the writer always emits these
	 * keys), so a non-model key can be told apart from a hand-written note object. */
	private static boolean looksLikeTargetEntry(JsonElement value) {
		if (value == null || !value.isJsonObject()) return false;
		JsonObject o = value.getAsJsonObject();
		return o.has("enabled") || o.has("level") || o.has("maxPrice");
	}

	/** Reads an id stored as a string, normalising it the way the GUI writes it
	 * (lowercase; a namespaceless value means minecraft:). An unparsable/absent value
	 * keeps the current one rather than blanking it. */
	private static String readIdString(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		String raw = e.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
		if (raw.isEmpty()) return fallback;
		ResourceLocation id = raw.indexOf(':') >= 0 ? McIds.tryParse(raw) : McIds.tryParse("minecraft:" + raw);
		return id == null ? fallback : id.toString();
	}

	/** The object already stored for {@code id} under {@code specs}, so a hand-written
	 * key this version does not know about survives the save; a fresh object otherwise.
	 * Matching is by PARSED id: a namespaceless hand-written key ("sharpness") loads as
	 * minecraft:sharpness, so a raw-string lookup would miss it. When two disk keys parse
	 * to the same id the LAST wins, exactly as the reader's map put overwrites. */
	private static JsonObject entryObject(@Nullable JsonObject specs, ResourceLocation id) {
		JsonObject found = null;
		if (specs != null) {
			for (Map.Entry<String, JsonElement> e : specs.entrySet()) {
				if (!e.getValue().isJsonObject()) continue;
				ResourceLocation parsed = McIds.tryParse(e.getKey());
				if (parsed != null && parsed.equals(id)) found = e.getValue().getAsJsonObject();
			}
		}
		return found != null ? found : new JsonObject();
	}

	/** The stored goods entry whose result is {@code result}: goods are identified by
	 * their result item, so that is the key a hand-written extra key hangs off. Matched
	 * by parsed id, for the same namespaceless reason as {@link #entryObject}. */
	private static JsonObject goodEntryObject(@Nullable JsonArray goods, ResourceLocation result) {
		if (goods != null) {
			for (JsonElement e : goods) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				JsonElement r = o.get("result");
				if (r == null || !r.isJsonPrimitive()) continue;
				ResourceLocation parsed = McIds.tryParse(r.getAsString());
				if (parsed != null && parsed.equals(result)) return o;
			}
		}
		return new JsonObject();
	}

	private static <E extends Enum<E>> E readEnum(JsonObject o, String key, E fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		try {
			return Enum.valueOf(fallback.getDeclaringClass(), e.getAsString());
		} catch (IllegalArgumentException ex) {
			return fallback;
		}
	}

	private static boolean readBool(JsonObject o, String key, boolean fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		JsonPrimitive p = e.getAsJsonPrimitive();
		if (p.isBoolean()) return p.getAsBoolean();
		// a number or a random string must not silently coerce a value
		if (p.isString()) {
			String s = p.getAsString();
			if (s.equalsIgnoreCase("true")) return true;
			if (s.equalsIgnoreCase("false")) return false;
		}
		return fallback;
	}

	private static int readInt(JsonObject o, String key, int fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		try {
			return e.getAsInt();
		} catch (RuntimeException ex) {
			// a malformed value (e.g. "3" in quotes) must fall back instead of
			// aborting the whole load and resetting every later section
			return fallback;
		}
	}

	private static @Nullable Float readFloat(@Nullable JsonElement e) {
		if (e == null || !e.isJsonPrimitive()) return null;
		try {
			return e.getAsFloat();
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/** Reads a tick count stored as a number or as a duration string like "1h30min". */
	private static int readDuration(JsonObject o, String key, int fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		try {
			if (e.getAsJsonPrimitive().isNumber()) {
				int value = e.getAsInt();
				return value > 0 ? value : fallback;
			}
			int parsed = DurationParser.parse(e.getAsString());
			return parsed > 0 ? parsed : fallback;
		} catch (RuntimeException ex) {
			return fallback;
		}
	}

	private static @Nullable JsonElement writePos(@Nullable BlockPos pos) {
		if (pos == null) return com.google.gson.JsonNull.INSTANCE;
		JsonArray arr = new JsonArray();
		arr.add(pos.getX());
		arr.add(pos.getY());
		arr.add(pos.getZ());
		return arr;
	}

	private static @Nullable BlockPos readPos(@Nullable JsonElement e) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != 3) return null;
		JsonArray arr = e.getAsJsonArray();
		try {
			int x = arr.get(0).getAsInt();
			int y = arr.get(1).getAsInt();
			int z = arr.get(2).getAsInt();
			// sanity bounds mirroring the GUIs; no level is available at load
			// time, so y is checked against the widest vanilla build range
			if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) return null;
			if (y < -2048 || y > 2047) return null;
			return new BlockPos(x, y, z);
		} catch (RuntimeException ex) {
			return null;
		}
	}
}
