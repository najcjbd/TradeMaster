package dev.trademaster.exec;

import dev.trademaster.matching.TradeMatcher;
import net.minecraft.client.Minecraft;
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
	private int stage = 0;
	private int pickupWaitTicks = 0;
	private boolean drifted = false;

	public LockTradeExecutor(MerchantMenu menu, int slot, TradeMatcher.@Nullable MatchedTrade expected) {
		this.menu = menu;
		this.slot = slot;
		this.expected = expected;
	}

	public MerchantMenu menu() {
		return this.menu;
	}

	/** True once the purchase click has been sent and the server-side lock is underway. */
	public boolean purchaseSent() {
		return this.stage >= 2;
	}

	/** True once the purchased item was seen on the cursor and the menu closed —
	 * the purchase is certain. purchaseSent() alone is not: a rejected trade also
	 * reaches that stage, and the carried check is what separates the two. */
	public boolean purchaseConfirmed() {
		return this.stage >= 3;
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
				this.stage = 3;
			}
			case 3 -> {
				return Result.DONE;
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
		if (offer.getBaseCostA().getCount() != this.expected.price()) return false;
		return bookHas(this.expected, offer.getResult());
	}

	private boolean carriedMatches() {
		// Defensive, unreachable today (audited 2026-09-19): see slotStillMatches.
		if (this.expected == null) return true;
		return bookHas(this.expected, this.menu.getCarried());
	}

	/** Verify the book IS the matched book: its full enchantment set must equal the
	 * set recorded at match time (purchase().bookEnchantments()), ids and levels alike.
	 * Datapack/mod offers can put several enchantments on one book, so the whole set
	 * is the fingerprint — a regenerated offer would differ in at least one entry. */
	private static boolean bookHas(TradeMatcher.MatchedTrade expected, ItemStack book) {
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
