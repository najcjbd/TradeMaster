package dev.trademaster.exec;

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
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Deposits enchanted books into the bound chests, in order, when the
 * inventory is full. One book moved per tick (anti-cheat). A chest that
 * accepts nothing is treated as full and skipped; when every chest is
 * full the run stops.
 */
public class ChestDepositor implements BookDisposal {

	private enum State { SEEK, AWAIT_OPEN, DEPOSIT, EVALUATE }

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

	public ChestDepositor(List<BlockPos> chests) {
		this.chests = List.copyOf(chests);
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
			return StepResult.failed(Component.translatable("trademaster.error.reroll-timeout"));
		}

		// Defensive, unreachable today (audited 2026-09-19): the constructor requires
		// a non-empty chest list and the index only grows in advance(), which reports
		// the same failure once it runs past the end.
		if (this.index >= this.chests.size()) {
			restoreHotbarSelection(player);
			return StepResult.failedFull(Component.translatable("trademaster.message.chests_full"));
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
					if (!player.getOffhandItem().is(Items.ENCHANTED_BOOK)) {
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
					if (player.getOffhandItem().is(Items.ENCHANTED_BOOK) && !this.offhandSwapped) {
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
				mc.gameMode.handleContainerInput(player.containerMenu.containerId,
						scan.firstSlot().index, 0, ContainerInput.QUICK_MOVE, player);
			}
			case EVALUATE -> {
				if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					return StepResult.RUNNING;
				}
				if (Preconditions.countItem(player, Items.ENCHANTED_BOOK) == 0) {
					undoOffhandStage(player);
					restoreHotbarSelection(player);
					return StepResult.DONE;
				}
				return advance(mc, player);
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
			restoreHotbarSelection(player);
			return StepResult.failedFull(Component.translatable("trademaster.message.chests_full"));
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
		if (!this.offhandSwapped || slot < 0) return;
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

	/** One pass over the open container's player slots: first book slot + total. */
	private static BookScan scanBooks(LocalPlayer player) {
		Slot first = null;
		int count = 0;
		for (Slot slot : player.containerMenu.slots) {
			if (slot.container == player.getInventory() && slot.getItem().is(Items.ENCHANTED_BOOK)) {
				if (first == null) first = slot;
				count++;
			}
		}
		return new BookScan(first, count);
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
	private static int findNonBookHotbarSlot(LocalPlayer player) {
		int selected = player.getInventory().getSelectedSlot();
		if (!player.getInventory().getItem(selected).is(Items.ENCHANTED_BOOK)) return selected;
		for (int i = 0; i < 9; i++) {
			if (!player.getInventory().getItem(i).is(Items.ENCHANTED_BOOK)) return i;
		}
		return -1;
	}
}
