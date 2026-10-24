package dev.trademaster.config;

import dev.trademaster.compat.McIds;
import dev.trademaster.compat.McEnchantTags;
import dev.trademaster.compat.McRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
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
	/** Treat the offer's BASE price (before discounts/demand) as its sale price for
	 * matching, budgets and messages. Reading base is the historic behaviour; turn it
	 * off to judge by the actually charged cost ({@code getCostA}). */
	public boolean basePriceAsSalePrice = true;
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
	public final LinkedHashMap<ResourceLocation, String> customEnchantNames = new LinkedHashMap<>();
	/** Display names actually used at naming time, mapped back to their enchantment.
	 * Keeps named-villager deduction working across game-language switches. */
	public final LinkedHashMap<String, ResourceLocation> nameHistory = new LinkedHashMap<>();

	// ---- trade offers: unusual cost shapes ---------------------------------
	/** Item ids that mean "this cost slot is not charged at all". Applied to BOTH
	 * sides: a slot the OFFER fills with one of these counts as not charged, and the
	 * same id (or an empty field) in a spec means "this slot is not required". */
	public final List<String> airItems = new ArrayList<>(List.of("minecraft:air"));

	/** Per-target trade shape. Absent = {@link TradeOfferSpec#defaults()}, i.e. exactly
	 * the emerald + book trade the mod required before this feature existed. */
	public final LinkedHashMap<ResourceLocation, TradeOfferSpec> tradeSpecs = new LinkedHashMap<>();

	/** Whether an item id counts as "air" (not charged). A null id (an item that could
	 * not be parsed) also counts as air, so a spec field left empty means "not required". */
	public boolean isAirItem(@Nullable ResourceLocation id) {
		if (id == null) return true;
		String text = id.toString();
		// the canonical sentinel is ALWAYS air, even if the user emptied the list:
		// specs and the "switch a row off" action write it and must stay effective
		if ("minecraft:air".equals(text)) return true;
		for (String air : this.airItems) {
			if (air == null || air.isBlank()) continue;
			if (air.trim().equalsIgnoreCase(text)) return true;
		}
		return false;
	}

	/** The spec for a target, or the default (emerald + book ×1) when none was set. */
	public TradeOfferSpec specFor(ResourceLocation id) {
		TradeOfferSpec spec = this.tradeSpecs.get(id);
		return spec == null ? TradeOfferSpec.defaults() : spec;
	}

	/** Custom goods to reroll for: offers whose result is not an enchanted book.
	 * Identified by their result item, so `goodFor` is a plain list scan. */
	public final List<GoodSpec> goods = new ArrayList<>();

	/** What happens to a bought good when every bound chest refuses it: stop with a
	 * message, or drop it facing the configured drop direction. */
	public NonBookDisposal nonBookDisposal = NonBookDisposal.CHEST_THEN_STOP;

	public List<GoodSpec> enabledGoods() {
		List<GoodSpec> result = new ArrayList<>();
		for (GoodSpec good : this.goods) {
			if (good.enabled()) result.add(good);
		}
		return result;
	}

	/** The good whose result item is {@code id}, or null. */
	public @Nullable GoodSpec goodFor(ResourceLocation id) {
		for (GoodSpec good : this.goods) {
			if (good.id().equals(id)) return good;
		}
		return null;
	}

	// positions (nullable until picked/configured)
	public @Nullable BlockPos lecternPos = null;
	public @Nullable BlockPos summonButtonPos = null;
	public @Nullable BlockPos sendAwayButtonPos = null;
	/** The minecart dispenser checked before each summon (null = skip the check). A
	 * dispenser's contents can only be read by opening it, so the check briefly opens and
	 * closes this block. */
	public @Nullable BlockPos dispenserPos = null;
	/** Overflow chests for enchanted books when the inventory is full; tried in order. */
	public final List<BlockPos> chestPositions = new ArrayList<>();

	/** Upper bound for bound chests: a hand-edited file with thousands of entries
	 * would otherwise be read, kept and rewritten in full on every save. */
	public static final int MAX_CHESTS = 64;

	/** Upper bound for the non-tradeable profession list: {@code isNonTradeable} scans it on
	 * hot paths, so a hand-edited file with thousands of entries must not be read in full. */
	public static final int MAX_NON_TRADEABLE = 64;

	/** Adds a chest unless it is already bound or the list is full. */
	public boolean addChest(BlockPos pos) {
		if (pos == null || this.chestPositions.size() >= MAX_CHESTS) return false;
		if (this.chestPositions.contains(pos)) return false;
		return this.chestPositions.add(pos);
	}

	// villagers (the rig's job-site block and the profession it should take)
	/** The job-site block the rig breaks / re-places / restocks. An id; a namespaceless
	 * value means minecraft: (resolved by {@code RigTarget}, which falls back to the
	 * lectern when the id is unknown). */
	public String workBlockId = "minecraft:lectern";
	/** The profession the rig's villager should take. The matching / naming / deduction
	 * still assume the profession SELLS ENCHANTED BOOKS — only the id is configurable. */
	public String professionId = "minecraft:librarian";
	/** Profession ids that can never sell a trade, beyond the built-in nitwit. A villager
	 * holding one is handled like a nitwit (dispatched as a blocker), and the configured
	 * target profession may not be one of them. Ids are stored normalised
	 * (namespaceless → minecraft:), like the air-item list. */
	public final List<String> nonTradeableProfessions = new ArrayList<>();

	/** {@link #workBlockId} as an id (namespaceless → minecraft:), or null. */
	public @Nullable ResourceLocation workBlockIdentifier() {
		return parseId(this.workBlockId);
	}

	/** {@link #professionId} as an id (namespaceless → minecraft:), or null. */
	public @Nullable ResourceLocation professionIdentifier() {
		return parseId(this.professionId);
	}

	/** Parses a config id: namespaceless means {@code minecraft:}, a blank or unparsable
	 * value means null. Shared with the Villagers tab's profession list. */
	public static @Nullable ResourceLocation parseId(String raw) {
		if (raw == null) return null;
		String text = raw.trim().toLowerCase(java.util.Locale.ROOT);
		if (text.isEmpty()) return null;
		return McIds.tryParse(text.indexOf(':') >= 0 ? text : "minecraft:" + text);
	}

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
	public final LinkedHashMap<ResourceLocation, EnchantmentTarget> enchantments = new LinkedHashMap<>();

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
		LinkedHashMap<ResourceLocation, EnchantmentTarget> merged = new LinkedHashMap<>();
		List<ResourceLocation> ids = new ArrayList<>();
		for (ResourceLocation id : registry.keySet()) {
			if (!McEnchantTags.isTradeable(registry, id)) continue;
			ids.add(id);
		}
		ids.sort(ResourceLocation::compareTo);
		for (ResourceLocation id : ids) {
			EnchantmentTarget existing = this.enchantments.get(id);
			Enchantment enchantment = McRegistry.value(registry, id);
			if (enchantment == null) continue;
			if (existing != null) {
				int maxLevel = enchantment.getMaxLevel();
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
	public static int minPrice(int level, ResourceLocation id) {
		int min = minPrice(level);
		return isDoubleTradePrice(id) ? min * 2 : min;
	}

	/** Last known #minecraft:double_trade_price ids, used while client.level is null. */
	private static java.util.Set<ResourceLocation> DOUBLE_TRADE_PRICE_CACHE = java.util.Set.of();
	private static Registry<Enchantment> doublePriceCacheSource = null;

	public static boolean isDoubleTradePrice(ResourceLocation id) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			// main menu / before join: fall back to the last known tag membership
			return DOUBLE_TRADE_PRICE_CACHE.contains(id);
		}
		Registry<Enchantment> registry = McRegistry.enchantments(client.level);
		if (doublePriceCacheSource != registry) {
			var cache = new java.util.LinkedHashSet<ResourceLocation>();
			for (ResourceLocation candidate : registry.keySet()) {
				if (McEnchantTags.isDoubleTradePrice(registry, candidate)) {
					cache.add(candidate);
				}
			}
			DOUBLE_TRADE_PRICE_CACHE = cache;
			doublePriceCacheSource = registry;
		}
		return DOUBLE_TRADE_PRICE_CACHE.contains(id);
	}
}
