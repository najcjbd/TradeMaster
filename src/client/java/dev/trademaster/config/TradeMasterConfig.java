package dev.trademaster.config;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.enchantment.Enchantment;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Single source of truth for all settings. Every entry point (in-game main
 * menu, ModMenu, maLiLib GUI) reads and writes this model; the maLiLib
 * adapter is only a view on top of it.
 */
public final class TradeMasterConfig {

	public static final TradeMasterConfig INSTANCE = new TradeMasterConfig();

	// mechanism
	public RerollMechanism rerollMechanism = RerollMechanism.AXE;
	public DetectionMode detectionMode = DetectionMode.PACKET;

	// matching / locking
	public boolean reserveCheaperTraders = false;
	public int minLockEmeralds = 1;
	public boolean timeoutEnabled = false;
	public int timeoutTicks = 20 * 60 * 60;
	/** Per-phase safety timeouts (profession/detection/execution). Default on. */
	public boolean phaseTimeoutsEnabled = true;
	/** Ticks between villager presence scans while resting in AWAIT_VILLAGER. */
	public int villagerCheckInterval = 20;
	/** Grace before the lectern/profession self-heal fires (ticks). Default 6s. */
	public int lecternHealGrace = 20 * 6;
	/** Opt-in: dispatch a villager that still has no profession after the heal cap. */
	public boolean professionHealCapEnabled = false;
	/** Max lectern refreshes per villager while waiting for the profession. */
	public int professionHealCap = 3;
	/** Detection region: origin + (dx,dy,dz) extension. Both required for staging waits. */
	public @Nullable BlockPos villagerRegionOrigin = null;
	public @Nullable BlockPos villagerRegionDelta = null;
	/** Staging-area fallback: villagers within this radius of the lectern count as
	 * reachable by the minecart when the region coords are not configured. */
	public int villagerSphereRadius = 8;
	public boolean autoDisableAfterLock = false;
	/** Hunt-cheaper mode: a locked enchantment stays live and is re-rolled until a
	 * strictly cheaper copy appears. Mutually exclusive with autoDisableAfterLock;
	 * when both are somehow set, autoDisableAfterLock wins. */
	public boolean huntCheaper = false;
	/** Max lock purchase retries after a server rejection; -1 retries forever. */
	public int lockMaxRetries = -1;
	/** Max post-dispatch lectern-break retries before the run stops; -1 retries
	 * forever. The break must eventually succeed, but a block that can never be
	 * broken (swapped out, out of reach) must not hang the run silently. */
	public int postDispatchBreakRetries = 2;
	/** Max offhand-swap retries per chest when the server rejects the swap and
	 * the chest still has space (deposit); -1 retries forever. */
	public int offhandSwapRetries = 3;
	/** Opt-in for modified librarian professions (datapacks that put several
	 * enchantments on one book): the start precheck then demands 64 emeralds
	 * (the hard price cap) so any rolled book stays payable. */
	public boolean multiEnchantProtection = false;
	public boolean displayRolls = true;
	public boolean debugLog = false;

	// naming
	public boolean namingEnabled = false;
	public boolean forceNamePriceOne = false;
	public final LinkedHashMap<Identifier, String> customEnchantNames = new LinkedHashMap<>();
	/** Display names actually used at naming time, mapped back to their enchantment.
	 * Keeps named-villager deduction working across game-language switches. */
	public final LinkedHashMap<String, Identifier> nameHistory = new LinkedHashMap<>();

	// positions (nullable until picked/configured)
	public @Nullable BlockPos lecternPos = null;
	public @Nullable BlockPos summonButtonPos = null;
	public @Nullable BlockPos sendAwayButtonPos = null;
	/** Overflow chests for enchanted books when the inventory is full; tried in order. */
	public final List<BlockPos> chestPositions = new ArrayList<>();

	// grindstone recycling (replaces the chest deposit steps when fully configured)
	public boolean grindstoneEnabled = true;
	public @Nullable BlockPos grindstonePos = null;
	/** Max retries when the grindstone menu never opens (laggy servers); -1 retries forever. */
	public int grindstoneOpenRetries = 1;
	/** Direction cursed books are dropped in when no chest can take them.
	 * Yaw: -180=N, -90=E, 0=S, 90=W; pitch: positive looks down. */
	public @Nullable Float curseDropYaw = null;
	public @Nullable Float curseDropPitch = null;
	/** Mod-added curse enchantment ids not covered by the vanilla curse tag;
	 * minecraft: ids are rejected everywhere (vanilla curses are tag-detected). */
	public final java.util.LinkedHashSet<String> customCurseEnchantments = new java.util.LinkedHashSet<>();

	// anti-cheat
	public boolean legitMode = true;
	public boolean slowMode = false;
	public boolean tpToVillager = false;
	public boolean tpToItem = false;
	public boolean preventAxeBreaking = true;
	public boolean stopOnPlayerMove = true;
	public int placeDelay = 3;
	public int interactDelay = 2;

	// targets, keyed by enchantment id; only #minecraft:tradeable entries
	public final LinkedHashMap<Identifier, EnchantmentTarget> enchantments = new LinkedHashMap<>();

	private TradeMasterConfig() {}

	/** True between beginBatch/endBatch: save() is deferred to the end of the batch. */
	private boolean batchSave = false;

	public void beginBatch() {
		this.batchSave = true;
	}

	public void endBatch() {
		this.batchSave = false;
		save();
	}

	public void save() {
		if (this.batchSave) return;
		ConfigPersistence.save(this);
	}

	/**
	 * Merges stored targets with the live enchantment registry (like LTF does
	 * on join): new tradeable enchantments are added disabled, existing entries
	 * keep their settings, and stored entries that are currently unknown or
	 * untradeable are kept so the user's level/price choices survive re-joins.
	 */
	public void syncEnchantmentsFromRegistry(Registry<Enchantment> registry) {
		LinkedHashMap<Identifier, EnchantmentTarget> merged = new LinkedHashMap<>();
		List<Identifier> ids = new ArrayList<>();
		for (Identifier id : registry.keySet()) {
			Holder<Enchantment> holder = registry.get(id).orElse(null);
			if (holder == null || !holder.is(EnchantmentTags.TRADEABLE)) continue;
			ids.add(id);
		}
		ids.sort(Identifier::compareTo);
		for (Identifier id : ids) {
			EnchantmentTarget existing = this.enchantments.get(id);
			if (existing != null) {
				int maxLevel = registry.getValue(id).getMaxLevel();
				// 99 cap mirrors the file-load clamp and the 2-digit GUI field:
				// a stored >=100 level truncates on display and the reconciliation
				// responder would silently persist the truncated value
				int level = Math.max(1, Math.min(existing.level(), Math.min(maxLevel, 99)));
				// a hand-edited maxPrice below the vanilla minimum for the level can
				// never match: raise it instead of leaving a silently dead target
				// (capped at 64 like the GUI clamp, even when minPrice exceeds it)
				int price = Math.min(64, Math.max(Math.min(existing.maxPrice(), 64), minPrice(level, id)));
				merged.put(id, existing.withLevel(level).withMaxPrice(price));
			} else {
				Enchantment enchantment = registry.getValue(id);
				merged.put(id, new EnchantmentTarget(id, false, Math.min(enchantment.getMaxLevel(), 99), 64));
			}
		}
		for (var stored : this.enchantments.entrySet()) {
			// lost its TRADEABLE tag (datapack/mod change): an enabled entry could
			// never produce its trade and would reroll forever — disable it, but
			// keep the entry so the user's level/price choices survive
			EnchantmentTarget target = stored.getValue();
			merged.putIfAbsent(stored.getKey(), target.enabled() ? target.withEnabled(false) : target);
		}
		this.enchantments.clear();
		this.enchantments.putAll(merged);
	}

	public int emeraldFloor() {
		return emeraldFloor(enabledTargets());
	}

	public int emeraldFloor(List<EnchantmentTarget> enabled) {
		int max = 0;
		for (EnchantmentTarget target : enabled) {
			if (target.maxPrice() > max) {
				max = target.maxPrice();
			}
		}
		return Math.max(max, this.minLockEmeralds);
	}

	public List<EnchantmentTarget> enabledTargets() {
		List<EnchantmentTarget> result = new ArrayList<>();
		for (EnchantmentTarget target : this.enchantments.values()) {
			if (target.enabled()) {
				result.add(target);
			}
		}
		return result;
	}

	/** Vanilla minimum price formula: 2 + 3 * level. */
	public static int minPrice(int level) {
		return 2 + 3 * level;
	}

	/**
	 * Vanilla minimum price for a specific enchantment: #minecraft:double_trade_price
	 * entries (the tradeable treasures: mending, frost walker, both curses) cost double.
	 * The book part of the trade is unaffected and always costs 1.
	 */
	public static int minPrice(int level, Identifier id) {
		int min = minPrice(level);
		return isDoubleTradePrice(id) ? min * 2 : min;
	}

	/** Last known #minecraft:double_trade_price ids, used while client.level is null. */
	private static java.util.Set<Identifier> DOUBLE_TRADE_PRICE_CACHE = java.util.Set.of();
	private static Registry<Enchantment> doublePriceCacheSource = null;

	public static boolean isDoubleTradePrice(Identifier id) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			// main menu / before join: fall back to the last known tag membership
			return DOUBLE_TRADE_PRICE_CACHE.contains(id);
		}
		var registry = client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		if (doublePriceCacheSource != registry) {
			var cache = new java.util.LinkedHashSet<Identifier>();
			for (Identifier candidate : registry.keySet()) {
				if (registry.get(candidate).map(h -> h.is(EnchantmentTags.DOUBLE_TRADE_PRICE)).orElse(false)) {
					cache.add(candidate);
				}
			}
			DOUBLE_TRADE_PRICE_CACHE = cache;
			doublePriceCacheSource = registry;
		}
		return DOUBLE_TRADE_PRICE_CACHE.contains(id);
	}
}
