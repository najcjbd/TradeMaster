package dev.trademaster.config;

import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McEnchantments;
import dev.trademaster.compat.McItem;
import dev.trademaster.compat.McNbt;
import dev.trademaster.compat.McRegistry;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
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
 * item can be configured (for example a named sharp sword and a plain one). Legacy
 * goods without a stored key use their result id as the key, preserving old aliases.
 *
 * <p>The filter is a SUBSET filter: every configured name/enchantment/NBT field must
 * match the live result, while extra fields are allowed. An enchantment level of 0
 * means "only presence is required"; a positive level means "at least this level".
 * The raw NBT form is the item tag before 1.20.5 and the component map from 1.20.5;
 * the structured name/enchantment filters are version-neutral.
 */
public record GoodSpec(ResourceLocation key, ResourceLocation result, int resultCount,
		boolean enabled, int maxPrice, List<TradeOfferSpec.TradeCost> costs,
		String filterName, List<GoodEnchant> filterEnchantments, String filterNbt) {

	/** One structured enchantment requirement. {@code level <= 0} means presence only. */
	public record GoodEnchant(ResourceLocation id, int level) {
		public GoodEnchant {
			level = Math.max(0, Math.min(99, level));
		}
	}

	public GoodSpec {
		if (key == null) key = result;
		filterName = filterName == null ? "" : filterName.trim();
		filterNbt = filterNbt == null ? "" : filterNbt.trim();
		filterEnchantments = filterEnchantments == null ? List.of() : List.copyOf(filterEnchantments);
		costs = costs == null ? List.of() : List.copyOf(costs);
	}

	/** Legacy/simple constructor: one good per result item, no filter. */
	public GoodSpec(ResourceLocation result, int resultCount, boolean enabled, int maxPrice,
			List<TradeOfferSpec.TradeCost> costs) {
		this(result, result, resultCount, enabled, maxPrice, costs, "", List.of(), "");
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

	/** Whether the raw NBT filter parses and every structured enchantment id is usable. */
	public boolean filterValid() {
		if (!McNbt.valid(this.filterNbt)) return false;
		for (GoodEnchant enchantment : this.filterEnchantments) {
			if (enchantment == null || enchantment.id() == null) return false;
		}
		return true;
	}

	/** Whether the live offer result satisfies this good's result item/count and filter.
	 * The result item and count are checked here too so every caller uses one rule. */
	public boolean matches(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = this.resultItem();
		if (item == null || !stack.is(item) || stack.getCount() < this.resultCount) return false;
		if (!this.filterName.isBlank()) {
			var name = McItem.customName(stack);
			if (name == null || !name.getString().equals(this.filterName)) return false;
		}
		if (!this.filterEnchantments.isEmpty()) {
			var live = McEnchantments.of(stack);
			for (GoodEnchant wanted : this.filterEnchantments) {
				int level = -1;
				for (var entry : live.entrySet()) {
					ResourceLocation id = entry.getKey().unwrapKey().map(McRegistry::keyId).orElse(null);
					if (id != null && id.equals(wanted.id())) {
						level = entry.getValue();
						break;
					}
				}
				if (level < 0) return false;
				if (wanted.level() > 0 && level < wanted.level()) return false;
			}
		}
		if (!this.filterNbt.isBlank()) {
			var filter = McNbt.parse(this.filterNbt);
			if (filter == null || !McNbt.contains(stack, filter)) return false;
		}
		return true;
	}

	/** A rough "most specific wins" score when several variants match one offer. */
	public int filterSpecificity() {
		return (this.filterName.isBlank() ? 0 : 1)
				+ this.filterEnchantments.size()
				+ (this.filterNbt.isBlank() ? 0 : 1);
	}

	/** The name used for the villager and the deduction index: the custom alias when
	 * one is set for this good, otherwise the configured result name, otherwise the
	 * result item's own name. Callers apply the shared sanitizer/canonicalizer. */
	public String displayName() {
		String custom = TradeMasterConfig.INSTANCE.customEnchantNames.get(this.key);
		if (custom != null && !custom.isBlank()) return custom.trim();
		if (!this.filterName.isBlank()) return this.filterName;
		Item item = this.resultItem();
		String base = item == null ? this.result.getPath() : new ItemStack(item).getHoverName().getString();
		// a synthetic key marks an additional variant: include it so two variants of
		// the same item do not produce the same villager name (deduction would fail)
		return this.key.equals(this.result) ? base : base + " (" + this.key.getPath() + ")";
	}

	/** The registry path of the result item: a villager named with the raw item id
	 * (the naming fallback) must still resolve back to this good. */
	public String path() {
		return this.result.getPath();
	}

	public GoodSpec withEnabled(boolean value) {
		return new GoodSpec(this.key, this.result, this.resultCount, value, this.maxPrice,
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt);
	}

	/** A trade-shape edit that keeps the stable key and the filter. */
	public GoodSpec withTrade(int resultCount, int maxPrice, List<TradeOfferSpec.TradeCost> costs) {
		return new GoodSpec(this.key, this.result, resultCount, this.enabled, maxPrice,
				costs, this.filterName, this.filterEnchantments, this.filterNbt);
	}

	/** A filter edit that keeps the stable key and the trade shape. */
	public GoodSpec withFilter(String name, List<GoodEnchant> enchantments, String rawNbt) {
		return new GoodSpec(this.key, this.result, this.resultCount, this.enabled, this.maxPrice,
				this.costs, name, enchantments, rawNbt);
	}

	/** Clamped/normalised copy for a clean write. */
	public GoodSpec normalized() {
		List<TradeOfferSpec.TradeCost> clean =
				new TradeOfferSpec(this.costs, this.result).normalized().costs();
		Set<String> seen = new LinkedHashSet<>();
		List<GoodEnchant> enchants = new ArrayList<>();
		for (GoodEnchant enchantment : this.filterEnchantments) {
			if (enchantment == null || enchantment.id() == null) continue;
			if (!seen.add(enchantment.id().toString())) continue;
			enchants.add(enchantment);
		}
		return new GoodSpec(this.key == null ? this.result : this.key, this.result,
				Math.max(1, Math.min(64, this.resultCount)),
				this.enabled, Math.max(0, Math.min(64, this.maxPrice)), clean,
				this.filterName.trim(), List.copyOf(enchants), this.filterNbt.trim());
	}
}
