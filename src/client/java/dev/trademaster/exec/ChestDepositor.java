package dev.trademaster.exec;

import dev.trademaster.anticheat.LookStep;
import dev.trademaster.anticheat.SmoothRotation;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.Interactions;
import dev.trademaster.strategy.RerollStrategy.StepResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
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
	/** Ticks spent waiting for the server's offhand-swap slot sync to arrive;
	 * -1 while not waiting. */
	private int swapWaitTicks = -1;
	/** Offhand-swap retries spent on the current chest (R32-04). */
	private int swapAttempts = 0;
	/** A rejected swap scheduled for resend after a short delay. */
	private boolean swapRetryPending = false;
	private int swapRetryDelay = 0;
	/** Hotbar slot selected before this depositor switched away; restored when the
	 * deposit ends so the player keeps their original hand. */
	private int originalSlot = -1;
	/** Drop flow (used when every chest refuses a good and the disposal option is
	 * "drop"): the selected slot holding the item to drop, plus its send/wait state. */
	private int dropSlot = -1;
	private int dropWaitTicks = 0;
	private int dropAimTicks = 0;
	private boolean dropSent = false;
	private boolean dropResent = false;
	/** The stack the current drop is moving: captured when the drop is staged, because
	 * by the time the server confirms the drop the slot is already empty and the amount
	 * is gone. The allowance is booked only on that confirmation (what actually left). */
	private Item dropItem = null;
	private int dropCount = 0;

	/** How many of each item this pass may take out of the inventory because THIS RUN
	 * produced it (bought goods). Empty = the usual behaviour: enchanted books only. */
	private final Map<Item, Integer> goodsAllowance;
	/** When true the pass takes only those produced goods: used in grindstone mode,
	 * where the books are still the recycler's business. */
	private final boolean goodsOnly;

	public ChestDepositor(List<BlockPos> chests) {
		this(chests, Map.of(), false);
	}

	public ChestDepositor(List<BlockPos> chests, Map<Item, Integer> goodsAllowance, boolean goodsOnly) {
		this.chests = List.copyOf(chests);
		this.goodsAllowance = new java.util.HashMap<>(goodsAllowance);
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
			return StepResult.failed(Component.literal("TradeMaster: player/level unavailable"));
		}
		this.ticks++;
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 20 * 120) {
			restoreHotbarSelection(player);
			return StepResult.failed(Component.translatable("trademaster.error.deposit-timeout"));
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
						this.originalSlot = player.getInventory().getSelectedSlot();
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
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
				player.swing(InteractionHand.MAIN_HAND, true);
				player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
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
					player.connection.send(new ServerboundPlayerActionPacket(
							ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
							BlockPos.ZERO, Direction.DOWN));
					this.swapWaitTicks = 0;
					return StepResult.RUNNING;
				}
				if (this.swapWaitTicks >= 0) {
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
							return StepResult.failed(Component.translatable("trademaster.message.swap_rejected"));
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
							this.stagedDisplaced = player.getInventory().getItem(swapSlot).copy();
							if (player.getInventory().getSelectedSlot() != swapSlot) {
								Interactions.selectHotbarSlot(player, swapSlot);
							}
							player.connection.send(new ServerboundPlayerActionPacket(
									ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
									BlockPos.ZERO, Direction.DOWN));
							this.swapWaitTicks = 0;
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
				// capture the stack BEFORE the click: handleContainerInput applies the
				// move locally first, so reading the slot afterwards would see it empty
				// and the allowance would never be spent
				ItemStack before = scan.firstSlot().getItem().copy();
				mc.gameMode.handleContainerInput(player.containerMenu.containerId,
						scan.firstSlot().index, 0, ContainerInput.QUICK_MOVE, player);
				// the pass may only take what this run produced: book what actually left
				ItemStack after = scan.firstSlot().getItem();
				consumeAllowance(before.getItem(), before.getCount() - after.getCount());
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
				int selected = player.getInventory().getSelectedSlot();
				this.dropSlot = selected;
				this.dropWaitTicks = 0;
				// capture what this drop will move: by the time the server confirms it,
				// the slot is empty and the amount can no longer be read
				this.dropItem = slot.getItem().getItem();
				this.dropCount = slot.getItem().getCount();
				if (slot.index == 36 + selected) {
					// already the selected hotbar slot: straight to aiming
					this.state = State.DROP_AIM;
					this.dropAimTicks = 0;
					return StepResult.RUNNING;
				}
				// SWAP it into the selected slot (the raw drop packet drops whatever is
				// selected), then wait for the server sync before aiming
				mc.gameMode.handleContainerInput(player.containerMenu.containerId, slot.index,
						selected, ContainerInput.SWAP, player);
				this.state = State.DROP_STAGE_WAIT;
			}
			case DROP_STAGE_WAIT -> {
				// the staging SWAP has no client-side prediction of its own: give the
				// server a round trip to correct a REJECTED click before aiming, or a
				// refusal would still be dropped against the local prediction
				if (++this.dropWaitTicks >= 10) {
					if (droppable(player.getInventory().getItem(this.dropSlot))) {
						this.state = State.DROP_AIM;
						this.dropAimTicks = 0;
					} else if (this.dropWaitTicks > 60) {
						return StepResult.failed(Component.translatable("trademaster.message.curse_drop_failed"));
					}
				}
			}
			case DROP_AIM -> {
				TradeMasterConfig config = TradeMasterConfig.INSTANCE;
				if (config.curseDropYaw == null || config.curseDropPitch == null) {
					// dropping blind is never allowed
					return StepResult.failed(Component.translatable("trademaster.message.curse_drop_failed"));
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
					// the drop landed: book what actually left, then take the next item
					consumeAllowance(this.dropItem, this.dropCount);
					this.state = State.DROP_STAGE;
					return StepResult.RUNNING;
				}
				if (!this.dropSent) {
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
						return StepResult.failed(Component.translatable("trademaster.message.curse_drop_failed"));
					}
				}
			}
			default -> {
				// a state that is wired up but has no case would otherwise spin here
				return StepResult.failed(Component.translatable("trademaster.message.deposit_state_invalid"));
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

	/** Returns the item an offhand staging swap displaced into the offhand. Runs at
	 * the END of a deposit (or on abort) only when the exact post-swap state is still
	 * observed: the book is gone from the staged slot AND the offhand holds a
	 * non-book. Any other state means the world moved on — leave it alone. */
	private void undoOffhandStage(LocalPlayer player) {
		int slot = this.stagedHotbarSlot;
		ItemStack displaced = this.stagedDisplaced;
		this.stagedHotbarSlot = -1;
		this.stagedDisplaced = ItemStack.EMPTY;
		// NOT gated on offhandSwapped: advance() resets that per chest, while the
		// displacement it describes outlives the chest it was made for
		if (slot < 0) return;
		if (!player.getInventory().getItem(slot).isEmpty()) return;
		// the offhand must still carry exactly what the swap displaced: anything else
		// means the player changed it, and moving it now would be theft
		if (!ItemStack.isSameItemSameComponents(player.getOffhandItem(), displaced)) return;
		if (player.getInventory().getSelectedSlot() != slot) {
			Interactions.selectHotbarSlot(player, slot);
		}
		player.connection.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
				BlockPos.ZERO, Direction.DOWN));
	}

	/** Puts the player's hand back where it was before the deposit started
	 * (hotbar selection and offhand swaps both go through hotbar slots). */
	private void restoreHotbarSelection(LocalPlayer player) {
		if (this.originalSlot >= 0 && player.getInventory().getSelectedSlot() != this.originalSlot) {
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

	/** A menu slot that holds a stack the player carries in the main inventory or the
	 * hotbar. The menu index must be >= 9: in InventoryMenu 0-4 are the crafting grid
	 * and 5-8 the WORN ARMOR, whose container is also the player inventory — without
	 * this bound a worn armor piece would be counted (and, on the drop path, taken off
	 * the player) as if it were carried. The offhand (45) passes here and is skipped
	 * separately, only where the drop flow cannot use it. */
	private static boolean isMainInventorySlot(LocalPlayer player, Slot slot) {
		return slot.container == player.getInventory() && slot.index >= 9;
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

	/** Consumes {@code moved} of {@code item} from the produced-goods allowance. Called
	 * only for what the server actually moved (never for a refused click), so the
	 * allowance stays an upper bound on THIS run's output rather than on the player's
	 * whole stock. */
	private void consumeAllowance(Item item, int moved) {
		if (item == null || moved <= 0 || item == Items.ENCHANTED_BOOK) return;
		Integer left = this.goodsAllowance.get(item);
		if (left == null) return;
		if (left <= moved) this.goodsAllowance.remove(item);
		else this.goodsAllowance.put(item, left - moved);
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
			this.dropWaitTicks = 0;
			this.dropAimTicks = 0;
			this.dropSent = false;
			this.dropResent = false;
			this.dropItem = null;
			this.dropCount = 0;
			return StepResult.RUNNING;
		}
		restoreHotbarSelection(player);
		return StepResult.failedFull(Component.translatable("trademaster.message.chests_full"));
	}

	/** Things this pass may DROP when no chest takes them: the goods this run produced,
	 * plus cursed books (which can never be ground — in grindstone mode the recycler
	 * owns those, so this pass leaves them alone). A normal book is only ever stored. */
	private boolean droppable(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (!stack.is(Items.ENCHANTED_BOOK)) {
			// only a stack that is ENTIRELY produced may be taken: QUICK_MOVE and
			// DROP_ALL_ITEMS move the whole stack, so a stack merged with the player's
			// own items would take theirs too. A mixed stack is left alone — nothing of
			// the player's is ever stored or dropped.
			return this.goodsAllowance.getOrDefault(stack.getItem(), 0) >= stack.getCount();
		}
		return !this.goodsOnly && GrindstoneRecycler.isCurseBook(stack);
	}

	private int countDroppables(LocalPlayer player) {
		int count = 0;
		for (Slot slot : player.containerMenu.slots) {
			if (slot.index >= 45) continue;
			if (isMainInventorySlot(player, slot) && droppable(slot.getItem())) count++;
		}
		return count;
	}

	/** First inventory (not offhand) slot holding something this pass may drop. The
	 * offhand has no selected slot to drop from, and the inventory menu's offhand slot
	 * is the one past the hotbar, so it is skipped here and simply left alone. */
	private @Nullable Slot findDroppableSlot(LocalPlayer player) {
		for (Slot slot : player.containerMenu.slots) {
			if (slot.index >= 45) continue;
			if (isMainInventorySlot(player, slot) && droppable(slot.getItem())) return slot;
		}
		return null;
	}

	private static void selectEmptyHotbarSlot(LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).isEmpty() && i != player.getInventory().getSelectedSlot()) {
				Interactions.selectHotbarSlot(player, i);
				return;
			}
		}
	}

	/** First hotbar slot not holding a book (the selected one first: no extra
	 * switch needed), or -1 when every hotbar slot is a book. */
	private int findNonBookHotbarSlot(LocalPlayer player) {
		int selected = player.getInventory().getSelectedSlot();
		if (!disposable(player.getInventory().getItem(selected))) return selected;
		for (int i = 0; i < 9; i++) {
			if (!disposable(player.getInventory().getItem(i))) return i;
		}
		return -1;
	}
}
