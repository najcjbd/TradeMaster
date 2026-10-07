package dev.trademaster.config;

import dev.trademaster.compat.McRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A custom good to reroll for: an offer whose result is {@code result} — any item, not
 * just an enchanted book — and whose cost slots fit {@code costs}, exactly like an
 * enchantment target fits enchanted books.
 *
 * <p>The good is identified by its result item, so two goods cannot share one result.
 * That id is what the needs / locked / hunt / name-history tables key on, which is why
 * everything downstream (budget, hunt ratchet, auto-disable, named-villager deduction)
 * works for goods without a second code path.
 *
 * <p>{@code costs} is the same ordered slot list an enchantment's spec uses: slot 1 is
 * the price slot, and its amount is this good's {@code maxPrice} (the "no more than"
 * price, exactly like an enchantment's max price). Slot 2 and beyond carry their own
 * amounts (see {@link TradeOfferSpec} for the count rules).
 *
 * <p>Goods cannot be disenchanted (they are not books): what happens to one after it is
 * bought is the disposal option's business.
 */
public record GoodSpec(ResourceLocation result, int resultCount, boolean enabled, int maxPrice,
		List<TradeOfferSpec.TradeCost> costs) {

	/** Identifies the good everywhere (needs, locked ids, hunt marks, name history). */
	public ResourceLocation id() {
		return this.result;
	}

	/** The same costs plus this good's result, in the shape the matcher compares. */
	public TradeOfferSpec spec() {
		return new TradeOfferSpec(this.costs, this.result);
	}

	/** The result item, or null when the id no longer exists in the registry. */
	public @Nullable Item resultItem() {
		return McRegistry.value(BuiltInRegistries.ITEM, this.result);
	}

	/** The name used for the villager and the deduction index: the custom alias when
	 * one is set for this good (the alias tab edits the same map enchantments use),
	 * otherwise the result item's own name, falling back to the registry path when the
	 * item is unknown. Callers apply the shared sanitizer/canonicalizer. */
	public String displayName() {
		String custom = TradeMasterConfig.INSTANCE.customEnchantNames.get(this.result);
		if (custom != null && !custom.isBlank()) return custom.trim();
		Item item = this.resultItem();
		return item == null ? this.result.getPath() : new ItemStack(item).getHoverName().getString();
	}

	/** The registry path of the result item: a villager named with the raw item id
	 * (the naming fallback) must still resolve back to this good. */
	public String path() {
		return this.result.getPath();
	}

	public GoodSpec withEnabled(boolean value) {
		return new GoodSpec(this.result, this.resultCount, value, this.maxPrice, this.costs);
	}

	/** Clamped copy for a clean write: the result count is at least one and the price
	 * never negative. */
	public GoodSpec normalized() {
		// reuse the spec's sanitiser: a hand-written slot with no item becomes air and
		// the counts are clamped, so a typo can never reach the writer as a null item
		// (which would throw and make every later save fail)
		List<TradeOfferSpec.TradeCost> clean =
				new TradeOfferSpec(this.costs, this.result).normalized().costs();
		return new GoodSpec(this.result, Math.max(1, Math.min(64, this.resultCount)),
				this.enabled, Math.max(0, Math.min(64, this.maxPrice)), clean);
	}
}
