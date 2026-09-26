package dev.trademaster.config;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * How one target's trade is expected to look.
 *
 * <p>{@code costs} is an ORDERED list of slots: slot 1 is the vanilla "cost A"
 * (emeralds for an enchantment book), slot 2 the vanilla "cost B" (the book). A mod
 * or datapack that changes the trade object can carry MORE slots, so this is a list
 * rather than two fields — the extra slots (3..N) are read from the offer in the same
 * order and compared against the same list here. They can only be declared by hand in
 * the config file; the in-game tab edits slots 1 and 2.
 *
 * <p>{@code result} is the item the offer must yield. For an enchantment target the
 * default is {@code minecraft:enchanted_book}; naming an <em>air</em> item here means
 * "ignore this target" (the agreed way to switch a row off from this table).
 *
 * <p>Matching rule (agreed): <b>every slot the offer charges must be declared here,
 * with the same item and a count no greater than the spec's; slots declared here that
 * the offer does not charge are fine</b> — the player simply pays less. A slot whose
 * item is an air item counts as not charged on either side.
 */
public record TradeOfferSpec(List<TradeCost> costs, Identifier result) {

	public static final Identifier EMERALD = Identifier.withDefaultNamespace("emerald");
	public static final Identifier BOOK = Identifier.withDefaultNamespace("book");
	public static final Identifier ENCHANTED_BOOK = Identifier.withDefaultNamespace("enchanted_book");
	/** The canonical "not charged" item; a spec slot left without an item becomes this. */
	public static final Identifier AIR = Identifier.withDefaultNamespace("air");

	/** emerald + book → enchanted book: exactly what the mod required before this
	 * feature existed (it never capped the book count), so an absent spec keeps
	 * today's behaviour. Slot 1's count is ignored — its amount is the target's own
	 * price (the enchantments tab's max price). */
	public static TradeOfferSpec defaults() {
		return new TradeOfferSpec(
				List.of(new TradeCost(EMERALD, 0), new TradeCost(BOOK, UNLIMITED)),
				ENCHANTED_BOOK);
	}

	/** Count meaning for a slot: a negative count is "no limit" (the server's own cap
	 * applies), 0 is "this slot is not required", a positive count is the cap. Slot 1
	 * ignores its stored count entirely — its amount is the target's price. */
	public static final int UNLIMITED = -1;

	/** The effective cap a slot allows, or 0 when the slot is not required. Slot 1's
	 * cap is {@code price} (the enchantments tab's max price for that target). */
	public static int capFor(int slot, TradeCost cost, int price) {
		if (slot == 0) return price;
		if (cost == null) return 0;
		if (cost.count() < 0) return UNLIMITED_CAP;
		return Math.max(0, cost.count());
	}

	/** What "no limit" resolves to in a comparison. Deliberately far above any real
	 * trade (the 64 emerald clamp applies to the PRICE, not to item counts): a server
	 * that charges more than the old 64 must still match, or "-1 no limit" would
	 * silently become "at most 64" and change behaviour for such servers. */
	public static final int UNLIMITED_CAP = 1_000_000;

	/** One cost slot: the item that must be paid, and how many (see the count rules
	 * above). An air item as the item means the slot is not used at all. */
	public record TradeCost(Identifier item, int count) {}

	/** Copy with every slot kept in place (positions matter — slot 2 must stay slot 2)
	 * and values clamped. A missing item becomes {@code minecraft:air}, which both
	 * sides read as "not charged". */
	public TradeOfferSpec normalized() {
		List<TradeCost> kept = new ArrayList<>(this.costs.size());
		for (TradeCost cost : this.costs) {
			Identifier item = cost == null || cost.item() == null ? AIR : cost.item();
			int count = cost == null ? 0 : Math.max(UNLIMITED, Math.min(UNLIMITED_CAP, cost.count()));
			kept.add(new TradeCost(item, count));
		}
		Identifier res = this.result == null ? ENCHANTED_BOOK : this.result;
		return new TradeOfferSpec(List.copyOf(kept), res);
	}

	/** The declared slot at {@code index} (0-based), or null when the list is shorter
	 * than that — which means "the offer must not charge that slot". */
	public @Nullable TradeCost costAt(int index) {
		return index >= 0 && index < this.costs.size() ? this.costs.get(index) : null;
	}

}
