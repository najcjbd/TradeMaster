package dev.trademaster.exec;

import dev.trademaster.core.Preconditions;
import dev.trademaster.matching.OfferCosts;
import dev.trademaster.matching.TradeMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Performs the one-time trade that locks the villager's profession.
 * Tick-driven: select trade -> pick up result -> close container.
 *
 * Every stage re-verifies the expected offer fingerprint (enchantment id,
 * level, price) recorded at match time: the merchant menu is client-side
 * state, and an unlocked villager's profession can auto-reroll while the
 * packets of this purchase are in flight — the server would then apply the
 * clicks to regenerated offers and sell a different book at a different
 * price. A fingerprint mismatch fails the executor as a DRIFT, which the
 * controller routes to a free reroll instead of the server-rejection retry.
 */
public class LockTradeExecutor {

	public enum Result { RUNNING, DONE, FAILED }

	private final MerchantMenu menu;
	private final int slot;
	private final TradeMatcher.@Nullable MatchedTrade expected;
	/** Books matching the purchased fingerprint carried before the click: measured the
	 * same way as the confirmation, so an unrelated book picked up or dropped during
	 * the wait cannot flip the verdict. */
	private final int preBookCount;
	private int stage = 0;
	private int pickupWaitTicks = 0;
	private int confirmTicks = 0;
	private boolean drifted = false;
	private boolean verified = false;
	private boolean uncertain = false;

	public LockTradeExecutor(MerchantMenu menu, int slot, TradeMatcher.@Nullable MatchedTrade expected) {
		this.menu = menu;
		this.slot = slot;
		this.expected = expected;
		Minecraft mc = Minecraft.getInstance();
		this.preBookCount = mc.player == null ? 0 : matchingBookCount(mc.player);
	}

	/** Books in the inventory matching the purchased fingerprint (every enchanted book
	 * when there is no fingerprint, i.e. EVENT-mode detections). */
	private int matchingBookCount(LocalPlayer player) {
		int count = 0;
		for (int i = 0; i < 36; i++) {
			count += matchingCount(player.getInventory().getItem(i));
		}
		count += matchingCount(player.getOffhandItem());
		return count;
	}

	private int matchingCount(ItemStack stack) {
		if (this.expected == null) return stack.is(Items.ENCHANTED_BOOK) ? stack.getCount() : 0;
		return resultMatches(this.expected, stack) ? stack.getCount() : 0;
	}

	public MerchantMenu menu() {
		return this.menu;
	}

	/** True once the purchase click has been sent and the server-side lock is underway. */
	public boolean purchaseSent() {
		return this.stage >= 2;
	}

	/** True once the purchase is PROVEN by the server, not by our own prediction:
	 * {@code handleContainerInput} runs {@code AbstractContainerMenu.clicked} locally
	 * before the packet, so "a book is on the cursor" is client state only. The proof
	 * is the server moving the held book into the inventory when the container closes
	 * ({@code AbstractContainerMenu.removed} -> {@code dropOrPlaceInInventory}, which
	 * runs for ServerPlayer only) — see the stage-3 check. */
	public boolean purchaseConfirmed() {
		return this.verified;
	}

	/** True once we closed the container ourselves (stage 3 onwards). The controller
	 * must not read the resulting menu mismatch as "the server closed it". */
	public boolean closing() {
		return this.stage >= 3;
	}

	/** True when the click was sent but the confirmation never arrived: the trade may
	 * or may not have happened. Retrying the purchase would buy a SECOND book (the
	 * offer still has uses left), so the caller must not treat this as a rejection. */
	public boolean uncertain() {
		return this.uncertain;
	}

	/** The failure was an offer drift (reroll in flight), not a server rejection:
	 * the villager is still unlocked, so the right recovery is a free reroll,
	 * not another purchase attempt at the same slot. */
	public boolean drifted() {
		return this.drifted;
	}

	public Result tick() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gameMode == null) return Result.FAILED;
		switch (this.stage) {
			case 0 -> {
				if (!slotStillMatches()) {
					this.drifted = true;
					return Result.FAILED;
				}
				this.menu.setSelectionHint(this.slot);
				this.menu.tryMoveItems(this.slot);
				client.player.connection.send(new ServerboundSelectTradePacket(this.slot));
				this.stage = 1;
			}
			case 1 -> {
				// SELECT_TRADE alone costs nothing: if the offers regenerated since
				// it went out (the resync packet can land between stages), abort
				// before the PICKUP click so the server never deducts a drifted price
				if (!slotStillMatches()) {
					this.drifted = true;
					return Result.FAILED;
				}
				client.gameMode.handleContainerInput(this.menu.containerId, 2, 0, ContainerInput.PICKUP, client.player);
				this.pickupWaitTicks = 0;
				this.stage = 2;
			}
			case 2 -> {
				// a rejected trade leaves the result slot empty and nothing on the
				// cursor: verify the item actually landed before closing, otherwise
				// the run would treat the villager as locked and dispatch it
				if (this.menu.getCarried().isEmpty()) {
					// deliberately NOT flagged like the stage-3 timeout: the client's
					// own prediction puts the book on the cursor whenever the click was
					// applied, so an empty cursor here means the click did not go
					// through at all — retrying this one buys nothing twice
					if (++this.pickupWaitTicks > 10) return Result.FAILED;
					return Result.RUNNING;
				}
				// the purchase went through but the book is not the matched one:
				// the server applied the clicks to regenerated offers. Nothing can
				// be un-bought — fail as a drift so the controller re-evaluates
				// instead of locking/naming under the stale target
				if (!carriedMatches()) {
					this.drifted = true;
					return Result.FAILED;
				}
				client.player.closeContainer();
				this.confirmTicks = 0;
				this.stage = 3;
			}
			case 3 -> {
				// Server-authoritative confirmation: closing the container makes the
				// SERVER put the held book into the inventory (dropOrPlaceInInventory);
				// the client's own prediction never does. The book count rising is
				// therefore proof the trade really happened, and its absence means the
				// server refused the click — a rejection the cursor check cannot see
				if (client.player != null
						&& matchingBookCount(client.player) > this.preBookCount) {
					this.verified = true;
					return Result.DONE;
				}
				// with no free slot the server DROPS the book instead, which is not
				// observable here: trust the prediction rather than mis-report a
				// purchase that did go through
				if (client.player != null && !Preconditions.hasFreeSlot(client.player)) {
					this.verified = true;
					return Result.DONE;
				}
				// 60 ticks (about 3s at 20 tps; longer if the client itself stalls,
				// since this counter rides the client tick), in line with the other
				// slot-sync waits. Timing out means the outcome is UNKNOWN, not that
				// the trade failed: the caller must not re-click, or a slow-but-
				// successful server gets a second purchase
				if (++this.confirmTicks > 60) {
					this.uncertain = true;
					return Result.FAILED;
				}
				return Result.RUNNING;
			}
		}
		return Result.RUNNING;
	}

	/** The offer at the locked slot must still be exactly the matched one:
	 * same enchanted book, level and emerald price. EVENT-mode detections run
	 * without a matched trade (expected == null) and skip the check. */
	private boolean slotStillMatches() {
		// Defensive, unreachable today (audited 2026-09-19): LOCK always supplies a
		// fingerprint (RerollController:1156), so expected is never null here.
		if (this.expected == null) return true;
		var offers = this.menu.getOffers();
		if (this.slot < 0 || this.slot >= offers.size()) return false;
		var offer = offers.get(this.slot);
		if (offer.isOutOfStock()) return false;
		if (offer.getBaseCostA().getItem() != Items.EMERALD || offer.getCostB().getItem() != Items.BOOK
				|| !offer.getResult().is(Items.ENCHANTED_BOOK)) return false;
		// the whole cost shape, not just the price: a custom spec may pay in another
		// item, and a regenerated offer must not be applied to this click
		if (!this.expected.purchase().costsMatch(OfferCosts.slots(offer))) return false;
		return resultMatches(this.expected, offer.getResult());
	}

	private boolean carriedMatches() {
		// Defensive, unreachable today (audited 2026-09-19): see slotStillMatches.
		if (this.expected == null) return true;
		return resultMatches(this.expected, this.menu.getCarried());
	}

	/** Verify the book IS the matched book: its full enchantment set must equal the
	 * set recorded at match time (purchase().bookEnchantments()), ids and levels alike.
	 * Datapack/mod offers can put several enchantments on one book, so the whole set
	 * is the fingerprint — a regenerated offer would differ in at least one entry. */
	private static boolean resultMatches(TradeMatcher.MatchedTrade expected, ItemStack stack) {
		ItemStack expectedResult = expected.purchase().result();
		if (!expectedResult.is(Items.ENCHANTED_BOOK)) {
			// a custom good: the result item (and at least the matched amount) must match
			return stack.is(expectedResult.getItem()) && stack.getCount() >= expectedResult.getCount();
		}
		ItemStack book = stack;
		if (!book.is(Items.ENCHANTED_BOOK)) return false;
		var enchantments = EnchantmentHelper.getEnchantmentsForCrafting(book);
		var expectedParts = expected.purchase().bookEnchantments();
		if (enchantments.size() != expectedParts.size()) return false;
		for (var part : expectedParts) {
			boolean found = false;
			for (var entry : enchantments.entrySet()) {
				var id = entry.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
				if (id != null && id.equals(part.id()) && entry.getIntValue() == part.level()) {
					found = true;
					break;
				}
			}
			if (!found) return false;
		}
		return true;
	}
}
