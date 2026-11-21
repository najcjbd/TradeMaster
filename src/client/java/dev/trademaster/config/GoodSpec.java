package dev.trademaster.config;

import dev.trademaster.compat.McNbt;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A custom good: a stable key plus the result item and optional NAME / ENCHANTMENT /
 * RAW-NBT include and exclude filters.
 *
 * <p>Include filters are subset filters (configured fields must match, extras allowed);
 * exclude filters invert that (the live item must not contain them). An enchantment
 * level of 0 means absence (no such enchantment); {@code 1..} means any present level,
 * {@code N} exactly N and {@code N..M} the inclusive range.
 */
public record GoodSpec(Identifier key, Identifier result, int resultCount,
		boolean enabled, int maxPrice, List<TradeOfferSpec.TradeCost> costs,
		String filterName, List<GoodEnchant> filterEnchantments, String filterNbt,
		List<GoodEnchant> excludedEnchantments, String filterNbtExclude,
		boolean filterInvalid, String rawIncludeEnchants, String rawExcludeEnchants) {

	public record GoodEnchant(Identifier id, int minLevel, int maxLevel) {
		public GoodEnchant {
			minLevel = Math.max(0, Math.min(99, minLevel));
			maxLevel = Math.max(0, Math.min(99, maxLevel));
			if (minLevel == 0 && maxLevel == 0) {
				// absence
			} else if (minLevel == 0) {
				minLevel = 1;
			} else if (maxLevel > 0 && maxLevel < minLevel) {
				maxLevel = minLevel;
			}
		}

		public GoodEnchant(Identifier id, int level) {
			this(id, level <= 0 ? 0 : level, level <= 0 ? 0 : level);
		}

		public static GoodEnchant present(Identifier id) {
			return new GoodEnchant(id, 1, 0);
		}

		public static GoodEnchant absent(Identifier id) {
			return new GoodEnchant(id, 0, 0);
		}

		public static GoodEnchant exact(Identifier id, int level) {
			return new GoodEnchant(id, level, level);
		}

		public static GoodEnchant range(Identifier id, int min, int max) {
			return new GoodEnchant(id, min, max);
		}

		public boolean absent() {
			return this.minLevel == 0 && this.maxLevel == 0;
		}

		public boolean levelMatches(int liveLevel) {
			if (absent()) return liveLevel == 0;
			if (liveLevel <= 0 || liveLevel < this.minLevel) return false;
			return this.maxLevel == 0 || liveLevel <= this.maxLevel;
		}
	}

	public GoodSpec {
		if (key == null) key = result;
		filterName = filterName == null ? "" : filterName.trim();
		filterNbt = filterNbt == null ? "" : filterNbt.trim();
		filterNbtExclude = filterNbtExclude == null ? "" : filterNbtExclude.trim();
		filterEnchantments = filterEnchantments == null ? List.of() : List.copyOf(filterEnchantments);
		excludedEnchantments = excludedEnchantments == null ? List.of() : List.copyOf(excludedEnchantments);
		costs = costs == null ? List.of() : List.copyOf(costs);
	}

	/** Legacy/simple constructor: one good per result item, no filter. */
	public GoodSpec(Identifier result, int resultCount, boolean enabled, int maxPrice,
			List<TradeOfferSpec.TradeCost> costs) {
		this(result, result, resultCount, enabled, maxPrice, costs, "", List.of(), "",
				List.of(), "", false);
	}

	/** The pre-{@code filterInvalid} constructor shape, used by all in-memory edits. */
	public GoodSpec(Identifier key, Identifier result, int resultCount,
			boolean enabled, int maxPrice, List<TradeOfferSpec.TradeCost> costs,
			String filterName, List<GoodEnchant> filterEnchantments, String filterNbt,
			List<GoodEnchant> excludedEnchantments, String filterNbtExclude) {
		this(key, result, resultCount, enabled, maxPrice, costs, filterName,
				filterEnchantments, filterNbt, excludedEnchantments, filterNbtExclude,
				false, null, null);
	}

	/** The pre-{@code raw*} constructor shape, used by callers that carry no raw JSON. */
	public GoodSpec(Identifier key, Identifier result, int resultCount,
			boolean enabled, int maxPrice, List<TradeOfferSpec.TradeCost> costs,
			String filterName, List<GoodEnchant> filterEnchantments, String filterNbt,
			List<GoodEnchant> excludedEnchantments, String filterNbtExclude,
			boolean filterInvalid) {
		this(key, result, resultCount, enabled, maxPrice, costs, filterName,
				filterEnchantments, filterNbt, excludedEnchantments, filterNbtExclude,
				filterInvalid, null, null);
	}

	public Identifier id() {
		return this.key;
	}

	public TradeOfferSpec spec() {
		return new TradeOfferSpec(this.costs, this.result);
	}

	public @Nullable Item resultItem() {
		return BuiltInRegistries.ITEM.get(this.result).map(holder -> holder.value()).orElse(null);
	}

	public boolean filterValid() {
		// a token the config reader could not parse must never degrade into "no filter":
		// that would silently match every offer of the result item
		if (this.filterInvalid) return false;
		if (!McNbt.valid(this.filterNbt) || !McNbt.valid(this.filterNbtExclude)) return false;
		Set<String> include = new LinkedHashSet<>();
		Set<String> exclude = new LinkedHashSet<>();
		for (GoodEnchant enchantment : this.filterEnchantments) {
			if (enchantment == null || enchantment.id() == null) return false;
			if (!validRange(enchantment)) return false;
			if (exclude.contains(enchantment.id().toString())) return false;
			if (!include.add(enchantment.id().toString())) return false;
		}
		for (GoodEnchant enchantment : this.excludedEnchantments) {
			if (enchantment == null || enchantment.id() == null) return false;
			if (!validRange(enchantment)) return false;
			if (include.contains(enchantment.id().toString())) return false;
			if (!exclude.add(enchantment.id().toString())) return false;
		}
		return true;
	}

	private static boolean validRange(GoodEnchant enchantment) {
		int min = enchantment.minLevel();
		int max = enchantment.maxLevel();
		if (min < 0 || max < 0 || min > 99 || max > 99) return false;
		return max == 0 || max >= min;
	}

	/** Filter ids that are absent from {@code known}: callers pass the live
	 * enchantment registry so a typo like {@code sharpness..} (a legal-looking id)
	 * is reported instead of silently never matching. */
	public List<Identifier> unknownEnchantIds(Set<Identifier> known) {
		List<Identifier> unknown = new ArrayList<>();
		for (GoodEnchant enchantment : this.filterEnchantments) {
			if (enchantment == null || enchantment.id() == null) continue;
			if (!known.contains(enchantment.id()) && !unknown.contains(enchantment.id())) {
				unknown.add(enchantment.id());
			}
		}
		for (GoodEnchant enchantment : this.excludedEnchantments) {
			if (enchantment == null || enchantment.id() == null) continue;
			if (!known.contains(enchantment.id()) && !unknown.contains(enchantment.id())) {
				unknown.add(enchantment.id());
			}
		}
		return unknown;
	}

	public boolean matches(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = this.resultItem();
		if (item == null || !stack.is(item) || stack.getCount() < this.resultCount) return false;
		if (!this.filterName.isBlank()) {
			var name = stack.getCustomName();
			if (name == null || !name.getString().equals(this.filterName)) return false;
		}
		var live = this.filterEnchantments.isEmpty() && this.excludedEnchantments.isEmpty()
				? null : EnchantmentHelper.getEnchantmentsForCrafting(stack);
		if (!this.filterEnchantments.isEmpty()) {
			for (GoodEnchant wanted : this.filterEnchantments) {
				if (!wanted.levelMatches(liveLevel(live, wanted.id()))) return false;
			}
		}
		if (!this.excludedEnchantments.isEmpty()) {
			for (GoodEnchant unwanted : this.excludedEnchantments) {
				if (unwanted.levelMatches(liveLevel(live, unwanted.id()))) return false;
			}
		}
		if (!this.filterNbt.isBlank()) {
			var filter = McNbt.parse(this.filterNbt);
			if (filter == null || !McNbt.contains(stack, filter)) return false;
		}
		if (!this.filterNbtExclude.isBlank()) {
			var filter = McNbt.parse(this.filterNbtExclude);
			if (filter == null) return false;
			if (!filter.isEmpty() && McNbt.contains(stack, filter)) return false;
		}
		return true;
	}

	private static int liveLevel(
			net.minecraft.world.item.enchantment.ItemEnchantments live, Identifier id) {
		if (live == null) return 0;
		for (var entry : live.entrySet()) {
			Identifier found = entry.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
			if (found != null && found.equals(id)) return entry.getIntValue();
		}
		return 0;
	}

	public int filterSpecificity() {
		return (this.filterName.isBlank() ? 0 : 1)
				+ this.filterEnchantments.size()
				+ (this.filterNbt.isBlank() ? 0 : 1)
				+ this.excludedEnchantments.size()
				+ (this.filterNbtExclude.isBlank() ? 0 : 1);
	}

	public String displayName() {
		String custom = TradeMasterConfig.INSTANCE.customEnchantNames.get(this.key);
		if (custom != null && !custom.isBlank()) return custom.trim();
		if (!this.filterName.isBlank()) return this.filterName;
		Item item = this.resultItem();
		String base = item == null ? this.result.getPath() : new ItemStack(item).getHoverName().getString();
		return this.key.equals(this.result) ? base : base + " (" + this.key.getPath() + ")";
	}

	public String path() {
		return this.result.getPath();
	}

	public GoodSpec withEnabled(boolean value) {
		return new GoodSpec(this.key, this.result, this.resultCount, value, this.maxPrice,
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, this.filterInvalid,
				this.rawIncludeEnchants, this.rawExcludeEnchants);
	}

	public GoodSpec withTrade(int resultCount, int maxPrice, List<TradeOfferSpec.TradeCost> costs) {
		return new GoodSpec(this.key, this.result, resultCount, this.enabled, maxPrice,
				costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, this.filterInvalid,
				this.rawIncludeEnchants, this.rawExcludeEnchants);
	}

	public GoodSpec withResult(Identifier newResult) {
		return new GoodSpec(this.key, newResult, this.resultCount, this.enabled, this.maxPrice,
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, this.filterInvalid,
				this.rawIncludeEnchants, this.rawExcludeEnchants);
	}

	public GoodSpec withFilter(String name, List<GoodEnchant> enchantments, String rawNbt,
			List<GoodEnchant> excluded, String rawNbtExclude) {
		return new GoodSpec(this.key, this.result, this.resultCount, this.enabled, this.maxPrice,
				this.costs, name, enchantments, rawNbt, excluded, rawNbtExclude,
				this.filterInvalid, this.rawIncludeEnchants, this.rawExcludeEnchants);
	}

	public GoodSpec withFilterInvalid(boolean value) {
		// a successful enchantment edit replaces the raw text; keeping it true keeps it
		return new GoodSpec(this.key, this.result, this.resultCount, this.enabled, this.maxPrice,
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, value,
				value ? this.rawIncludeEnchants : null,
				value ? this.rawExcludeEnchants : null);
	}

	public GoodSpec normalized() {
		List<TradeOfferSpec.TradeCost> clean =
				new TradeOfferSpec(this.costs, this.result).normalized().costs();
		return new GoodSpec(this.key == null ? this.result : this.key, this.result,
				Math.max(1, Math.min(64, this.resultCount)),
				this.enabled, Math.max(0, Math.min(64, this.maxPrice)), clean,
				this.filterName.trim(), cleanEnchants(this.filterEnchantments),
				this.filterNbt.trim(), cleanEnchants(this.excludedEnchantments),
				this.filterNbtExclude.trim(), this.filterInvalid,
				this.rawIncludeEnchants, this.rawExcludeEnchants);
	}

	private static List<GoodEnchant> cleanEnchants(List<GoodEnchant> enchantments) {
		List<GoodEnchant> clean = new ArrayList<>();
		for (GoodEnchant enchantment : enchantments) {
			if (enchantment == null || enchantment.id() == null) continue;
			clean.add(enchantment);
		}
		return List.copyOf(clean);
	}
}
