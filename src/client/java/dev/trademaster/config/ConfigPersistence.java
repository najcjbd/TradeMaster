package dev.trademaster.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.trademaster.TradeMaster;
import dev.trademaster.config.MaliLibAdapter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class ConfigPersistence {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int CONFIG_VERSION = 1;

	private ConfigPersistence() {}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("trade-master.json");
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
					TradeMaster.LOGGER.error("Failed to recover trade-master.json.tmp", e);
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
			TradeMaster.LOGGER.error("Failed to load trade-master.json", e);
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
				TradeMaster.LOGGER.error("Too many quarantined trade-master.json copies, leaving the broken file in place");
			} else {
				try {
					Files.move(file, quarantine);
					TradeMaster.LOGGER.error("Quarantined broken config as {}", quarantine.getFileName());
				} catch (IOException io) {
					// the move failed but the file still holds salvageable content:
					// copy it aside so the next save cannot destroy it
					try {
						Files.copy(file, quarantine, StandardCopyOption.REPLACE_EXISTING);
						TradeMaster.LOGGER.error("Copied broken config to {} (move failed)", quarantine.getFileName());
					} catch (IOException copyError) {
						TradeMaster.LOGGER.error("Failed to quarantine broken trade-master.json", copyError);
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
		Path file = FabricLoader.getInstance().getConfigDir().resolve("librarian-trade-finder.json");
		if (!Files.isRegularFile(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
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
					Identifier id = Identifier.tryParse(entry.getKey());
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
			save(c);
			TradeMaster.LOGGER.info("Imported settings from librarian-trade-finder.json ({} enchantment targets)", imported);
		} catch (Exception e) {
			TradeMaster.LOGGER.error("Failed to import librarian-trade-finder.json", e);
		}
	}

	public static void save(TradeMasterConfig config) {
		JsonObject root = new JsonObject();
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
			TradeMaster.LOGGER.error("Failed to save trade-master.json", e);
		}
	}

	private static void writeFrom(TradeMasterConfig c, JsonObject root) {
		root.addProperty("configVersion", CONFIG_VERSION);

		JsonObject mechanism = new JsonObject();
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

		JsonObject naming = new JsonObject();
		naming.addProperty("enabled", c.namingEnabled);
		naming.addProperty("forceNamePriceOne", c.forceNamePriceOne);
		JsonObject customNames = new JsonObject();
		for (Map.Entry<Identifier, String> entry : c.customEnchantNames.entrySet()) {
			customNames.addProperty(entry.getKey().toString(), entry.getValue());
		}
		naming.add("customNames", customNames);
		JsonObject nameHistory = new JsonObject();
		for (Map.Entry<String, Identifier> entry : c.nameHistory.entrySet()) {
			nameHistory.addProperty(entry.getKey(), entry.getValue().toString());
		}
		naming.add("nameHistory", nameHistory);
		root.add("naming", naming);

		JsonObject positions = new JsonObject();
		positions.add("lectern", writePos(c.lecternPos));
		positions.add("summonButton", writePos(c.summonButtonPos));
		positions.add("sendAwayButton", writePos(c.sendAwayButtonPos));
		positions.add("villagerRegionOrigin", writePos(c.villagerRegionOrigin));
		positions.add("villagerRegionDelta", writePos(c.villagerRegionDelta));
		positions.addProperty("villagerSphereRadius", c.villagerSphereRadius);
		JsonArray chests = new JsonArray();
		for (net.minecraft.core.BlockPos chest : c.chestPositions) {
			chests.add(writePos(chest));
		}
		positions.add("chests", chests);
		root.add("positions", positions);

		JsonObject grindstone = new JsonObject();
		grindstone.addProperty("enabled", c.grindstoneEnabled);
		grindstone.add("pos", writePos(c.grindstonePos));
		grindstone.addProperty("openRetries", c.grindstoneOpenRetries);
		if (c.curseDropYaw != null) grindstone.addProperty("curseDropYaw", c.curseDropYaw);
		if (c.curseDropPitch != null) grindstone.addProperty("curseDropPitch", c.curseDropPitch);
		JsonArray customCurses = new JsonArray();
		for (String curse : c.customCurseEnchantments) {
			customCurses.add(curse);
		}
		grindstone.add("customCurses", customCurses);
		root.add("grindstone", grindstone);

		JsonObject antiCheat = new JsonObject();
		antiCheat.addProperty("legitMode", c.legitMode);
		antiCheat.addProperty("slowMode", c.slowMode);
		antiCheat.addProperty("tpToVillager", c.tpToVillager);
		antiCheat.addProperty("tpToItem", c.tpToItem);
		antiCheat.addProperty("preventAxeBreaking", c.preventAxeBreaking);
		antiCheat.addProperty("stopOnPlayerMove", c.stopOnPlayerMove);
		antiCheat.addProperty("placeDelay", c.placeDelay);
		antiCheat.addProperty("interactDelay", c.interactDelay);
		root.add("antiCheat", antiCheat);

		JsonObject enchantments = new JsonObject();
		for (Map.Entry<Identifier, EnchantmentTarget> entry : c.enchantments.entrySet()) {
			EnchantmentTarget t = entry.getValue();
			JsonObject o = new JsonObject();
			o.addProperty("enabled", t.enabled());
			o.addProperty("level", t.level());
			o.addProperty("maxPrice", t.maxPrice());
			enchantments.add(entry.getKey().toString(), o);
		}
		root.add("enchantments", enchantments);

		root.add("hotkeys", MaliLibAdapter.writeHotkeys());
	}

	private static void readInto(TradeMasterConfig c, JsonObject root) {
		JsonObject mechanism = getSection(root, "mechanism");
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

		JsonObject naming = getSection(root, "naming");
		if (naming != null) {
			c.namingEnabled = readBool(naming, "enabled", c.namingEnabled);
			c.forceNamePriceOne = readBool(naming, "forceNamePriceOne", c.forceNamePriceOne);
			JsonObject customNames = getSection(naming, "customNames");
			// only clear when the file actually carries the section: a partial
			// file without it must keep the in-memory values
			if (customNames != null) {
				c.customEnchantNames.clear();
				for (Map.Entry<String, JsonElement> entry : customNames.entrySet()) {
					Identifier id = Identifier.tryParse(entry.getKey());
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
					Identifier id = Identifier.tryParse(entry.getValue().getAsString());
					if (id != null) c.nameHistory.put(name, id);
				}
				// a hand-edited file may exceed the cap: keep the newest entries
				while (c.nameHistory.size() > 256) {
					c.nameHistory.remove(c.nameHistory.keySet().iterator().next());
				}
			}
		}

		JsonObject positions = getSection(root, "positions");
		if (positions != null) {
			c.lecternPos = readPos(positions.get("lectern"));
			c.summonButtonPos = readPos(positions.get("summonButton"));
			c.sendAwayButtonPos = readPos(positions.get("sendAwayButton"));
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
					BlockPos pos = readPos(e);
					if (pos != null) c.chestPositions.add(pos);
				}
			}
		}

		JsonObject grindstone = getSection(root, "grindstone");
		if (grindstone != null) {
			c.grindstoneEnabled = readBool(grindstone, "enabled", c.grindstoneEnabled);
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
					Identifier id = Identifier.tryParse(e.getAsString());
					// vanilla ids are forbidden: vanilla curses are detected via
					// the curse tag, a hand-entered one could never add anything
					if (id != null && !"minecraft".equals(id.getNamespace())) {
						c.customCurseEnchantments.add(id.toString());
					}
				}
			}
		}

		JsonObject antiCheat = getSection(root, "antiCheat");
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

		JsonObject enchantments = getSection(root, "enchantments");
		if (enchantments != null) {
			c.enchantments.clear();
			for (Map.Entry<String, JsonElement> entry : enchantments.entrySet()) {
				Identifier id = Identifier.tryParse(entry.getKey());
				if (id == null || !entry.getValue().isJsonObject()) continue;
				JsonObject o = entry.getValue().getAsJsonObject();
				c.enchantments.put(id, new EnchantmentTarget(
						id,
						readBool(o, "enabled", false),
						Math.max(1, Math.min(99, readInt(o, "level", 1))),
						Math.max(1, Math.min(64, readInt(o, "maxPrice", 64)))));
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

		MaliLibAdapter.readHotkeys(getSection(root, "hotkeys"));
	}

	/** Tolerates hand-edited files where a section has the wrong JSON type
	 * (e.g. "mechanism": []): skip the section instead of throwing and
	 * aborting every later section. */
	private static @Nullable JsonObject getSection(JsonObject root, String key) {
		JsonElement e = root.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
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
