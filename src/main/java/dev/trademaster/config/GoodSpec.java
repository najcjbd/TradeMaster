package dev.trademaster.config;

import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McEnchantments;
import dev.trademaster.compat.McItem;
import dev.trademaster.compat.McNbt;
import dev.trademaster.compat.McRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A custom good to reroll for: an offer whose result matches {@link #result} and the
 * optional NAME / ENCHANTMENT / RAW-NBT filter, and whose cost slots fit {@code costs}.
 *
 * <p>{@link #key} is the stable identity used by the needs / locked / hunt / name
 * history tables. It is separate from the result item so several variants of the same
 * item can be configured. Legacy goods without a stored key use their result id as the
 * key, preserving old aliases.
 *
 * <p>INCLUDE filters are subset filters: every configured field must match, while extra
 * fields are allowed. EXCLUDE filters invert that: the live item must not contain the
 * configured enchantment/NBT subset. An enchantment level of 0 means absence (no such
 * enchantment); {@code 1..} means any present level, {@code N} exactly N and
 * {@code N..M} the inclusive range. Raw NBT is the item tag before 1.20.5 and the
 * component map from 1.20.5; known fields are also exposed in both views so a filter
 * written for one era can match on another.
 */
public record GoodSpec(ResourceLocation key, ResourceLocation result, int resultCount,
		boolean enabled, int maxPrice, List<TradeOfferSpec.TradeCost> costs,
		String filterName, List<GoodEnchant> filterEnchantments, String filterNbt,
		List<GoodEnchant> excludedEnchantments, String filterNbtExclude,
		boolean filterInvalid) {

	/** One structured enchantment requirement. {@code minLevel=0,maxLevel=0} is
	 * absence; {@code minLevel>=1,maxLevel=0} is any present level; a positive maxLevel
	 * is an inclusive upper bound. */
	public record GoodEnchant(ResourceLocation id, int minLevel, int maxLevel) {
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

		/** Legacy two-arg form: a positive level is exact, 0 is absence. */
		public GoodEnchant(ResourceLocation id, int level) {
			this(id, level <= 0 ? 0 : level, level <= 0 ? 0 : level);
		}

		public static GoodEnchant present(ResourceLocation id) {
			return new GoodEnchant(id, 1, 0);
		}

		public static GoodEnchant absent(ResourceLocation id) {
			return new GoodEnchant(id, 0, 0);
		}

		public static GoodEnchant exact(ResourceLocation id, int level) {
			return new GoodEnchant(id, level, level);
		}

		public static GoodEnchant range(ResourceLocation id, int min, int max) {
			return new GoodEnchant(id, min, max);
		}

		public boolean absent() {
			return this.minLevel == 0 && this.maxLevel == 0;
		}

		/** Whether a live enchantment level (0 = not present) satisfies this range. */
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
	public GoodSpec(ResourceLocation result, int resultCount, boolean enabled, int maxPrice,
			List<TradeOfferSpec.TradeCost> costs) {
		this(result, result, resultCount, enabled, maxPrice, costs, "", List.of(), "",
				List.of(), "", false);
	}

	/** The pre-{@code filterInvalid} constructor shape, used by all in-memory edits. */
	public GoodSpec(ResourceLocation key, ResourceLocation result, int resultCount,
			boolean enabled, int maxPrice, List<TradeOfferSpec.TradeCost> costs,
			String filterName, List<GoodEnchant> filterEnchantments, String filterNbt,
			List<GoodEnchant> excludedEnchantments, String filterNbtExclude) {
		this(key, result, resultCount, enabled, maxPrice, costs, filterName,
				filterEnchantments, filterNbt, excludedEnchantments, filterNbtExclude, false);
	}

	/** Identifies the good everywhere (needs, locked ids, hunt marks, name history). */
	public ResourceLocation id() {
		return this.key;
	}

	/** The same costs plus this good's result, in the shape the matcher compares. */
	public TradeOfferSpec spec() {
		return new TradeOfferSpec(this.costs, this.result);
	}

	/** The result item, or null when the id no longer exists in the registry. */
	public @Nullable Item resultItem() {
		return McRegistry.value(McBuiltIn.items(), this.result);
	}

	/** Whether both raw NBT filters parse and every structured enchantment id is usable. */
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

	/** Whether the live offer result satisfies this good's result item/count and filters. */
	public boolean matches(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = this.resultItem();
		if (item == null || !stack.is(item) || stack.getCount() < this.resultCount) return false;
		if (!this.filterName.isBlank()) {
			var name = McItem.customName(stack);
			if (name == null || !name.getString().equals(this.filterName)) return false;
		}
		var live = this.filterEnchantments.isEmpty() && this.excludedEnchantments.isEmpty()
				? null : McEnchantments.of(stack);
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

	private static int liveLevel(java.util.Map<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>, Integer> live,
			ResourceLocation id) {
		if (live == null) return 0;
		for (var entry : live.entrySet()) {
			ResourceLocation found = entry.getKey().unwrapKey().map(McRegistry::keyId).orElse(null);
			if (found != null && found.equals(id)) return entry.getValue();
		}
		return 0;
	}

	/** A rough "most specific wins" score when several variants match one offer. */
	public int filterSpecificity() {
		return (this.filterName.isBlank() ? 0 : 1)
				+ this.filterEnchantments.size()
				+ (this.filterNbt.isBlank() ? 0 : 1)
				+ this.excludedEnchantments.size()
				+ (this.filterNbtExclude.isBlank() ? 0 : 1);
	}

	/** The name used for the villager and the deduction index. */
	public String displayName() {
		String custom = TradeMasterConfig.INSTANCE.customEnchantNames.get(this.key);
		if (custom != null && !custom.isBlank()) return custom.trim();
		if (!this.filterName.isBlank()) return this.filterName;
		Item item = this.resultItem();
		String base = item == null ? this.result.getPath() : new ItemStack(item).getHoverName().getString();
		return this.key.equals(this.result) ? base : base + " (" + this.key.getPath() + ")";
	}

	/** The registry path of the result item. */
	public String path() {
		return this.result.getPath();
	}

	public GoodSpec withEnabled(boolean value) {
		return new GoodSpec(this.key, this.result, this.resultCount, value, this.maxPrice,
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, this.filterInvalid);
	}

	/** A trade-shape edit that keeps the stable key and the filter. */
	public GoodSpec withTrade(int resultCount, int maxPrice, List<TradeOfferSpec.TradeCost> costs) {
		return new GoodSpec(this.key, this.result, resultCount, this.enabled, maxPrice,
				costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, this.filterInvalid);
	}

	/** A result-item edit that keeps the stable key, trade shape and filter. */
	public GoodSpec withResult(ResourceLocation newResult) {
		return new GoodSpec(this.key, newResult, this.resultCount, this.enabled, this.maxPrice,
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, this.filterInvalid);
	}

	/** A filter edit that keeps the stable key and trade shape. */
	public GoodSpec withFilter(String name, List<GoodEnchant> enchantments, String rawNbt,
			List<GoodEnchant> excluded, String rawNbtExclude) {
		return new GoodSpec(this.key, this.result, this.resultCount, this.enabled, this.maxPrice,
				this.costs, name, enchantments, rawNbt, excluded, rawNbtExclude,
				this.filterInvalid);
	}

	public GoodSpec withFilterInvalid(boolean value) {
		return new GoodSpec(this.key, this.result, this.resultCount, this.enabled, this.maxPrice,
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt,
				this.excludedEnchantments, this.filterNbtExclude, value);
	}

	/** Clamped/normalised copy for a clean write. */
	public GoodSpec normalized() {
		List<TradeOfferSpec.TradeCost> clean =
				new TradeOfferSpec(this.costs, this.result).normalized().costs();
		return new GoodSpec(this.key == null ? this.result : this.key, this.result,
				Math.max(1, Math.min(64, this.resultCount)),
				this.enabled, Math.max(0, Math.min(64, this.maxPrice)), clean,
				this.filterName.trim(), cleanEnchants(this.filterEnchantments),
				this.filterNbt.trim(), cleanEnchants(this.excludedEnchantments),
				this.filterNbtExclude.trim(), this.filterInvalid);
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
