package dev.trademaster.matching;

import dev.trademaster.compat.McRegistry;
import dev.trademaster.compat.McItem;
import dev.trademaster.compat.McEnchantments;
import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.exec.EnchantmentNamer;
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-target matcher (LPCTools tradeReroller style): keeps a max-price
 * budget heap per enchantment, supports "reserve cheaper traders", and can
 * deduct already-found enchantments by scanning named librarian villagers.
 */
public class TradeMatcher {

	public record MatchPart(ResourceLocation id, int level) {}

	/** One qualifying enchanted-book offer. {@code parts} are the enchantments that
	 * matched an enabled target at sufficient level; {@code bookEnchantments} is the
	 * book's FULL set (lock fingerprints must reject any change to it, matched or not). */
	public record MatchedBook(List<MatchPart> parts, List<MatchPart> bookEnchantments, int price,
			int offerIndex, List<ItemStack> costs, ItemStack result) {
		/** This book's parts as an item's display name would be, for a custom good. */
		/** Canonical copy of a book's charged cost slots, for the drift fingerprint: the
		 * live offer must charge the same items in the same amounts right before the
		 * purchase, or the click would apply to a regenerated trade. */
		public static List<ItemStack> fingerprintCosts(List<ItemStack> slots) {
			List<ItemStack> copy = new ArrayList<>(slots.size());
			for (ItemStack stack : slots) {
				copy.add(stack == null ? ItemStack.EMPTY : stack.copy());
			}
			return List.copyOf(copy);
		}

		/** Whether a live result stack is still the one this book was matched with: the
		 * same item with at least the matched amount (a bonus is fine), and for an
		 * enchanted book the same enchantment set — the drift-proof part. Shared by the
		 * executor's per-stage check and the controller's "offer unchanged" check, and
		 * used for custom goods, whose result is not a book. */
		public boolean resultMatches(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return false;
			if (!this.result.is(Items.ENCHANTED_BOOK)) {
				return stack.is(this.result.getItem()) && stack.getCount() >= this.result.getCount();
			}
			if (!stack.is(Items.ENCHANTED_BOOK)) return false;
			var enchantments = McEnchantments.of(stack);
			if (enchantments.size() != this.bookEnchantments.size()) return false;
			for (MatchPart part : this.bookEnchantments) {
				boolean found = false;
				for (var entry : enchantments.entrySet()) {
					ResourceLocation id = entry.getKey().unwrapKey().map(McRegistry::keyId).orElse(null);
					if (id != null && id.equals(part.id()) && entry.getValue() == part.level()) {
						found = true;
						break;
					}
				}
				if (!found) return false;
			}
			return true;
		}

		/** Whether a live offer charges exactly what this book was matched with. */
		public boolean costsMatch(List<ItemStack> liveSlots) {
			if (liveSlots.size() != this.costs.size()) return false;
			for (int i = 0; i < liveSlots.size(); i++) {
				ItemStack live = liveSlots.get(i);
				ItemStack matched = this.costs.get(i);
				if (live == null) live = ItemStack.EMPTY;
				if (!McItem.sameItemSameComponents(live, matched)) return false;
				if (live.getCount() != matched.getCount()) return false;
			}
			return true;
		}
	}

	/** Budget bookkeeping for one deducted need, so restore/undo/reset can return
	 * exactly what the match consumed. {@code seenPrice} is the book price that was
	 * on the table when the need was deducted (reserve removal keys off it). */
	public record ConsumedNeed(ResourceLocation id, int slot, boolean reserveAdded, int seenPrice) {}

	/** A match against one villager: every qualifying enchanted-book offer
	 * (cheapest first — index 0 is the one purchased; a single trade suffices to
	 * lock the villager) plus the needs deducted for the targets they cover. */
	public record MatchedTrade(List<MatchedBook> books, List<ConsumedNeed> consumed, int needsVersion) {
		public MatchedBook purchase() { return this.books.get(0); }
		public ResourceLocation id() { return purchase().parts().get(0).id(); }
		public int level() { return purchase().parts().get(0).level(); }
		public int price() { return purchase().price(); }
		public int offerIndex() { return purchase().offerIndex(); }
		/** Every matched target id across all qualifying books, first-seen order. */
		/** All matched parts with their kind (level 0 = custom good, >=1 = enchantment). */
		public List<MatchPart> matchedParts() {
			java.util.List<MatchPart> parts = new java.util.ArrayList<>();
			for (MatchedBook book : this.books) {
				for (MatchPart part : book.parts()) {
					if (!parts.contains(part)) parts.add(part);
				}
			}
			return parts;
		}

		public List<ResourceLocation> matchedIds() {
			List<ResourceLocation> ids = new ArrayList<>();
			for (MatchedBook book : this.books) {
				for (MatchPart part : book.parts()) {
					if (!ids.contains(part.id())) ids.add(part.id());
				}
			}
			return ids;
		}
		/** Whether any matched part now sits below its target's (possibly raised)
		 * configured level. */
		public boolean anyPartBelowConfigLevel() {
			for (MatchedBook book : this.books) {
				for (MatchPart part : book.parts()) {
					// level 0 marks a GOOD part: it is not an enchantment and has no
					// configured level, even when an enchantment shares its id
					if (part.level() == 0) continue;
					EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(part.id());
					if (target != null && target.enabled() && part.level() < target.level()) return true;
				}
			}
			return false;
		}
		/** Whether any matched target was removed or disabled in the config since
		 * the match (a mid-run edit that invalidates the combo). */
		public boolean anyTargetGone() {
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			for (MatchPart part : matchedParts()) {
				ResourceLocation id = part.id();
				if (part.level() == 0) {
					// a GOOD part: it is shadowed as soon as a same-id target is enabled
					// (the target-first precedence rule); otherwise its own row decides
					EnchantmentTarget sameId = config.enchantments.get(id);
					if (sameId != null && sameId.enabled()) return true;
					GoodSpec good = config.goodFor(id);
					if (good == null || !good.enabled() || !goodMatchable(good)) return true;
					continue;
				}
				// an enchantment part: its own row must still be live and matchable
				EnchantmentTarget target = config.enchantments.get(id);
				if (target == null || !target.enabled()) return true;
				// the row can also be switched off WITHOUT disabling it: an AIR spec
				// result, or an enchantment no longer in the live registry
				if (config.isAirItem(config.specFor(id).result())) return true;
				if (!registryTradeable(id)) return true;
			}
			return false;
		}
	}

	/** per-enchantment heap of acceptable prices (max heap) */
	private final HashMap<ResourceLocation, IntHeapPriorityQueue> needs = new HashMap<>();
	private final Map<ResourceLocation, EnchantmentTarget> targets = new LinkedHashMap<>();
	/** Bumped on every full buildNeeds rebuild: a budget return (restore/undo)
	 * carrying an older version would enqueue into a queue the rebuild already
	 * refilled, doubling the budget — the version check drops such returns. */
	private int needsVersion = 0;
	/** Hunt-cheaper mode: locked price per enchantment this run. A hunt-marked
	 * target's budget is marked-1, so only a strictly cheaper copy can match. */
	private Map<ResourceLocation, Integer> huntPrices = Map.of();
	/** Hunt-cheaper mode: temporary combo needs recorded when a multi-enchantment
	 * book was purchased (qualifying-part set, id AND level -> cheapest price
	 * paid). A book whose qualifying set equals a recorded one only matches
	 * strictly cheaper — the combo ratchet. A record is auxiliary only: it
	 * never keeps a hunt alive on its own — the run continues solely on live
	 * primary needs, and stop() clears the records. */
	private Map<Set<MatchPart>, Integer> comboHuntPrices = Map.of();

	public void buildNeeds() {
		this.needsVersion++;
		this.needs.clear();
		this.targets.clear();
		for (EnchantmentTarget target : TradeMasterConfig.INSTANCE.enabledTargets()) {
			// a target whose vanilla minimum price exceeds the configured max can never
			// match, one switched off with an AIR result is never offered, and one whose
			// enchantment left the registry / lost TRADEABLE can never be offered either:
			// keeping any of them in the needs would leave an unsatisfiable demand alive
			// for the whole run (start() warns about the price case)
			if (!targetMatchable(target)) continue;
			this.targets.put(target.id(), target);
			this.needs.computeIfAbsent(target.id(),
							k -> new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a)))
					.enqueue(target.maxPrice());
		}
		// custom goods ride the same table: their id is their result item, so the
		// budget / hunt / locked machinery needs no second code path. A good that can
		// never be offered (air/absent result, price cap 0, no payable price slot) must
		// be skipped here too, or the run rerolls forever on a need nothing can satisfy.
		for (GoodSpec good : TradeMasterConfig.INSTANCE.enabledGoods()) {
			if (!goodMatchable(good)) continue;
			this.needs.computeIfAbsent(good.id(),
							k -> new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a)))
					.enqueue(good.maxPrice());
		}
	}

	/** Whether the id belongs to a configured good rather than an enchantment target:
	 * goods are keyed by their result item, so the enchantment-only gates below must
	 * not be applied to them. */
	public static boolean isGood(ResourceLocation id) {
		return TradeMasterConfig.INSTANCE.goodFor(id) != null;
	}

	/** The price slot's amount for a demand: the good's max price, or the target's.
	 * An id that is an enabled TARGET wins over a same-id good (mirrors goodMatchable,
	 * which refuses such a good): reading a target's price from the good would shrink
	 * its allowance and silently unmatch it. */
	private int priceOf(ResourceLocation id) {
		EnchantmentTarget target = this.targets.get(id);
		if (target != null) return target.maxPrice();
		GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(id);
		return good != null ? good.maxPrice() : 0;
	}

	/** The cost spec for a demand: the good's, or the enchantment's. An id that is an
	 * enabled target wins over a same-id good (see {@link #priceOf}). */
	private TradeOfferSpec specOf(ResourceLocation id) {
		if (this.targets.containsKey(id)) return TradeMasterConfig.INSTANCE.specFor(id);
		GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(id);
		return good != null ? good.spec() : TradeMasterConfig.INSTANCE.specFor(id);
	}

	/** The price the program treats as this offer's sale price: the BASE cost by
	 * default (historic behaviour, before discounts/demand), or the actually charged
	 * cost when {@code basePriceAsSalePrice} is off. */
	public static int offerPrice(MerchantOffer offer) {
		return TradeMasterConfig.INSTANCE.basePriceAsSalePrice
				? offer.getBaseCostA().getCount()
				: offer.getCostA().getCount();
	}

	/** Whether the enchantment currently exists in the live registry and carries
	 * the TRADEABLE tag (the same gate syncEnchantmentsFromRegistry uses). No
	 * level/registry available yet means assume available — the check re-runs on
	 * every rebuild. */
	public static boolean registryTradeable(ResourceLocation id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return true;
		Registry<Enchantment> registry = McRegistry.enchantments(mc.level);
		Holder<Enchantment> holder = McRegistry.holder(registry, id);
		return holder != null && McEnchantments.isTradeable(holder);
	}

	/** Whether an enchantment target can ever be offered: its vanilla minimum price must
	 * fit its configured max, its spec result must not be an air item, and the
	 * enchantment must still be tradeable. buildNeeds, resyncTargets and the material
	 * precheck must all agree on this set, or the precheck demands materials for a trade
	 * that no villager can ever offer. */
	public static boolean targetMatchable(EnchantmentTarget target) {
		if (TradeMasterConfig.minPrice(target.level(), target.id()) > target.maxPrice()) return false;
		if (TradeMasterConfig.INSTANCE.isAirItem(TradeMasterConfig.INSTANCE.specFor(target.id()).result())) return false;
		return registryTradeable(target.id());
	}

	/** Whether a custom good can ever be offered: its result item must still exist and
	 * not be an air item, its price cap must allow at least one emerald, and its spec
	 * must declare at least one PAYABLE slot. "Payable" is the matcher's own notion
	 * (see {@link #specHasPayableSlot}); a good whose slots are all air / unknown / a
	 * count of 0 declares "pays nothing" and can never match, so enqueuing it would
	 * spin the run forever. */
	public static boolean goodMatchable(GoodSpec good) {
		if (good.maxPrice() < 1) return false;
		if (TradeMasterConfig.INSTANCE.isAirItem(good.id())) return false;
		if (good.resultItem() == null) return false;
		// an id that is ALSO an enchantment TARGET shares one needs queue and the id-keyed
		// vouchers/marks cannot distinguish the kinds. Target-first means the target wins:
		// a same-id good is never matchable, whatever the target's enabled state. The
		// Offers tab and the config reader refuse such a good; old files become inert.
		if (TradeMasterConfig.INSTANCE.enchantments.containsKey(good.id())) return false;
		return specHasPayableSlot(good.spec());
	}

	/** Whether an enchantment target's spec declares at least one payable cost slot (see
	 * {@link #specHasPayableSlot}). A target with none can never be bought ALONE. It is
	 * deliberately NOT pruned from the needs — it can still qualify as a passenger on a
	 * combo book — but start() warns about it so the failure is not silent. */
	public static boolean targetHasPayableCost(EnchantmentTarget target) {
		return specHasPayableSlot(TradeMasterConfig.INSTANCE.specFor(target.id()));
	}

	/** Whether at least one spec slot can be charged: the mirror of costsAllowed/capFor —
	 * the mirror of {@code costsAllowed}/{@code capFor}: slot 1 is payable when its item
	 * is a real (registry) non-air item, since its amount is the price; a later slot only
	 * when its count is not 0 (0 = "not required", negative = no limit, positive = cap).
	 * A spec with no such slot can never be matched by any offer. */
	private static boolean specHasPayableSlot(TradeOfferSpec spec) {
		List<TradeOfferSpec.TradeCost> costs = spec.costs();
		for (int slot = 0; slot < costs.size(); slot++) {
			TradeOfferSpec.TradeCost cost = costs.get(slot);
			if (cost == null || cost.item() == null) continue;
			if (TradeMasterConfig.INSTANCE.isAirItem(cost.item())) continue;
			if (McRegistry.value(McBuiltIn.items(), cost.item()) == null) continue;
			// slot 1's count is ignored (capFor returns the price); a later slot with
			// count 0 is "not required" and contributes no allowance
			if (slot >= 1 && cost.count() == 0) continue;
			return true;
		}
		return false;
	}

	public boolean isEmpty() {
		return this.needs.isEmpty();
	}

	/** Drops targets already locked this run: the rebuild in buildNeeds wiped the
	 * in-session memory, and without naming/auto-disable no other voucher exists. */
	public void dropLocked(Set<ResourceLocation> lockedIds) {
		for (ResourceLocation id : lockedIds) {
			this.needs.remove(id);
		}
	}

	public @Nullable MatchedTrade tryMatch(MerchantOffers offers) {
		List<MatchedBook> qualifying = new ArrayList<>();
		// scan EVERY offer: datapacks/mods can give a novice librarian several
		// enchanted-book trades, and each book can carry several enchantments
		for (int i = 0; i < offers.size(); i++) {
			MerchantOffer offer = offers.get(i);
			// a sold-out offer cannot be purchased: matching it would run into a
			// guaranteed lock rejection (and re-match it forever after the retry cap)
			if (offer.isOutOfStock()) continue;
			ItemStack result = offer.getResult();
			ResourceLocation resultItem = McBuiltIn.items().getKey(result.getItem());
			// a configured good: any non-book result the player asked to reroll for.
			// (An enchanted book result is always the enchantment path, even if some
			// good names enchanted_book as its result.)
			GoodSpec good = resultItem == null || result.is(Items.ENCHANTED_BOOK)
					? null
					: TradeMasterConfig.INSTANCE.goodFor(resultItem);
			if (good != null) {
				// the SAME gate buildNeeds used to enqueue goods: goodFor still returns a good
				// that the gate refuses (e.g. its id is ALSO an enabled target, which shares one
				// needs queue) — matching it here would read the target's queue for a non-book
				// offer. buildNeeds and tryMatch must agree on the demand set or they diverge.
				if (!goodMatchable(good)) continue;
				if (result.getCount() < good.resultCount()) continue;
				IntHeapPriorityQueue goodQueue = this.needs.get(good.id());
				if (goodQueue == null) continue;
				int goodPrice = offerPrice(offer);
				if (goodPrice > Math.min(goodQueue.firstInt(), 64)) continue;
				List<MatchPart> itemParts = List.of(new MatchPart(good.id(), 0));
				if (!costsAllowed(offer, itemParts)) continue;
				qualifying.add(new MatchedBook(itemParts, List.of(), goodPrice, i,
						MatchedBook.fingerprintCosts(OfferCosts.slots(offer)), result.copy()));
				continue;
			}
			if (!result.is(Items.ENCHANTED_BOOK)) continue;
			var enchantments = McEnchantments.of(result);
			if (enchantments.isEmpty()) continue;
			List<MatchPart> bookEnchantments = new ArrayList<>();
			List<MatchPart> parts = new ArrayList<>();
			int budget = 0;
			for (var entry : enchantments.entrySet()) {
				ResourceLocation id = entry.getKey().unwrapKey().map(McRegistry::keyId).orElse(null);
				int level = entry.getValue();
				if (id != null) bookEnchantments.add(new MatchPart(id, level));
				if (id == null) continue;
				// qualifying is decided by the live config (enabled + level satisfied),
				// NOT needs presence: a recorded combo ratchet still applies to the
				// same combo re-appearing mid-run even after its targets were locked
				// out of the needs table (the record itself never keeps the run
				// alive — live primary needs decide that)
				EnchantmentTarget target = this.targets.get(id);
				if (target == null || level < target.level()) continue;
				// an AIR result in the spec switches that row off from the offers table
				if (TradeMasterConfig.INSTANCE.isAirItem(TradeMasterConfig.INSTANCE.specFor(id).result())) {
					continue;
				}
				parts.add(new MatchPart(id, level));
				IntHeapPriorityQueue queue = this.needs.get(id);
				if (queue != null) budget += queue.firstInt();
			}
			if (parts.isEmpty()) continue;
			// the cost slots must fit the specs' allowances (order-insensitive)
			if (!costsAllowed(offer, parts)) continue;
			// the price is slot 1's amount: for the default spec that is exactly the old
			// emerald price, and the spec rules keep it in that same scale otherwise, so
			// the budget / hunt / reserve machinery below is untouched
			int price = offerPrice(offer);
			// recorded combo need (hunt): the same qualifying set only matches
			// strictly cheaper than last paid, ratcheting like a single book —
			// regardless of whether its targets are still in the needs table
			Integer comboMark = parts.size() >= 2 ? this.comboHuntPrices.get(comboKey(parts)) : null;
			if (comboMark != null) {
				if (price >= comboMark) continue;
			} else {
				// otherwise the price must fit the sum of the matched targets'
				// current budget slots; prices clamp at 64 server-side, so the cap
				// is 64. For a single-enchantment book this is exactly the old
				// check (price <= that target's slot) — vanilla behavior unchanged
				if (price > Math.min(budget, 64)) continue;
			}
			qualifying.add(new MatchedBook(parts, bookEnchantments, price, i,
					MatchedBook.fingerprintCosts(OfferCosts.slots(offer)), result.copy()));
		}
		if (qualifying.isEmpty()) return null;
		// one purchase locks the villager: buy the cheapest qualifying book
		qualifying.sort(Comparator.comparingInt(MatchedBook::price));
		List<ConsumedNeed> consumed = new ArrayList<>();
		Set<ResourceLocation> deducted = new HashSet<>();
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		for (MatchedBook book : qualifying) {
			for (MatchPart part : book.parts()) {
				// a need covered by several books still only needs one villager:
				// deduct each target once
				if (!deducted.add(part.id())) continue;
				IntHeapPriorityQueue queue = this.needs.get(part.id());
				if (queue == null) continue;
				int slot = queue.dequeueInt();
				// a hunt-marked target's budget slot already encodes "cheaper than the
				// marked price"; a second reservation would double the bookkeeping.
				// Only a single-part book's price can be attributed to its one target,
				// so combo books never leave a "cheaper only" reservation behind
				boolean huntMarked = this.huntPrices.containsKey(part.id());
				boolean reserveAdded = book.parts().size() == 1 && config.reserveCheaperTraders
						&& !huntMarked && book.price() > floorPrice(part);
				if (reserveAdded) {
					queue.enqueue(book.price() - 1);
				} else if (queue.isEmpty()) {
					this.needs.remove(part.id());
				}
				consumed.add(new ConsumedNeed(part.id(), slot, reserveAdded, book.price()));
			}
		}
		return new MatchedTrade(qualifying, consumed, this.needsVersion);
	}

	/**
	 * The matched villager vanished BEFORE locking (no profession reroll): keep
	 * the budget exactly where the match left it. In reserve mode the match put
	 * a "cheaper only" slot (price-1) into the heap — the seen-price info
	 * survives the villager, so do NOT reset to maxPrice. Only when nothing was
	 * left behind does the consumed slot come back. Runs per consumed need, so a
	 * multi-book match returns every target it deducted.
	 */
	public void restoreBudget(MatchedTrade trade) {
		// a full rebuild since the match already refilled these targets' queues:
		// enqueuing the consumed slots on top would double the budget
		if (trade.needsVersion() != this.needsVersion) return;
		for (ConsumedNeed need : trade.consumed()) {
			// use the reserve flag recorded at match time: the config toggle may
			// have been flipped since, and recomputing it here could double the budget
			if (need.reserveAdded() && this.needs.containsKey(need.id())) continue;
			IntHeapPriorityQueue queue = this.needs.computeIfAbsent(need.id(),
					k -> new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a)));
			queue.enqueue(need.slot());
		}
	}

	/**
	 * Full revert of a match after the lock purchase was rejected past the retry
	 * cap: the villager is still present and unlocked, so nothing about the seen
	 * price is worth keeping. Drop the "cheaper only" reservation added at match
	 * time and return the exact consumed slot, restoring the pre-match budget.
	 * Runs per consumed need.
	 */
	public void undoMatch(MatchedTrade trade) {
		// a full rebuild since the match already refilled these targets' queues:
		// replaying the slot return on top would double the budget
		if (trade.needsVersion() != this.needsVersion) return;
		for (ConsumedNeed need : trade.consumed()) {
			IntHeapPriorityQueue queue = this.needs.get(need.id());
			if (queue != null && need.reserveAdded()) {
				// heaps support no removal: rebuild, skipping one reservation slot
				IntHeapPriorityQueue rebuilt = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
				boolean skipped = false;
				while (!queue.isEmpty()) {
					int slot = queue.dequeueInt();
					if (!skipped && slot == need.seenPrice() - 1) {
						skipped = true;
						continue;
					}
					rebuilt.enqueue(slot);
				}
				this.needs.put(need.id(), rebuilt);
				queue = rebuilt;
			}
			if (queue == null) {
				queue = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
				this.needs.put(need.id(), queue);
			}
			queue.enqueue(need.slot());
		}
	}

	/**
	 * The villager's profession auto-rerolled before the lock, so its offers are
	 * completely fresh. Still honor the "cheaper only" reservation: if the match
	 * added one, the rerolled villager must beat the seen price, so rebuild with
	 * the price-1 slot instead of a full maxPrice slot. Runs per consumed need.
	 */
	public void resetBudget(MatchedTrade trade) {
		// same guard as restoreBudget/undoMatch: a rebuild since the match already
		// refilled these targets' queues; overwriting them with the stale seen-price
		// slots would shrink the freshly applied budget until the next rebuild
		if (trade.needsVersion() != this.needsVersion) return;
		for (ConsumedNeed need : trade.consumed()) {
			// a custom good is absent from `targets`, and without this branch its budget
			// would never be given back: the good would drop out of the needs until the
			// next full rebuild
			GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(need.id());
			// an id that is an enabled TARGET wins over a same-id good (mirrors goodMatchable):
			// otherwise the target's budget would be restored from the good's price
			if (this.targets.get(need.id()) == null && good != null) {
				// an unmarked good is budgeted at its own price, exactly like
				// budgetFor does for a target (taking price-1 here made a legitimate
				// offer at the configured price stop matching, and dropped the good
				// entirely at price 1)
				Integer goodMark = this.huntPrices.get(need.id());
				int goodBudget = goodMark == null ? good.maxPrice()
						: Math.min(goodMark - 1, good.maxPrice());
				if (goodBudget < 1) continue;
				IntHeapPriorityQueue goodQueue = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
				goodQueue.enqueue(need.reserveAdded() ? Math.min(need.seenPrice() - 1, good.maxPrice()) : goodBudget);
				this.needs.put(need.id(), goodQueue);
				continue;
			}
			EnchantmentTarget target = this.targets.get(need.id());
			if (target == null) continue;
			// replace the whole queue: needs holds exactly one slot per enchantment
			// id, and in reserve mode tryMatch leaves a price-1 reservation behind
			// during the match — merging it in again would double-count the need
			IntHeapPriorityQueue queue = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
			queue.enqueue(need.reserveAdded() ? need.seenPrice() - 1 : budgetFor(need.id(), target));
			this.needs.put(need.id(), queue);
		}
	}

	/** Sets the hunt marked prices for this run (controller-owned map). */
	public void setHuntPrices(Map<ResourceLocation, Integer> markedPrices) {
		this.huntPrices = markedPrices;
	}

	/** Sets the recorded combo needs for this run (controller-owned map). */
	public void setComboHuntPrices(Map<Set<MatchPart>, Integer> comboPrices) {
		this.comboHuntPrices = comboPrices;
	}

	/** Key of a combo need: the set of the book's qualifying parts, id AND level
	 * alike — a level difference makes a different combo. */
	public static Set<MatchPart> comboKey(List<MatchPart> parts) {
		return Set.copyOf(parts);
	}

	/** The cheapest a trade for {@code part} can be. An enchantment follows the
	 * vanilla price formula (doubled for double_trade_price entries); an item part
	 * (a custom good, level 0) has no formula and can cost as little as one emerald.
	 * Feeding it the enchantment formula's floor of 2 would treat a price-2 good as
	 * already-bottom and drop the need, silently skipping the cheaper price-1 copy
	 * the reserve option exists to keep hunting. */
	private static int floorPrice(MatchPart part) {
		return part.level() <= 0 ? 1 : TradeMasterConfig.minPrice(part.level(), part.id());
	}

	/** Budget slot for a target: the hunt LOWERS it (marked-1 — only a strictly
	 * cheaper copy is accepted) and the CONFIG CAPS it — so lowering maxPrice
	 * mid-run takes effect at once, while raising it never loosens the hunt. */
	private int budgetFor(ResourceLocation id, EnchantmentTarget target) {
		Integer marked = this.huntPrices.get(id);
		if (marked == null) return target.maxPrice();
		return Math.min(marked - 1, target.maxPrice());
	}

	/** Re-queues every still-enabled hunt-marked target at marked-1. Runs after
	 * buildNeeds/deduct/dropLocked so a locked enchantment is never dropped from
	 * the needs table while hunt mode is on. */
	public void applyHuntOverrides() {
		for (Map.Entry<ResourceLocation, Integer> entry : this.huntPrices.entrySet()) {
			// target id wins over a same-id good (round-30 precedence): the live
			// target set decides first, exactly like priceOf/specOf and admission
			EnchantmentTarget target = this.targets.get(entry.getKey());
			if (target != null) {
				int budget = budgetFor(entry.getKey(), target);
				// villager trades have a price floor of 1 emerald + 1 book: once the
				// budget reaches 0 no cheaper copy can ever exist — the hunt for this book
				// simply stops (the 1→0 ratchet is deliberate). A config price below the
				// vanilla minimum never reaches here: buildNeeds drops such a target, so
				// target is null above and the hunt pauses instead of spinning.
				if (budget < 1) continue;
				IntHeapPriorityQueue queue = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
				queue.enqueue(budget);
				this.needs.put(entry.getKey(), queue);
				continue;
			}
			// a hunt-marked custom good is kept alive on the same terms
			GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(entry.getKey());
			if (good == null) continue;
			if (!good.enabled()) continue;   // disabled mid-run: the hunt pauses
			// a good that can no longer be offered must not keep a hunt alive
			if (!goodMatchable(good)) continue;
			int goodBudget = Math.min(entry.getValue() - 1, good.maxPrice());
			if (goodBudget < 1) continue;
			IntHeapPriorityQueue goodQueue = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
			goodQueue.enqueue(goodBudget);
			this.needs.put(entry.getKey(), goodQueue);
		}
	}

	/** Live-resync between villagers: drops needs whose target was disabled in the
	 * config and picks up targets enabled mid-run, re-applying the hunt ratchet.
	 * Existing queues are untouched, so reserve/hunt budgets survive. Locked ids
	 * are only re-added when hunt-marked with remaining budget — otherwise the
	 * lock voucher would be silently undone by a mid-run config edit. */
	public void resyncTargets(Set<ResourceLocation> lockedIds) {
		List<EnchantmentTarget> enabled = TradeMasterConfig.INSTANCE.enabledTargets();
		Set<ResourceLocation> enabledIds = new HashSet<>();
		for (EnchantmentTarget target : enabled) {
			enabledIds.add(target.id());
			// the same guard buildNeeds applies: a target whose vanilla minimum price
			// exceeds its max can never be offered, and leaving it in `targets` let it
			// ride along as a combo passenger on someone else's budget
			if (!this.huntPrices.containsKey(target.id())
					&& TradeMasterConfig.minPrice(target.level(), target.id()) > target.maxPrice()) continue;
			// a row switched off with an AIR result is not offered either
			if (TradeMasterConfig.INSTANCE.isAirItem(TradeMasterConfig.INSTANCE.specFor(target.id()).result())) continue;
			// refresh the target reference: the user may have edited level/price
			// mid-run and tryMatch's level check reads from this map
			this.targets.put(target.id(), target);
			// No named-deduction guard here, DELIBERATELY: hunt CANCELS the named
			// deduction by design, so a target deducted before hunt turned on must be
			// able to come back. The old deductedByName set could therefore never be
			// read (it was cleared by buildNeeds at run start and only written while
			// hunt was off, i.e. never in this hunt-only path) and was removed
			// 2026-09-25. Restore a guard only if the deduction is ever meant to
			// survive hunt.
			if (this.needs.containsKey(target.id())) continue;
			if (lockedIds.contains(target.id()) && !this.huntPrices.containsKey(target.id())) continue;
			// same guard as buildNeeds: config-budgeted targets that can never match
			// stay out (hunt-marked targets keep their cheaper-than-mark budget)
			if (!this.huntPrices.containsKey(target.id())
					&& TradeMasterConfig.minPrice(target.level(), target.id()) > target.maxPrice()) continue;
			if (!registryTradeable(target.id())) continue;
			int budget = budgetFor(target.id(), target);
			// hunt budget exhausted (marked ≤ 1): nothing cheaper can exist
			if (budget < 1) continue;
			IntHeapPriorityQueue queue = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
			queue.enqueue(budget);
			this.needs.put(target.id(), queue);
		}
		for (GoodSpec good : TradeMasterConfig.INSTANCE.enabledGoods()) {
			// a good that can no longer be offered (result item gone, price cap 0, ...)
			// is dropped from the enabled set, so the removeIf below prunes it
			if (!goodMatchable(good)) continue;
			enabledIds.add(good.id());
			if (this.needs.containsKey(good.id())) continue;
			if (lockedIds.contains(good.id()) && !this.huntPrices.containsKey(good.id())) continue;
			int budget = this.huntPrices.containsKey(good.id())
					? Math.min(this.huntPrices.get(good.id()) - 1, good.maxPrice())
					: good.maxPrice();
			if (budget < 1) continue;
			IntHeapPriorityQueue queue = new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a));
			queue.enqueue(budget);
			this.needs.put(good.id(), queue);
		}
		this.needs.keySet().removeIf(id -> !enabledIds.contains(id)
				|| (!isGood(id) && !registryTradeable(id)));
		this.targets.keySet().removeIf(id -> !enabledIds.contains(id)
				|| (!isGood(id) && !registryTradeable(id)));
	}

	/**
	 * Scans named librarian villagers and deducts their enchantments from the
	 * remaining needs so already-captured villagers are not re-rolled. Name
	 * format: one or more books, each "{display} {price}"; a book's display is
	 * its matched enchantments joined by "+" (each "{name} {roman}"). The legacy
	 * single-book "{name} {roman} {price}" format parses as one book.
	 */
	public void deductNamedVillagers(List<Villager> villagers) {
		Map<String, ResourceLocation> nameToId = buildDisplayNameIndex();
		for (Villager villager : villagers) {
			Component customName = villager.getCustomName();
			if (customName == null) continue;
			Set<ResourceLocation> deductedHere = new HashSet<>();
			for (NamedBook book : parseVillagerBooks(customName.getString(), nameToId)) {
				for (NamedPart part : book.parts()) {
					ResourceLocation id = nameToId.get(EnchantmentNamer.canonical(part.name()));
					if (id == null || !deductedHere.add(id)) continue;
					IntHeapPriorityQueue queue = this.needs.get(id);
					if (queue == null) continue;
					EnchantmentTarget target = this.targets.get(id);
					// no price check: a named villager is already captured for this need —
					// deduct it regardless of its live price (curing discounts can drift it),
					// otherwise the need stays live and the run re-rolls a duplicate
					// a custom good has no level to satisfy: its part is the item itself
					// (level 0), and without this branch the deduction would skip it and
					// the good would be farmed again in the next session
					boolean counts = target != null
							? part.level() >= target.level()
							: TradeMasterConfig.INSTANCE.goodFor(id) != null;
					if (counts) {
						// the dequeue IS the deduction; it needs no cross-tick flag
						// (see resyncTargets: hunt, the only resync trigger, re-adds
						// by design)
						queue.dequeueInt();
						if (queue.isEmpty()) this.needs.remove(id);
					} else {
						// level too low (or target gone): another book on this villager
						// may still satisfy the need
						deductedHere.remove(id);
					}
				}
			}
		}
	}

	private record NamedPart(String name, int level) {}
	private record NamedBook(List<NamedPart> parts, int price) {}

	/** Books are the segments between price tokens: a maximal run of non-integer
	 * tokens followed by an integer. Display names can contain spaces, so the
	 * split must key off the prices, not fixed positions. {@code nameToId} is the
	 * deduction index, used to disambiguate a name that ends in a roman-looking token. */
	private static List<NamedBook> parseVillagerBooks(String name, Map<String, ResourceLocation> nameToId) {
		String[] tokens = name.trim().split("\\s+");
		List<NamedBook> books = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < tokens.length; i++) {
			int price;
			try {
				price = Integer.parseInt(tokens[i]);
			} catch (NumberFormatException e) {
				continue;
			}
			if (i > start) {
				List<NamedPart> parts = parseBookSpan(java.util.Arrays.asList(tokens).subList(start, i), nameToId);
				if (!parts.isEmpty()) books.add(new NamedBook(parts, price));
			}
			start = i + 1;
		}
		return books;
	}

	private static List<NamedPart> parseBookSpan(List<String> span, Map<String, ResourceLocation> nameToId) {
		// a detached trailing level token: legacy "{name} {roman} {price}" and
		// combo displays whose last part's roman sits after a space
		String last = span.get(span.size() - 1);
		int detachedLevel = parseLevel(last);
		if (detachedLevel > 0 && span.size() >= 2) {
			// a name whose own text ends in a roman-looking token (a custom good named
			// "... IV") would otherwise be read as "{name} {level}" and never resolve
			// back. Only a GOOD is named without a level, so the whole-span reading is
			// applied for a good alone: for an enchantment the legacy "{name} {roman}"
			// reading must win, or an alias literally equal to "{name} {roman}" would
			// strand its own book.
			String whole = String.join(" ", span);
			String rest = String.join(" ", span.subList(0, span.size() - 1));
			ResourceLocation restId = rest.isEmpty() ? null
					: nameToId.get(EnchantmentNamer.canonical(rest));
			// the legacy "{name} {roman}" reading wins when `rest` really is an enchantment:
			// otherwise a good whose name happens to BE "{name} {roman}" would shadow that
			// enchantment and its own alias could never resolve back
			if (restId == null || !TradeMasterConfig.INSTANCE.enchantments.containsKey(restId)) {
				ResourceLocation wholeId = nameToId.get(EnchantmentNamer.canonical(whole));
				if (wholeId != null && TradeMasterConfig.INSTANCE.goodFor(wholeId) != null) {
					return List.of(new NamedPart(whole, 0));
				}
			}
			if (!rest.contains("+")) {
				return rest.isEmpty() ? List.of() : List.of(new NamedPart(rest, detachedLevel));
			}
			List<NamedPart> parts = new ArrayList<>();
			String[] subs = rest.split("\\+");
			for (int k = 0; k < subs.length; k++) {
				String sub = k == subs.length - 1 ? subs[k] + " " + last : subs[k];
				NamedPart part = splitNameLevel(sub);
				if (part == null) return List.of();
				parts.add(part);
			}
			return parts;
		}
		// no detached level: every part carries its roman glued to the name,
		// parts joined by "+" within one or more tokens
		List<NamedPart> parts = new ArrayList<>();
		for (String token : span) {
			for (String sub : token.split("\\+")) {
				NamedPart part = splitNameLevel(sub);
				if (part != null) {
					parts.add(part);
					continue;
				}
				// a part with NO level token: a custom good is named with just the item's
				// name (the level is a BOOK's format anchor). The whole span is one such
				// name — a book's parts always carry their level — and a level of 0 fails
				// every enchantment's level check, so this cannot mis-deduct an enchantment
				String whole = String.join(" ", span).trim();
				if (whole.isEmpty()) return List.of();
				return List.of(new NamedPart(whole, 0));
			}
		}
		return parts;
	}

	/** "{name} {roman}" / "{name}+..." style part text: the level is the trailing
	 * run of roman-numeral characters (or decimal digits); the rest is the name. */
	private static @Nullable NamedPart splitNameLevel(String text) {
		String trimmed = text.trim();
		int cut = trimmed.length();
		while (cut > 0 && isLevelChar(trimmed.charAt(cut - 1))) cut--;
		if (cut == 0 || cut == trimmed.length()) return null;
		String levelText = trimmed.substring(cut);
		int level = parseLevel(levelText);
		if (level <= 0) return null;
		String displayName = trimmed.substring(0, cut).trim();
		if (displayName.isEmpty()) return null;
		return new NamedPart(displayName, level);
	}

	private static boolean isLevelChar(char c) {
		return c == 'I' || c == 'V' || c == 'X' || c == 'L' || c == 'C' || c == 'D' || c == 'M'
				|| (c >= '0' && c <= '9');
	}

	private static int parseLevel(String text) {
		int level = romanToInt(text);
		if (level > 0) return level;
		// romanNumerals falls back to plain decimal above 3999; accept that too
		try {
			level = Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return -1;
		}
		return level > 0 ? level : -1;
	}

	private Map<String, ResourceLocation> buildDisplayNameIndex() {
		Map<String, ResourceLocation> index = new HashMap<>();
		Set<String> ambiguous = new HashSet<>();
		for (ResourceLocation id : this.targets.keySet()) {
			String translationKey = "enchantment." + id.getNamespace() + "." + id.getPath();
			putIndex(index, ambiguous, EnchantmentNamer.canonical(EnchantmentNamer.displayName(id, translationKey)), id);
			putIndex(index, ambiguous, EnchantmentNamer.canonical(id.getPath()), id);
		}
		// custom goods are identified by their result item, and a named villager
		// carries that item's display name
		for (GoodSpec good : TradeMasterConfig.INSTANCE.enabledGoods()) {
			// the same triple an enchantment gets: the (alias aware) display name and
			// the registry path, so a villager named either way resolves back
			putIndex(index, ambiguous,
					EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(good.displayName())), good.id());
			putIndex(index, ambiguous, EnchantmentNamer.canonical(good.path()), good.id());
		}
		// names recorded when villagers were actually named: keeps deduction working
		// when the game language changed since naming
		for (Map.Entry<String, ResourceLocation> entry : TradeMasterConfig.INSTANCE.nameHistory.entrySet()) {
			// enchantment targets AND custom goods: an item's name is localized too, so
			// a good named before a language switch needs its history entry as well
			if (this.targets.containsKey(entry.getValue())
					|| TradeMasterConfig.INSTANCE.goodFor(entry.getValue()) != null) {
				putIndex(index, ambiguous, EnchantmentNamer.canonical(entry.getKey()), entry.getValue());
			}
		}
		// a name shared by two targets cannot be attributed: drop it rather than
		// crediting the wrong enchantment (worst case the villager is not deducted)
		ambiguous.forEach(index::remove);
		return index;
	}

	/** Order-insensitive cost check (the agreed rule): every slot the offer charges must be
	 * declared by one of the matched targets' specs, and the total charged per ITEM may not
	 * exceed the total the specs allow, so the slots' ORDER does not matter here. Slot 0 is
	 * still the price slot for the CALLER (it reads the offer's slot 0 as the price) — the
	 * allowances are summed per item, the price is positional. Slots declared but not charged
	 * are fine — the player simply pays less. A slot whose item is an "air" item is not a cost
	 * on either side, so a server that spells "free" as air works, and a spec field left empty
	 * means "not required". */
	private boolean costsAllowed(MerchantOffer offer, List<MatchPart> parts) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		Map<ResourceLocation, Integer> allowed = new HashMap<>();
		for (MatchPart part : parts) {
			int price = priceOf(part.id());
			List<TradeOfferSpec.TradeCost> costs = specOf(part.id()).costs();
			for (int slot = 0; slot < costs.size(); slot++) {
				TradeOfferSpec.TradeCost cost = costs.get(slot);
				if (cost == null || cost.item() == null || config.isAirItem(cost.item())) continue;
				// slot 1 is the price slot: its amount stays the target's own price, so
				// the price / budget / hunt scale is unchanged. A slot count of 0 means
				// "not required" (like an air item), a negative count means "no limit"
				// (the default for the book slot kept the old uncapped behaviour)
				int amount = TradeOfferSpec.capFor(slot, cost, price);
				if (amount <= 0) continue;
				allowed.merge(cost.item(), amount, Integer::sum);
			}
		}
		Map<ResourceLocation, Integer> charged = new HashMap<>();
		for (ItemStack stack : OfferCosts.pricingSlots(offer)) {
			if (stack == null || stack.isEmpty()) continue;
			ResourceLocation item = McBuiltIn.items().getKey(stack.getItem());
			if (item == null || config.isAirItem(item)) continue;
			// something the specs never declared: buying it would cost an item the
			// player did not agree to pay
			if (!allowed.containsKey(item)) return false;
			charged.merge(item, stack.getCount(), Integer::sum);
		}
		for (Map.Entry<ResourceLocation, Integer> entry : charged.entrySet()) {
			if (entry.getValue() > allowed.getOrDefault(entry.getKey(), 0)) return false;
		}
		return true;
	}

	private static void putIndex(Map<String, ResourceLocation> index, Set<String> ambiguous, String name, ResourceLocation id) {
		ResourceLocation existing = index.put(name, id);
		if (existing != null && !existing.equals(id)) ambiguous.add(name);
	}

	private static int romanToInt(String roman) {
		int total = 0;
		int prev = 0;
		for (int i = roman.length() - 1; i >= 0; i--) {
			int value = switch (roman.charAt(i)) {
				case 'I' -> 1;
				case 'V' -> 5;
				case 'X' -> 10;
				case 'L' -> 50;
				case 'C' -> 100;
				case 'D' -> 500;
				case 'M' -> 1000;
				default -> 0;
			};
			if (value == 0) return -1;
			if (value < prev) total -= value;
			else total += value;
			prev = value;
		}
		return total;
	}
}
