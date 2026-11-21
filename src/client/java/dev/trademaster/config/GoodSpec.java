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
 * A custom good to reroll for: an offer whose result matches {@link #result} and the
 * optional NAME / ENCHANTMENT / RAW-NBT filter, and whose cost slots fit {@code costs}.
 *
 * <p>{@link #key} is the stable identity used by the needs / locked / hunt / name
 * history tables. It is separate from the result item so several variants of the same
 * item can be configured. Legacy goods without a stored key use their result id as the
 * key, preserving old aliases.
 *
 * <p>The filter is a SUBSET filter: every configured field must match, while extra
 * fields are allowed. An enchantment level of 0 means presence only; a positive level
 * means at least that level. The raw NBT form is the item's component map.
 */
public record GoodSpec(Identifier key, Identifier result, int resultCount,
		boolean enabled, int maxPrice, List<TradeOfferSpec.TradeCost> costs,
		String filterName, List<GoodEnchant> filterEnchantments, String filterNbt) {

	public record GoodEnchant(Identifier id, int level) {
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
	public GoodSpec(Identifier result, int resultCount, boolean enabled, int maxPrice,
			List<TradeOfferSpec.TradeCost> costs) {
		this(result, result, resultCount, enabled, maxPrice, costs, "", List.of(), "");
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
		if (!McNbt.valid(this.filterNbt)) return false;
		for (GoodEnchant enchantment : this.filterEnchantments) {
			if (enchantment == null || enchantment.id() == null) return false;
		}
		return true;
	}

	public boolean matches(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		Item item = this.resultItem();
		if (item == null || !stack.is(item) || stack.getCount() < this.resultCount) return false;
		if (!this.filterName.isBlank()) {
			var name = stack.getCustomName();
			if (name == null || !name.getString().equals(this.filterName)) return false;
		}
		if (!this.filterEnchantments.isEmpty()) {
			var live = EnchantmentHelper.getEnchantmentsForCrafting(stack);
			for (GoodEnchant wanted : this.filterEnchantments) {
				int level = -1;
				for (var entry : live.entrySet()) {
					Identifier id = entry.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
					if (id != null && id.equals(wanted.id())) {
						level = entry.getIntValue();
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

	public int filterSpecificity() {
		return (this.filterName.isBlank() ? 0 : 1)
				+ this.filterEnchantments.size()
				+ (this.filterNbt.isBlank() ? 0 : 1);
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
				this.costs, this.filterName, this.filterEnchantments, this.filterNbt);
	}

	public GoodSpec withTrade(int resultCount, int maxPrice, List<TradeOfferSpec.TradeCost> costs) {
		return new GoodSpec(this.key, this.result, resultCount, this.enabled, maxPrice,
				costs, this.filterName, this.filterEnchantments, this.filterNbt);
	}

	public GoodSpec withFilter(String name, List<GoodEnchant> enchantments, String rawNbt) {
		return new GoodSpec(this.key, this.result, this.resultCount, this.enabled, this.maxPrice,
				this.costs, name, enchantments, rawNbt);
	}

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
