package dev.trademaster.exec;

import dev.trademaster.compat.McPlayer;
import dev.trademaster.anticheat.LookStep;
import dev.trademaster.anticheat.SmoothRotation;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.Interactions;
import dev.trademaster.core.Preconditions;
import dev.trademaster.strategy.RerollStrategy.StepResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import dev.trademaster.compat.McClicks;
import dev.trademaster.compat.McPackets;
import dev.trademaster.compat.McItem;
import dev.trademaster.compat.McText;
import dev.trademaster.compat.McInteract;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Deposits enchanted books into the bound chests, in order, when the
 * inventory is full. One book moved per tick (anti-cheat). A chest that
 * accepts nothing is treated as full and skipped; when every chest is
 * full the run stops.
 */
public class ChestDepositor implements BookDisposal {

	private enum State { SEEK, AWAIT_OPEN, DEPOSIT, EVALUATE, DROP_STAGE, DROP_STAGE_WAIT, DROP_AIM, DROP_WAIT }

	private record BookScan(@Nullable Slot firstSlot, int count) {}

	private final List<BlockPos> chests;
	private final LookStep look = new LookStep();
	private int index = 0;
	private State state = State.SEEK;
	private int ticks = 0;
	private int openWait = 0;
	private int openRetries = 0;
	private int delay;
	private int lastBookCount = -1;
	private int noProgressTicks = 0;
	private boolean switchedSlot = false;
	private boolean offhandSwapped = false;
	/** Hotbar slot an offhand staging swap sent the book into, or -1. The swap also
	 * displaces that slot's item into the offhand, so it is undone once the book is
	 * gone — otherwise the item stays in the offhand after the deposit ends. Survives
	 * advance() (the displacement outlives a single chest). */
	private int stagedHotbarSlot = -1;
	/** Item that staging swap pushed into the offhand (a copy), so the undo can prove
	 * the offhand still holds exactly that and not something the player put there. */
	private ItemStack stagedDisplaced = ItemStack.EMPTY;
	/** The book/good the staging swap moved into the hotbar slot (a copy). The undo runs
	 * once that slot is empty (the item was deposited/dropped) OR still holds exactly
	 * this — either way the displaced item is returned to where it came from. */
	private ItemStack stagedBook = ItemStack.EMPTY;
	/** Ticks spent waiting for the server's offhand-swap slot sync to arrive;
	 * -1 while not waiting. */
	private int swapWaitTicks = -1;
	/** Offhand-swap retries spent on the current chest (R32-04). */
	private int swapAttempts = 0;
	/** A rejected swap scheduled for resend after a short delay. */
	private boolean swapRetryPending = false;
	private int swapRetryDelay = 0;
	/** Menu state id when the offhand SWAP was sent, and how many ticks it has stayed
	 * unchanged. An accepted swap always changes the menu state; resending while a
	 * sync is merely slow could reverse a swap the server already applied. */
	private int swapStateId = -1;
	private int swapQuietTicks = 0;
	/** Hotbar slot selected before this depositor switched away; restored when the
	 * deposit ends so the player keeps their original hand. */
	private int originalSlot = -1;
	/** Drop flow (used when every chest refuses a good and the disposal option is
	 * "drop"): the selected slot holding the item to drop, plus its send/wait state. */
	private int dropSlot = -1;
	/** Menu state id before the goods-drop staging SWAP click. An inventory SWAP click
	 * is applied locally, so the contents alone can show a prediction; a changed state
	 * id (1.21+ echoes it on slot packets) or a long quiet wait proves the server saw it. */
	private int dropStageStateId = -1;
	private int dropWaitTicks = 0;
	private int dropAimTicks = 0;
	private boolean dropSent = false;
	private boolean dropResent = false;
	/** Menu slot the book was SWAPped out of when the drop was staged, or -1 when the
	 * droppable item already sat in the selected hotbar slot. */
	private int dropSourceSlot = -1;
	/** At staging time: the stack being staged (a copy) and the item the swap will push
	 * into the source slot (the destination's occupant, EMPTY when it was empty). A
	 * container SWAP click is applied LOCALLY first, so the drop may only proceed once
	 * the whole exchange is observed after a round trip — a rejected click restores both
	 * slots. "Source slot empty" alone is wrong: into an OCCUPIED destination the swap
	 * is a true exchange and the source ends up holding the occupant. */
	private ItemStack dropStagedBook = ItemStack.EMPTY;
	private ItemStack dropDisplaced = ItemStack.EMPTY;

	/** The start-of-run counts of every good's result item. The allowance is DERIVED
	 * from these every tick (count now minus baseline) and never booked per move: a
	 * click the server refuses is corrected back and must not spend the allowance. */
	private final Map<Item, Integer> goodsBaseline;
	/** Live: how many of each item this pass may still take because THIS RUN produced
	 * it. Recomputed from the inventory in {@link #refreshAllowance}. */
	private final Map<Item, Integer> allowance = new java.util.HashMap<>();
	/** Every distinct stack a locked trade has yielded, per result item: a good may only be
	 * taken when the stack IS one of that output (item AND components), never a
	 * component-distinct copy the player already carried (the allowance above is a
	 * per-item total). A list because the same good can be locked more than once. */
	private final Map<Item, List<ItemStack>> expectedResults;
	/** When true the pass takes only those produced goods: used in grindstone mode,
	 * where the books are still the recycler's business. */
	private final boolean goodsOnly;

	public ChestDepositor(List<BlockPos> chests) {
		this(chests, Map.of(), Map.of(), false);
	}

	public ChestDepositor(List<BlockPos> chests, Map<Item, Integer> goodsBaseline,
			Map<Item, List<ItemStack>> expectedResults, boolean goodsOnly) {
		this.chests = List.copyOf(chests);
		this.goodsBaseline = Map.copyOf(goodsBaseline);
		this.expectedResults = Map.copyOf(expectedResults);
		this.goodsOnly = goodsOnly;
		this.delay = TradeMasterConfig.INSTANCE.interactDelay;
	}

	public StepResult tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			// restore only needs player.connection: do it even when level/gameMode
			// are already gone (disconnect window), or the hand stays on the
			// depositor's temporary empty slot
			if (player != null) restoreHotbarSelection(player);
			return StepResult.failed(McText.literal("TradeMaster: player/level unavailable"));
		}
		this.ticks++;
		refreshAllowance(player);
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 20 * 120) {
			restoreHotbarSelection(player);
			return StepResult.failed(McText.translatable("trademaster.error.deposit-timeout"));
		}

		// Every chest has been tried (or there are none at all): either drop what is
		// left over — when the option says so — or report the chests as full. Once the
		// drop flow has started it must be allowed to run: re-deciding here every tick
		// would re-enter startDropOrFail forever and the DROP_* states would never run.
		if (this.index >= this.chests.size() && !inDropFlow()) {
			return startDropOrFail(player);
		}

		switch (this.state) {
			case SEEK -> {
				if (!(mc.level.getBlockState(this.chests.get(this.index)).getBlock() instanceof ChestBlock)) {
					// stale position: no chest there anymore, try the next one
					return advance(mc, player);
				}
				if (player.containerMenu != player.inventoryMenu) {
					// a leftover menu of unknown origin — even an open chest, since we
					// cannot know WHICH chest it is: close it and open the bound one
					// ourselves, or the QUICK_MOVE clicks could land in the wrong
					// container (a non-chest menu would swallow books into its own
					// slots, e.g. the merchant screen's payment slots)
					player.closeContainer();
					return StepResult.RUNNING;
				}
				if (!this.look.tick(player, Vec3.atCenterOf(this.chests.get(this.index)))) {
					return StepResult.RUNNING;
				}
				if (!this.switchedSlot) {
					this.switchedSlot = true;
					if (this.originalSlot < 0) {
						this.originalSlot = McPlayer.selectedSlot(player);
					}
					selectEmptyHotbarSlot(player);
					return StepResult.RUNNING;
				}
				TradeMasterConfig config = TradeMasterConfig.INSTANCE;
				if (config.slowMode && this.delay > 0) {
					this.delay--;
					return StepResult.RUNNING;
				}
				BlockPos chest = this.chests.get(this.index);
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
				McInteract.useItemOn(mc.gameMode, player, InteractionHand.MAIN_HAND, hit);
				McPackets.swing(player, InteractionHand.MAIN_HAND);
				this.openWait = 0;
				this.state = State.AWAIT_OPEN;
			}
			case AWAIT_OPEN -> {
				if (player.containerMenu instanceof ChestMenu) {
					this.state = State.DEPOSIT;
				} else if (player.containerMenu != player.inventoryMenu) {
					// something else opened instead of the chest: close it and retry
					player.closeContainer();
					this.state = State.SEEK;
				} else if (++this.openWait > 60) {
					if (this.openRetries < 1) {
						// one retry on the same chest: a laggy server or a single
						// rejected click should not burn a chest that still has space
						this.openRetries++;
						this.state = State.SEEK;
					} else {
						return advance(mc, player);
					}
				}
			}
			case DEPOSIT -> {
				if (this.swapRetryPending) {
					// a rejected swap scheduled for resend: space the attempts so a
					// laggy server gets time to catch up before the next packet
					if (this.swapRetryDelay > 0) {
						this.swapRetryDelay--;
						return StepResult.RUNNING;
					}
					this.swapRetryPending = false;
					// a late sync may already have landed the first swap; resending then
					// would swap the book straight back out of the offhand
					if (!McItem.sameItemSameComponents(player.getOffhandItem(), this.stagedBook)) {
						this.swapWaitTicks = -1;
						return StepResult.RUNNING;
					}
					// the stage slot changed under us (player moved an item): the raw
					// swap would exchange the offhand with an arbitrary slot
					if (!McItem.sameItemSameComponents(
							player.getInventory().getItem(this.stagedHotbarSlot), this.stagedDisplaced)) {
						return StepResult.failed(McText.translatable("trademaster.message.swap_rejected"));
					}
					// the raw swap always uses the SELECTED slot: re-select the stage
					// slot (a 1-9/wheel change during the retry delay moves it)
					if (McPlayer.selectedSlot(player) != this.stagedHotbarSlot) {
						Interactions.selectHotbarSlot(player, this.stagedHotbarSlot);
					}
					player.connection.send(new ServerboundPlayerActionPacket(
							ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
							BlockPos.ZERO, Direction.DOWN));
					this.markSwapSent(player);
					return StepResult.RUNNING;
				}
				if (this.swapWaitTicks >= 0) {
					// track whether the server has touched the menu since the send: a
					// landed (or rejected) swap always does
					if (player.containerMenu.getStateId() != this.swapStateId) {
						this.swapStateId = player.containerMenu.getStateId();
						this.swapQuietTicks = 0;
					} else {
						this.swapQuietTicks++;
					}
					// the raw SWAP packet has no client-side prediction: wait for
					// the server's slot sync (offhand no longer holds the book)
					// before ANYTHING reads the inventory — including the
					// menu-closed bounce below, which would otherwise skip the
					// wait and let EVALUATE see the stale offhand book on a laggy
					// dedicated server. A rejected swap times out into the retry
					// decision below
					if (!disposable(player.getOffhandItem())) {
						this.swapWaitTicks = -1;
					} else if (++this.swapWaitTicks > 20) {
						// no state change since the send: the packet may have been lost
						// OR its sync may still be in flight. Give the server a longer
						// quiet grace before resending — an early resend can reverse a
						// swap the server already applied.
						if (this.swapQuietTicks < 40) return StepResult.RUNNING;
						this.swapWaitTicks = -1;
						if (chestHasSpace(player)) {
							// the packet was rejected/dropped but this chest can still
							// take the book: retry a configurable number of times
							// (-1 = forever) instead of marching on and mis-reporting
							// "chests full" at the last one
							int max = TradeMasterConfig.INSTANCE.offhandSwapRetries;
							if (max < 0 || this.swapAttempts < max) {
								this.swapAttempts++;
								this.swapRetryPending = true;
								this.swapRetryDelay = Math.max(5, TradeMasterConfig.INSTANCE.interactDelay);
								return StepResult.RUNNING;
							}
							// attempts exhausted: stop through the normal failure flow
							// (stop() tears the depositor down via abortCleanly)
							return StepResult.failed(McText.translatable("trademaster.message.swap_rejected"));
						}
						// chest has no space: fall through to the old path
						// (close + EVALUATE + advance to the next chest)
					} else {
						return StepResult.RUNNING;
					}
				}
				if (!(player.containerMenu instanceof ChestMenu)) {
					// the menu opened in AWAIT_OPEN is gone or was replaced: a click
					// now would feed the books to whatever container is open instead.
					// Close it and let EVALUATE/advance re-decide
					if (player.containerMenu != player.inventoryMenu) player.closeContainer();
					this.state = State.EVALUATE;
					return StepResult.RUNNING;
				}
				// deposit every visible book first; the offhand is not part of the
				// chest menu and can only be staged via a hotbar slot — doing that
				// before the hotbar books are deposited fails when all nine hotbar
				// slots hold books, so it happens here, once slots have freed up
				BookScan scan = scanBooks(player);
				if (scan.firstSlot() == null) {
					this.lastBookCount = 0;
					if (disposable(player.getOffhandItem()) && !this.offhandSwapped) {
						int swapSlot = findNonBookHotbarSlot(player);
						// Defensive, unreachable today (audited 2026-09-19): reaching here
					// means no book sits in any of the 36 inventory slots, so the nine
					// hotbar slots cannot all be books and the search always finds one.
					if (swapSlot >= 0) {
							this.offhandSwapped = true;
							this.stagedHotbarSlot = swapSlot;
							this.stagedBook = player.getOffhandItem().copy();
							this.stagedDisplaced = player.getInventory().getItem(swapSlot).copy();
							if (McPlayer.selectedSlot(player) != swapSlot) {
								Interactions.selectHotbarSlot(player, swapSlot);
							}
							player.connection.send(new ServerboundPlayerActionPacket(
									ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
									BlockPos.ZERO, Direction.DOWN));
							this.markSwapSent(player);
							return StepResult.RUNNING;
						}
						// unreachable in practice (an all-books hotbar would have
						// shown up in the scan); fall through and let EVALUATE decide
						this.offhandSwapped = true;
					}
					player.closeContainer();
					this.state = State.EVALUATE;
					return StepResult.RUNNING;
				}
				if (scan.count() == this.lastBookCount) {
					// Nothing moved since the last click. That can mean the chest
					// accepts no more books, but on a laggy server the move can also
					// take several ticks to show up: re-click and wait a bit before
					// declaring this chest full.
					if (++this.noProgressTicks > 10) {
						return advance(mc, player);
					}
				} else {
					this.noProgressTicks = 0;
					this.lastBookCount = scan.count();
				}
				// the allowance is NOT booked from this click: handleContainerInput applies
				// the move locally first, so a booking would count a click the server may
				// refuse. It is re-derived from the inventory every tick instead.
				McClicks.click(mc.gameMode, player.containerMenu.containerId,
						scan.firstSlot().index, 0, McClicks.Click.QUICK_MOVE, player);
			}
			case EVALUATE -> {
				if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					return StepResult.RUNNING;
				}
				if (countDisposables(player) == 0) {
					undoOffhandStage(player);
					restoreHotbarSelection(player);
					return StepResult.DONE;
				}
				return advance(mc, player);
			}
			case DROP_STAGE -> {
				if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					return StepResult.RUNNING;
				}
				Slot slot = findDroppableSlot(player);
				if (slot == null) {
					// nothing left to drop: the offhand is deliberately not a drop
					// target (it has no selected slot to drop from), so finishing here
					// is correct — aiming at a stale slot used to loop until the timeout
					undoOffhandStage(player);
					restoreHotbarSelection(player);
					return StepResult.DONE;
				}
				int selected = McPlayer.selectedSlot(player);
				// the drop packet drops the SELECTED slot. If the droppable already sits in
				// a hotbar slot, select THAT one: no swap is needed, so there is no staging
				// prediction to get wrong (this is also the full-inventory case, where no
				// hotbar slot is free)
				if (slot.index >= 36 && slot.index <= 44 && slot.index - 36 != selected) {
					if (this.originalSlot < 0) this.originalSlot = selected;
					Interactions.selectHotbarSlot(player, slot.index - 36);
					selected = slot.index - 36;
				}
				boolean alreadyHeld = slot.index == 36 + selected;
				if (!alreadyHeld && !player.getInventory().getItem(selected).isEmpty()) {
					// the staging SWAP is applied LOCALLY before the server can reject it:
					// into an OCCUPIED slot the swap is a TRUE EXCHANGE, so a stale
					// prediction would DROP the player's own item. Stage only into an EMPTY
					// slot — then a stale prediction can at worst drop nothing.
					int empty = findEmptyHotbarSlot(player);
					if (empty < 0) {
						// nothing safe to stage into. FAILED_FULL, not FAILED: a full HOTBAR
						// says nothing about the main inventory, so let the caller decide
						// (it re-checks a free slot) instead of hard-stopping the run
						return StepResult.failedFull(McText.translatable("trademaster.message.drop_no_slot"));
					}
					if (this.originalSlot < 0) this.originalSlot = selected;
					Interactions.selectHotbarSlot(player, empty);
					selected = empty;
				}
				this.dropSlot = selected;
				this.dropWaitTicks = 0;
				if (alreadyHeld) {
					// no swap, so nothing to confirm — but record the stack, since the drop
					// packet only names the SELECTED slot and DROP_WAIT re-verifies it
					this.dropSourceSlot = -1;
					this.dropStagedBook = slot.getItem().copy();
					this.state = State.DROP_AIM;
					this.dropAimTicks = 0;
					return StepResult.RUNNING;
				}
				// SWAP it into the (empty) selected slot, then wait for the server sync
				this.dropSourceSlot = slot.index;
				this.dropStagedBook = slot.getItem().copy();
				this.dropDisplaced = player.getInventory().getItem(selected).copy();
				this.dropStageStateId = player.containerMenu.getStateId();
				McClicks.click(mc.gameMode, player.containerMenu.containerId, slot.index,
						selected, McClicks.Click.SWAP, player);
				this.state = State.DROP_STAGE_WAIT;
			}
			case DROP_STAGE_WAIT -> {
				// a container SWAP click IS applied locally first, so both slots already
				// show the result even when the server REJECTS the click: wait a round
				// trip and confirm the whole exchange — the target holds the staged book
				// AND the source holds exactly the displaced stack (EMPTY when the target
				// was empty). A rejection restores both, so the prediction cannot pass.
				if (++this.dropWaitTicks >= 10) {
					// a locally applied SWAP click can show the result before the server
					// accepts it: require a server echo (changed state id) or a long quiet
					// wait for versions whose slot packets carry no state id
					boolean serverProof = this.dropSourceSlot < 0
							|| player.containerMenu.getStateId() != this.dropStageStateId
							|| this.dropWaitTicks >= 40;
					boolean landed = serverProof && (this.dropSourceSlot < 0
							|| (player.containerMenu == player.inventoryMenu
									&& McItem.sameItemSameComponents(
											player.getInventory().getItem(this.dropSlot), this.dropStagedBook)
									&& McItem.sameItemSameComponents(
											player.containerMenu.slots.get(this.dropSourceSlot).getItem(),
											this.dropDisplaced)));
					if (landed) {
						this.state = State.DROP_AIM;
						this.dropAimTicks = 0;
					} else if (this.dropWaitTicks > 60) {
						return StepResult.failed(McText.translatable("trademaster.message.curse_drop_failed"));
					}
				}
			}
			case DROP_AIM -> {
				TradeMasterConfig config = TradeMasterConfig.INSTANCE;
				if (config.curseDropYaw == null || config.curseDropPitch == null) {
					// dropping blind is never allowed
					return StepResult.failed(McText.translatable("trademaster.message.curse_drop_failed"));
				}
				Vec3 target = player.getEyePosition().add(
						Vec3.directionFromRotation(config.curseDropPitch, config.curseDropYaw).scale(100.0));
				if (!this.look.tick(player, target)) return StepResult.RUNNING;
				// LookStep does nothing outside legit mode: without this the item is
				// dropped facing wherever the player happened to look, not the configured
				// direction (the recycler sets it explicitly for the same reason)
				if (!config.legitMode) {
					player.setYRot(config.curseDropYaw);
					player.setXRot(config.curseDropPitch);
				}
				// give the rotation a few ticks to reach the server before the drop
				if (++this.dropAimTicks >= 3) {
					this.dropSent = false;
					this.dropResent = false;
					this.dropWaitTicks = 0;
					this.state = State.DROP_WAIT;
				}
			}
			case DROP_WAIT -> {
				if (!droppable(player.getInventory().getItem(this.dropSlot))) {
					// the drop landed: the allowance shrinks on its own next tick
					this.state = State.DROP_STAGE;
					return StepResult.RUNNING;
				}
				if (!this.dropSent) {
					// the packet names no slot: the server drops the SELECTED one. Re-verify
					// it right before sending — a 1-9/wheel hotbar change since the staging
					// would otherwise drop whatever else is selected (the player's item)
					if (McPlayer.selectedSlot(player) != this.dropSlot
							|| !McItem.sameItemSameComponents(
									player.getInventory().getItem(this.dropSlot), this.dropStagedBook)) {
						this.state = State.DROP_STAGE;
						return StepResult.RUNNING;
					}
					player.connection.send(new ServerboundPlayerActionPacket(
							ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
							BlockPos.ZERO, Direction.DOWN));
					this.dropSent = true;
					this.dropWaitTicks = 0;
				} else if (++this.dropWaitTicks > 60) {
					if (!this.dropResent) {
						// one resend: idempotent — if the first packet landed, the selected
						// slot is empty server-side and this does nothing
						this.dropResent = true;
						this.dropSent = false;
						this.dropWaitTicks = 0;
					} else {
						return StepResult.failed(McText.translatable("trademaster.message.curse_drop_failed"));
					}
				}
			}
			default -> {
				// a state that is wired up but has no case would otherwise spin here
				return StepResult.failed(McText.translatable("trademaster.message.deposit_state_invalid"));
			}
		}
		return StepResult.RUNNING;
	}

	/** {@code mc} is unused (kept for call-site parity) — audited 2026-09-19. */
	private StepResult advance(Minecraft mc, LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
		this.index++;
		if (this.index >= this.chests.size()) {
			return startDropOrFail(player);
		}
		this.state = State.SEEK;
		this.look.reset();
		SmoothRotation.reset();
		this.openRetries = 0;
		this.switchedSlot = false;
		this.offhandSwapped = false;
		this.swapWaitTicks = -1;
		this.swapAttempts = 0;
		this.swapRetryPending = false;
		this.swapRetryDelay = 0;
		this.swapStateId = -1;
		this.swapQuietTicks = 0;
		this.lastBookCount = -1;
		this.noProgressTicks = 0;
		this.delay = TradeMasterConfig.INSTANCE.interactDelay;
		return StepResult.RUNNING;
	}

	/** Stop-time discard: close any chest still open and put the hand back where
	 * it was before the deposit started — a bare {@code depositor = null} would
	 * strand the selection on the temporary empty hotbar slot. */
	public void abortCleanly() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) return;
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		undoOffhandStage(player);
		restoreHotbarSelection(player);
	}

	/** Returns the item an offhand staging swap displaced into the offhand. Runs at the
	 * END of a deposit (or on abort) only when the exact post-swap state is still
	 * observed: the staged slot is empty (the book left) OR still holds exactly the book
	 * that was staged (all chests refused it — the swap-back restores the original
	 * state), AND the offhand still holds a non-book. Anything else means the world moved
	 * on — leave it alone. */
	private void undoOffhandStage(LocalPlayer player) {
		int slot = this.stagedHotbarSlot;
		ItemStack displaced = this.stagedDisplaced;
		ItemStack book = this.stagedBook;
		this.stagedHotbarSlot = -1;
		this.stagedDisplaced = ItemStack.EMPTY;
		this.stagedBook = ItemStack.EMPTY;
		// NOT gated on offhandSwapped: advance() resets that per chest, while the
		// displacement it describes outlives the chest it was made for
		if (slot < 0) return;
		ItemStack inSlot = player.getInventory().getItem(slot);
		// a book still sitting there means the deposit never took it (all chests refused):
		// swapping it back is exactly the pre-staging state, not theft
		if (!inSlot.isEmpty() && !McItem.sameItemSameComponents(inSlot, book)) return;
		// the offhand must still carry exactly what the swap displaced: anything else
		// means the player changed it, and moving it now would be theft
		if (!McItem.sameItemSameComponents(player.getOffhandItem(), displaced)) return;
		if (McPlayer.selectedSlot(player) != slot) {
			Interactions.selectHotbarSlot(player, slot);
		}
		player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
				BlockPos.ZERO, Direction.DOWN));
	}

	/** Puts the player's hand back where it was before the deposit started
	 * (hotbar selection and offhand swaps both go through hotbar slots). */
	private void restoreHotbarSelection(LocalPlayer player) {
		if (this.originalSlot >= 0 && McPlayer.selectedSlot(player) != this.originalSlot) {
			Interactions.selectHotbarSlot(player, this.originalSlot);
		}
		this.originalSlot = -1;
	}

	/** Whether the open container still has an empty slot of its own: a rejected
	 * offhand swap against a chest with space is worth retrying (R32-04); against
	 * a full one the old "advance to the next chest" path is correct. */
	private static boolean chestHasSpace(LocalPlayer player) {
		// only meaningful while a chest menu is actually open: with the inventory
		// menu open, its crafting result/grid slots belong to another container and
		// are empty, so the scan below would always answer "has space" and turn a
		// closed menu into pointless swap retries (and then a swap_rejected stop)
		if (!(player.containerMenu instanceof ChestMenu)) return false;
		for (Slot slot : player.containerMenu.slots) {
			if (slot.container != player.getInventory() && slot.getItem().isEmpty()) return true;
		}
		return false;
	}

	/** A menu slot holding a stack the player CARRIES (main inventory or hotbar). The menu
	 * SHAPE matters: InventoryMenu has crafting 0-4, WORN ARMOR 5-8, main 9-35, hotbar
	 * 36-44 and the offhand at 45, while any container menu lists only the 36 carried
	 * slots (no armor, no offhand). Filtering by a raw menu index would therefore either
	 * count worn armor (InventoryMenu) or drop every carried slot (a chest menu puts the
	 * player's rows at >= 27, past the offhand's index). */
	private static boolean isMainInventorySlot(LocalPlayer player, Slot slot) {
		if (slot.container != player.getInventory()) return false;
		if (player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu) {
			return slot.index >= 9 && slot.index <= 44;
		}
		return true;
	}

	/** One pass over the open container's player slots: first disposable slot + total. */
	private BookScan scanBooks(LocalPlayer player) {
		Slot first = null;
		int count = 0;
		for (Slot slot : player.containerMenu.slots) {
			if (isMainInventorySlot(player, slot) && disposable(slot.getItem())) {
				if (first == null) first = slot;
				count++;
			}
		}
		return new BookScan(first, count);
	}

	/** What this pass may take out of the inventory: enchanted books (unless this pass
	 * is goods-only) and the produced goods it still has an allowance for. */
	private boolean disposable(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (!this.goodsOnly && stack.is(Items.ENCHANTED_BOOK)) return true;
		return droppable(stack);
	}

	/** Records the menu state at the moment an offhand swap was sent, so the retry
	 * path can tell "the server answered (accept or reject)" from "no answer yet". */
	private void markSwapSent(LocalPlayer player) {
		this.swapStateId = player.containerMenu.getStateId();
		this.swapQuietTicks = 0;
		this.swapWaitTicks = 0;
	}

	/** Re-derives the produced-goods allowance from the live inventory: count now minus
	 * the start-of-run baseline. A click the server refuses never changes the count, so
	 * it can never spend the allowance — the whole reason this is derived, not booked. */
	private void refreshAllowance(LocalPlayer player) {
		this.allowance.clear();
		for (Map.Entry<Item, Integer> entry : this.goodsBaseline.entrySet()) {
			int excess = Preconditions.countItem(player, entry.getKey()) - entry.getValue();
			if (excess > 0) this.allowance.put(entry.getKey(), excess);
		}
	}

	private int countDisposables(LocalPlayer player) {
		int count = 0;
		for (Slot slot : player.containerMenu.slots) {
			if (isMainInventorySlot(player, slot) && disposable(slot.getItem())) count++;
		}
		return count;
	}

	/** True while the drop flow owns the tick: the top-of-tick "all chests tried"
	 * decision must not fire again, or startDropOrFail would loop instead of letting
	 * the DROP_* states actually drop anything. */
	private boolean inDropFlow() {
		return this.state == State.DROP_STAGE || this.state == State.DROP_STAGE_WAIT
				|| this.state == State.DROP_AIM || this.state == State.DROP_WAIT;
	}

	/** Every chest refused (or there are none): drop the leftover PRODUCED goods when
	 * the disposal option says so, otherwise report the chests as full — books are never
	 * dropped, only stored, so the option cannot throw a player's books away. */
	private StepResult startDropOrFail(LocalPlayer player) {
		if (TradeMasterConfig.INSTANCE.nonBookDisposal
						== dev.trademaster.config.NonBookDisposal.CHEST_THEN_DROP
				&& countDroppables(player) > 0) {
			this.state = State.DROP_STAGE;
			this.look.reset();
			this.dropSlot = -1;
			this.dropSourceSlot = -1;
			this.dropStagedBook = ItemStack.EMPTY;
			this.dropDisplaced = ItemStack.EMPTY;
			this.dropWaitTicks = 0;
			this.dropAimTicks = 0;
			this.dropSent = false;
			this.dropResent = false;
			return StepResult.RUNNING;
		}
		restoreHotbarSelection(player);
		return StepResult.failedFull(McText.translatable("trademaster.message.chests_full"));
	}

	/** Things this pass may DROP when no chest takes them: the goods this run produced,
	 * plus cursed books (which can never be ground — in grindstone mode the recycler
	 * owns those, so this pass leaves them alone). A normal book is only ever stored. */
	private boolean droppable(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (!stack.is(Items.ENCHANTED_BOOK)) {
			// only a stack that IS one of a locked trade's outputs may be taken: the
			// allowance is a per-item TOTAL, so without this component check a
			// component-distinct copy of the player's own item could satisfy it
			List<ItemStack> expected = this.expectedResults.get(stack.getItem());
			// no recorded output for this item means it is not one of this run's products,
			// whatever the per-item allowance says: never store or drop the player's own
			// items (the allowance is only derived from the goods baseline)
			if (expected == null) return false;
			boolean produced = false;
			for (ItemStack candidate : expected) {
				if (McItem.sameItemSameComponents(stack, candidate)) {
					produced = true;
					break;
				}
			}
			if (!produced) return false;
			// only a stack that is ENTIRELY produced may be taken: QUICK_MOVE and
			// DROP_ALL_ITEMS move the whole stack, so a stack merged with the player's
			// own items would take theirs too. A mixed stack is left alone — nothing of
			// the player's is ever stored or dropped.
			return this.allowance.getOrDefault(stack.getItem(), 0) >= stack.getCount();
		}
		return !this.goodsOnly && GrindstoneRecycler.isCurseBook(stack);
	}

	private int countDroppables(LocalPlayer player) {
		int count = 0;
		for (Slot slot : player.containerMenu.slots) {
			if (isMainInventorySlot(player, slot) && droppable(slot.getItem())) count++;
		}
		return count;
	}

	/** First inventory (not offhand) slot holding something this pass may drop, preferring
	 * a HOTBAR slot: the drop packet drops the SELECTED slot, and a droppable that already
	 * sits in the hotbar needs no staging swap at all (so there is no prediction to get
	 * wrong). The offhand (menu 45) is skipped: it has no selected slot to drop from. */
	private @Nullable Slot findDroppableSlot(LocalPlayer player) {
		// hotbar first (menu 36-44), then the main inventory (menu 9-35)
		for (Slot slot : player.containerMenu.slots) {
			if (slot.index >= 36 && slot.index <= 44
					&& isMainInventorySlot(player, slot) && droppable(slot.getItem())) return slot;
		}
		for (Slot slot : player.containerMenu.slots) {
			if (slot.index < 36 && isMainInventorySlot(player, slot) && droppable(slot.getItem())) return slot;
		}
		return null;
	}

	private static void selectEmptyHotbarSlot(LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).isEmpty() && i != McPlayer.selectedSlot(player)) {
				Interactions.selectHotbarSlot(player, i);
				return;
			}
		}
	}

	/** First empty hotbar slot, or -1 when all nine are occupied (the drop staging needs
	 * an empty destination so a stale prediction cannot drop the player's own item). */
	private static int findEmptyHotbarSlot(LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).isEmpty()) return i;
		}
		return -1;
	}

	/** First hotbar slot not holding a book (the selected one first: no extra
	 * switch needed), or -1 when every hotbar slot is a book. */
	private int findNonBookHotbarSlot(LocalPlayer player) {
		int selected = McPlayer.selectedSlot(player);
		if (!disposable(player.getInventory().getItem(selected))) return selected;
		for (int i = 0; i < 9; i++) {
			if (!disposable(player.getInventory().getItem(i))) return i;
		}
		return -1;
	}
}
