package dev.trademaster.matching;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.exec.EnchantmentNamer;
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.npc.villager.Villager;
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

	public record MatchPart(Identifier id, int level) {}

	/** One qualifying enchanted-book offer. {@code parts} are the enchantments that
	 * matched an enabled target at sufficient level; {@code bookEnchantments} is the
	 * book's FULL set (lock fingerprints must reject any change to it, matched or not). */
	public record MatchedBook(List<MatchPart> parts, List<MatchPart> bookEnchantments, int price, int offerIndex) {}

	/** Budget bookkeeping for one deducted need, so restore/undo/reset can return
	 * exactly what the match consumed. {@code seenPrice} is the book price that was
	 * on the table when the need was deducted (reserve removal keys off it). */
	public record ConsumedNeed(Identifier id, int slot, boolean reserveAdded, int seenPrice) {}

	/** A match against one villager: every qualifying enchanted-book offer
	 * (cheapest first — index 0 is the one purchased; a single trade suffices to
	 * lock the villager) plus the needs deducted for the targets they cover. */
	public record MatchedTrade(List<MatchedBook> books, List<ConsumedNeed> consumed, int needsVersion) {
		public MatchedBook purchase() { return this.books.get(0); }
		public Identifier id() { return purchase().parts().get(0).id(); }
		public int level() { return purchase().parts().get(0).level(); }
		public int price() { return purchase().price(); }
		public int offerIndex() { return purchase().offerIndex(); }
		/** Every matched target id across all qualifying books, first-seen order. */
		public List<Identifier> matchedIds() {
			List<Identifier> ids = new ArrayList<>();
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
					EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(part.id());
					if (target != null && part.level() < target.level()) return true;
				}
			}
			return false;
		}
		/** Whether any matched target was removed or disabled in the config since
		 * the match (a mid-run edit that invalidates the combo). */
		public boolean anyTargetGone() {
			for (Identifier id : matchedIds()) {
				EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(id);
				if (target == null || !target.enabled()) return true;
			}
			return false;
		}
	}

	/** per-enchantment heap of acceptable prices (max heap) */
	private final HashMap<Identifier, IntHeapPriorityQueue> needs = new HashMap<>();
	private final Map<Identifier, EnchantmentTarget> targets = new LinkedHashMap<>();
	/** Bumped on every full buildNeeds rebuild: a budget return (restore/undo)
	 * carrying an older version would enqueue into a queue the rebuild already
	 * refilled, doubling the budget — the version check drops such returns. */
	private int needsVersion = 0;
	/** Hunt-cheaper mode: locked price per enchantment this run. A hunt-marked
	 * target's budget is marked-1, so only a strictly cheaper copy can match. */
	private Map<Identifier, Integer> huntPrices = Map.of();
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
			// a target whose vanilla minimum price exceeds the configured max can
			// never match: keep it out of the needs (start() warns about it)
			if (TradeMasterConfig.minPrice(target.level(), target.id()) > target.maxPrice()) continue;
			// a kept-but-dead config entry (enchantment removed from the registry
			// or untradeable after a datapack/mod change) can never be offered by a
			// villager either — Select All could still enable it, and without this
			// guard the run would spin forever on a need nothing can satisfy
			if (!registryTradeable(target.id())) continue;
			this.targets.put(target.id(), target);
			this.needs.computeIfAbsent(target.id(),
							k -> new IntHeapPriorityQueue((a, b) -> Integer.compare(b, a)))
					.enqueue(target.maxPrice());
		}
	}

	/** Whether the enchantment currently exists in the live registry and carries
	 * the TRADEABLE tag (the same gate syncEnchantmentsFromRegistry uses). No
	 * level/registry available yet means assume available — the check re-runs on
	 * every rebuild. */
	public static boolean registryTradeable(Identifier id) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return true;
		Registry<Enchantment> registry = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		Holder<Enchantment> holder = registry.get(id).orElse(null);
		return holder != null && holder.is(EnchantmentTags.TRADEABLE);
	}

	public boolean isEmpty() {
		return this.needs.isEmpty();
	}

	/** Drops targets already locked this run: the rebuild in buildNeeds wiped the
	 * in-session memory, and without naming/auto-disable no other voucher exists. */
	public void dropLocked(Set<Identifier> lockedIds) {
		for (Identifier id : lockedIds) {
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
			ItemStack costA = offer.getBaseCostA();
			ItemStack costB = offer.getCostB();
			ItemStack result = offer.getResult();
			if (costA.getItem() != Items.EMERALD || costB.getItem() != Items.BOOK
					|| !result.is(Items.ENCHANTED_BOOK)) {
				continue;
			}
			var enchantments = EnchantmentHelper.getEnchantmentsForCrafting(result);
			if (enchantments.isEmpty()) continue;
			int price = costA.getCount();
			List<MatchPart> bookEnchantments = new ArrayList<>();
			List<MatchPart> parts = new ArrayList<>();
			int budget = 0;
			for (var entry : enchantments.entrySet()) {
				Identifier id = entry.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
				int level = entry.getIntValue();
				if (id != null) bookEnchantments.add(new MatchPart(id, level));
				if (id == null) continue;
				// qualifying is decided by the live config (enabled + level satisfied),
				// NOT needs presence: a recorded combo ratchet still applies to the
				// same combo re-appearing mid-run even after its targets were locked
				// out of the needs table (the record itself never keeps the run
				// alive — live primary needs decide that)
				EnchantmentTarget target = this.targets.get(id);
				if (target == null || level < target.level()) continue;
				parts.add(new MatchPart(id, level));
				IntHeapPriorityQueue queue = this.needs.get(id);
				if (queue != null) budget += queue.firstInt();
			}
			if (parts.isEmpty()) continue;
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
			qualifying.add(new MatchedBook(parts, bookEnchantments, price, i));
		}
		if (qualifying.isEmpty()) return null;
		// one purchase locks the villager: buy the cheapest qualifying book
		qualifying.sort(Comparator.comparingInt(MatchedBook::price));
		List<ConsumedNeed> consumed = new ArrayList<>();
		Set<Identifier> deducted = new HashSet<>();
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
						&& !huntMarked && book.price() > TradeMasterConfig.minPrice(part.level(), part.id());
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
	public void setHuntPrices(Map<Identifier, Integer> markedPrices) {
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

	/** Budget slot for a target: the hunt LOWERS it (marked-1 — only a strictly
	 * cheaper copy is accepted) and the CONFIG CAPS it — so lowering maxPrice
	 * mid-run takes effect at once, while raising it never loosens the hunt. */
	private int budgetFor(Identifier id, EnchantmentTarget target) {
		Integer marked = this.huntPrices.get(id);
		if (marked == null) return target.maxPrice();
		return Math.min(marked - 1, target.maxPrice());
	}

	/** Re-queues every still-enabled hunt-marked target at marked-1. Runs after
	 * buildNeeds/deduct/dropLocked so a locked enchantment is never dropped from
	 * the needs table while hunt mode is on. */
	public void applyHuntOverrides() {
		for (Map.Entry<Identifier, Integer> entry : this.huntPrices.entrySet()) {
			EnchantmentTarget target = this.targets.get(entry.getKey());
			if (target == null) continue; // disabled mid-run: the hunt pauses
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
		}
	}

	/** Live-resync between villagers: drops needs whose target was disabled in the
	 * config and picks up targets enabled mid-run, re-applying the hunt ratchet.
	 * Existing queues are untouched, so reserve/hunt budgets survive. Locked ids
	 * are only re-added when hunt-marked with remaining budget — otherwise the
	 * lock voucher would be silently undone by a mid-run config edit. */
	public void resyncTargets(Set<Identifier> lockedIds) {
		List<EnchantmentTarget> enabled = TradeMasterConfig.INSTANCE.enabledTargets();
		Set<Identifier> enabledIds = new HashSet<>();
		for (EnchantmentTarget target : enabled) {
			enabledIds.add(target.id());
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
		this.needs.keySet().removeIf(id -> !enabledIds.contains(id) || !registryTradeable(id));
		this.targets.keySet().removeIf(id -> !enabledIds.contains(id) || !registryTradeable(id));
	}

	/**
	 * Scans named librarian villagers and deducts their enchantments from the
	 * remaining needs so already-captured villagers are not re-rolled. Name
	 * format: one or more books, each "{display} {price}"; a book's display is
	 * its matched enchantments joined by "+" (each "{name} {roman}"). The legacy
	 * single-book "{name} {roman} {price}" format parses as one book.
	 */
	public void deductNamedVillagers(List<Villager> villagers) {
		Map<String, Identifier> nameToId = buildDisplayNameIndex();
		for (Villager villager : villagers) {
			Component customName = villager.getCustomName();
			if (customName == null) continue;
			Set<Identifier> deductedHere = new HashSet<>();
			for (NamedBook book : parseVillagerBooks(customName.getString())) {
				for (NamedPart part : book.parts()) {
					Identifier id = nameToId.get(EnchantmentNamer.canonical(part.name()));
					if (id == null || !deductedHere.add(id)) continue;
					IntHeapPriorityQueue queue = this.needs.get(id);
					if (queue == null) continue;
					EnchantmentTarget target = this.targets.get(id);
					// no price check: a named villager is already captured for this need —
					// deduct it regardless of its live price (curing discounts can drift it),
					// otherwise the need stays live and the run re-rolls a duplicate
					if (target != null && part.level() >= target.level()) {
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
	 * split must key off the prices, not fixed positions. */
	private static List<NamedBook> parseVillagerBooks(String name) {
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
				List<NamedPart> parts = parseBookSpan(java.util.Arrays.asList(tokens).subList(start, i));
				if (!parts.isEmpty()) books.add(new NamedBook(parts, price));
			}
			start = i + 1;
		}
		return books;
	}

	private static List<NamedPart> parseBookSpan(List<String> span) {
		// a detached trailing level token: legacy "{name} {roman} {price}" and
		// combo displays whose last part's roman sits after a space
		String last = span.get(span.size() - 1);
		int detachedLevel = parseLevel(last);
		if (detachedLevel > 0 && span.size() >= 2) {
			String rest = String.join(" ", span.subList(0, span.size() - 1));
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
				if (part == null) return List.of();
				parts.add(part);
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

	private Map<String, Identifier> buildDisplayNameIndex() {
		Map<String, Identifier> index = new HashMap<>();
		Set<String> ambiguous = new HashSet<>();
		for (Identifier id : this.targets.keySet()) {
			String translationKey = "enchantment." + id.getNamespace() + "." + id.getPath();
			putIndex(index, ambiguous, EnchantmentNamer.canonical(EnchantmentNamer.displayName(id, translationKey)), id);
			putIndex(index, ambiguous, EnchantmentNamer.canonical(id.getPath()), id);
		}
		// names recorded when villagers were actually named: keeps deduction working
		// when the game language changed since naming
		for (Map.Entry<String, Identifier> entry : TradeMasterConfig.INSTANCE.nameHistory.entrySet()) {
			if (this.targets.containsKey(entry.getValue())) {
				putIndex(index, ambiguous, EnchantmentNamer.canonical(entry.getKey()), entry.getValue());
			}
		}
		// a name shared by two targets cannot be attributed: drop it rather than
		// crediting the wrong enchantment (worst case the villager is not deducted)
		ambiguous.forEach(index::remove);
		return index;
	}

	private static void putIndex(Map<String, Identifier> index, Set<String> ambiguous, String name, Identifier id) {
		Identifier existing = index.put(name, id);
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
