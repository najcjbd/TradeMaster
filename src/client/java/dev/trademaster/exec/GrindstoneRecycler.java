package dev.trademaster.exec;

import dev.trademaster.anticheat.LookStep;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.Interactions;
import dev.trademaster.core.Preconditions;
import dev.trademaster.strategy.RerollStrategy.StepResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Grindstone recycling: disenchants enchanted books one at a time on the
 * configured grindstone instead of storing them in chests. Every step is
 * verified against the server's slot sync before the next one starts — a
 * laggy connection must never grind, move or drop the wrong item:
 *
 * - one book per cycle: QUICK_MOVE into the input, wait for the input sync,
 *   wait for the result (a plain book), QUICK_MOVE it out, then verify the
 *   totals shifted by exactly enchanted-1 / plain+1;
 * - books carrying a curse (vanilla curse tag or a configured mod id) can
 *   never enter the grindstone (it keeps curses): they go to the bound
 *   chests first, and when no chest can take them they are dropped while
 *   facing the configured yaw/pitch — each drop is confirmed by the slot
 *   actually emptying;
 * - leftovers inside the grindstone are returned to the player first, so an
 *   interrupted run cannot double-grind or strand items.
 */
public class GrindstoneRecycler implements BookDisposal {

	/** Every value must have a matching {@code case}: the tick() switch ends in a
	 * guarded default, so a state that is wired up but never handled fails loudly
	 * instead of spinning forever on the trailing RUNNING return. */
	private enum State {
		SEEK, AWAIT_OPEN, RECOVER, SCAN, PLACE, RESULT, TAKE,
		OFFHAND_WAIT,
		CHEST_SEEK, CHEST_AWAIT, CHEST_MOVE,
		DROP_STAGE, DROP_STAGE_WAIT, DROP_AIM, DROP_WAIT
	}

	private final BlockPos grindstone;
	private final List<BlockPos> chests;
	private final LookStep look = new LookStep();
	private State state = State.SEEK;
	private int ticks = 0;
	private int openWait = 0;
	private int openRetries = 0;
	/** Generic per-operation sync wait counter. */
	private int waitTicks = 0;
	private int delay;
	/** Menu slot of the book currently being ground. */
	private int placeSlot = -1;
	private int preEnchanted = 0;
	private int prePlain = 0;
	/** Menu slot of the book currently being pushed into a chest; -1 = not started. */
	private int stashSlot = -1;
	/** Chest list index currently tried for a stash. */
	private int chestIndex = 0;
	/** Hotbar slot the staged curse book sits in for the directional drop. */
	private int dropSlot = -1;
	private boolean dropSent = false;
	private boolean dropResent = false;
	/** One staging-swap resend for the cursed-book drop (same idempotency rule as
	 * OFFHAND_WAIT): reset when the offhand staging swap goes out. */
	private boolean dropStageResent = false;
	/** One safe resend spent on the offhand staging swap. */
	private boolean offhandResent = false;
	/** Hotbar slot selected before this recycler switched away; restored at the end. */
	private int originalSlot = -1;

	public GrindstoneRecycler() {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		this.grindstone = config.grindstonePos;
		this.chests = List.copyOf(config.chestPositions);
		this.delay = config.interactDelay;
	}

	@Override
	public StepResult tick() {
		Minecraft mc = Minecraft.getInstance();
		var player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			if (player != null) restoreHotbarSelection(player);
			return StepResult.failed(Component.literal("TradeMaster: player/level unavailable"));
		}
		this.ticks++;
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 20 * 180) {
			restoreHotbarSelection(player);
			return StepResult.failed(Component.translatable("trademaster.error.reroll-timeout"));
		}

		switch (this.state) {
			case SEEK -> {
				if (!mc.level.getBlockState(this.grindstone).is(Blocks.GRINDSTONE)) {
					return fail(player, "trademaster.message.grindstone_missing");
				}
				if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					return StepResult.RUNNING;
				}
				if (!this.look.tick(player, Vec3.atCenterOf(this.grindstone))) {
					return StepResult.RUNNING;
				}
				TradeMasterConfig config = TradeMasterConfig.INSTANCE;
				if (config.slowMode && this.delay > 0) {
					this.delay--;
					return StepResult.RUNNING;
				}
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(this.grindstone), Direction.UP, this.grindstone, false);
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
				player.swing(InteractionHand.MAIN_HAND, true);
				player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
				this.openWait = 0;
				this.state = State.AWAIT_OPEN;
			}
			case AWAIT_OPEN -> {
				if (player.containerMenu instanceof GrindstoneMenu) {
					this.openRetries = 0;
					if (grindstoneHasItems(player)) {
						// leftovers from an interrupted run (or the user): return
						// them to the player before any grinding starts
						this.waitTicks = 0;
						this.state = State.RECOVER;
					} else {
						this.state = State.SCAN;
					}
				} else if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					this.state = State.SEEK;
				} else if (++this.openWait > 60) {
					int maxRetries = TradeMasterConfig.INSTANCE.grindstoneOpenRetries;
					if (maxRetries < 0 || this.openRetries < maxRetries) {
						// opening moves no items, so retrying (even forever on a
						// laggy server) is always safe
						this.openRetries++;
						this.state = State.SEEK;
					} else {
						return fail(player, "trademaster.message.grindstone_open_failed");
					}
				}
			}
			case RECOVER -> {
				if (!(player.containerMenu instanceof GrindstoneMenu)) {
					this.state = State.SEEK;
					return StepResult.RUNNING;
				}
				if (!grindstoneHasItems(player)) {
					this.state = State.SCAN;
					return StepResult.RUNNING;
				}
				if (++this.waitTicks > 60) {
					return fail(player, "trademaster.message.grindstone_dirty");
				}
				if (this.waitTicks == 1 || this.waitTicks == 21 || this.waitTicks == 41) {
					for (int i = 0; i < 3; i++) {
						if (!player.containerMenu.slots.get(i).getItem().isEmpty()) {
							mc.gameMode.handleContainerInput(player.containerMenu.containerId,
									i, 0, ContainerInput.QUICK_MOVE, player);
							break;
						}
					}
				}
			}
			case SCAN -> {
				if (!(player.containerMenu instanceof GrindstoneMenu)) {
					this.state = State.SEEK;
					return StepResult.RUNNING;
				}
				if (Preconditions.countItem(player, Items.ENCHANTED_BOOK) == 0) {
					player.closeContainer();
					restoreHotbarSelection(player);
					return StepResult.DONE;
				}
				// cursed books first: they permanently leave the inventory (chest/drop),
				// freeing slots for the grind loop, and their riskier path fails fast
				// before a long grind cycle starts
				if (findBookSlot(player, true) != null) {
					// cursed books cannot be ground: chest first, drop as the fallback
					if (!this.chests.isEmpty()) {
						return beginChestStash(player);
					}
					return beginDrop(player);
				}
				Slot grindable = findBookSlot(player, false);
				if (grindable != null) {
					// no free-slot bootstrap needed: moving the book into the
					// grindstone input empties its inventory slot, which is where
					// the ground result lands
					this.placeSlot = grindable.index;
					this.preEnchanted = Preconditions.countItem(player, Items.ENCHANTED_BOOK);
					this.prePlain = Preconditions.countItem(player, Items.BOOK);
					mc.gameMode.handleContainerInput(player.containerMenu.containerId,
							grindable.index, 0, ContainerInput.QUICK_MOVE, player);
					this.waitTicks = 0;
					this.state = State.PLACE;
					return StepResult.RUNNING;
				}
				if (player.getOffhandItem().is(Items.ENCHANTED_BOOK)) {
					// the offhand is not part of the grindstone menu: stage it into
					// a non-book hotbar slot via the raw swap packet (no client-side
					// prediction — the sync decides whether it landed)
					int swapSlot = findNonBookHotbarSlot(player);
					// Defensive, unreachable today (audited 2026-09-19): the book was
					// found in the menu's player slots, so the hotbar cannot be all books.
					if (swapSlot < 0) {
						return fail(player, "trademaster.message.curse_stage_failed");
					}
					if (player.getInventory().getSelectedSlot() != swapSlot) {
						if (this.originalSlot < 0) this.originalSlot = player.getInventory().getSelectedSlot();
						Interactions.selectHotbarSlot(player, swapSlot);
					}
					player.connection.send(new ServerboundPlayerActionPacket(
							ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
							BlockPos.ZERO, Direction.DOWN));
					this.waitTicks = 0;
					this.offhandResent = false;
					this.state = State.OFFHAND_WAIT;
					return StepResult.RUNNING;
				}
				// counted books but found nothing to work on: counts and menu
				// disagree — stop instead of guessing
				return fail(player, "trademaster.message.grind_verify_failed");
			}
			case OFFHAND_WAIT -> {
				if (!(player.containerMenu instanceof GrindstoneMenu)) {
					this.state = State.SEEK;
					return StepResult.RUNNING;
				}
				if (!player.getOffhandItem().is(Items.ENCHANTED_BOOK)) {
					this.state = State.SCAN;
				} else if (++this.waitTicks > 40) {
					if (!this.offhandResent) {
						// safe single resend: the server sync still shows the book
						// in the offhand, so the first packet was lost — it cannot
						// double-swap. (A blind resend while the sync is merely late
						// would swap the book straight back.)
						this.offhandResent = true;
						this.waitTicks = 0;
						player.connection.send(new ServerboundPlayerActionPacket(
								ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
								BlockPos.ZERO, Direction.DOWN));
					} else {
						return fail(player, "trademaster.message.curse_stage_failed");
					}
				}
			}
			case PLACE -> {
				if (!(player.containerMenu instanceof GrindstoneMenu)) {
					return toGrindstone(player);
				}
				boolean placed = false;
				for (int i = 0; i < 2; i++) {
					if (player.containerMenu.slots.get(i).getItem().is(Items.ENCHANTED_BOOK)) {
						placed = true;
						break;
					}
				}
				if (placed && player.containerMenu.slots.get(this.placeSlot).getItem().isEmpty()) {
					this.waitTicks = 0;
					this.state = State.RESULT;
				} else if (++this.waitTicks > 60) {
					return fail(player, "trademaster.message.grind_place_failed");
				} else if (this.waitTicks == 20 || this.waitTicks == 40) {
					// lag resend — idempotent: once the first click landed the source
					// slot is empty server-side and a second click moves nothing
					if (!player.containerMenu.slots.get(this.placeSlot).getItem().isEmpty()) {
						mc.gameMode.handleContainerInput(player.containerMenu.containerId,
								this.placeSlot, 0, ContainerInput.QUICK_MOVE, player);
					}
				}
			}
			case RESULT -> {
				if (!(player.containerMenu instanceof GrindstoneMenu)) {
					return toGrindstone(player);
				}
				ItemStack result = player.containerMenu.slots.get(2).getItem();
				if (result.is(Items.BOOK)) {
					mc.gameMode.handleContainerInput(player.containerMenu.containerId,
							2, 0, ContainerInput.QUICK_MOVE, player);
					this.waitTicks = 0;
					this.state = State.TAKE;
				} else if (result.is(Items.ENCHANTED_BOOK)) {
					// the grindstone keeps curses: an enchanted result means a curse
					// slipped past detection — never take it back as "ground"
					return fail(player, "trademaster.message.grind_result_cursed");
				} else if (++this.waitTicks > 60) {
					return fail(player, "trademaster.message.grind_result_failed");
				}
			}
			case TAKE -> {
				if (!(player.containerMenu instanceof GrindstoneMenu)) {
					return toGrindstone(player);
				}
				if (player.containerMenu.slots.get(2).getItem().isEmpty()) {
					int enchanted = Preconditions.countItem(player, Items.ENCHANTED_BOOK);
					int plain = Preconditions.countItem(player, Items.BOOK);
					if (enchanted == this.preEnchanted - 1 && plain == this.prePlain + 1) {
						this.state = State.SCAN;
					} else {
						// the item counts disagree with the expected one-book swap:
						// something moved outside this pipeline — stop before the
						// next cycle can build on a corrupted state
						return fail(player, "trademaster.message.grind_verify_failed");
					}
				} else if (++this.waitTicks > 60) {
					return fail(player, "trademaster.message.grind_take_failed");
				} else if (this.waitTicks == 20 || this.waitTicks == 40) {
					// lag resend — idempotent: an already-emptied result slot ignores it
					mc.gameMode.handleContainerInput(player.containerMenu.containerId,
							2, 0, ContainerInput.QUICK_MOVE, player);
				}
			}
			case CHEST_SEEK -> {
				if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					return StepResult.RUNNING;
				}
				if (this.chestIndex >= this.chests.size()) {
					// every chest refused the curse book: directional drop
					return beginDrop(player);
				}
				BlockPos chest = this.chests.get(this.chestIndex);
				if (!(mc.level.getBlockState(chest).getBlock() instanceof ChestBlock)) {
					this.chestIndex++;
					return StepResult.RUNNING;
				}
				if (!this.look.tick(player, Vec3.atCenterOf(chest))) {
					return StepResult.RUNNING;
				}
				TradeMasterConfig config = TradeMasterConfig.INSTANCE;
				if (config.slowMode && this.delay > 0) {
					this.delay--;
					return StepResult.RUNNING;
				}
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(chest), Direction.UP, chest, false);
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
				player.swing(InteractionHand.MAIN_HAND, true);
				player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
				this.openWait = 0;
				this.state = State.CHEST_AWAIT;
			}
			case CHEST_AWAIT -> {
				if (player.containerMenu instanceof ChestMenu) {
					this.stashSlot = -1;
					this.waitTicks = 0;
					this.state = State.CHEST_MOVE;
				} else if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					this.state = State.CHEST_SEEK;
				} else if (++this.openWait > 60) {
					// never opened: treat the chest as unusable and try the next
					this.chestIndex++;
					this.look.reset();
					this.state = State.CHEST_SEEK;
				}
			}
			case CHEST_MOVE -> {
				if (!(player.containerMenu instanceof ChestMenu)) {
					this.state = State.CHEST_SEEK;
					return StepResult.RUNNING;
				}
				// the open menu can be swapped for a SMALLER one while we wait (the
				// player right-clicks another chest): the recorded slot index would
				// then be out of range — treat it as a menu change, don't crash on get()
				if (this.stashSlot >= player.containerMenu.slots.size()) {
					this.stashSlot = -1;
					player.closeContainer();
					this.look.reset();
					this.state = State.CHEST_SEEK;
					return StepResult.RUNNING;
				}
				if (this.stashSlot < 0) {
					Slot target = findBookSlot(player, true);
					if (target == null) {
						// nothing left to stash (the book moved between menus)
						return toGrindstone(player);
					}
					this.stashSlot = target.index;
					mc.gameMode.handleContainerInput(player.containerMenu.containerId,
							target.index, 0, ContainerInput.QUICK_MOVE, player);
					this.waitTicks = 0;
					return StepResult.RUNNING;
				}
				// one book per chest visit: wait for exactly the clicked slot to
				// empty (server sync), then head back to the grindstone
				if (player.containerMenu.slots.get(this.stashSlot).getItem().isEmpty()) {
					return toGrindstone(player);
				}
				if (++this.waitTicks > 60) {
					// the book never left the slot: chest full or rejecting
					player.closeContainer();
					this.chestIndex++;
					this.look.reset();
					this.state = State.CHEST_SEEK;
				} else if (this.waitTicks == 20 || this.waitTicks == 40) {
					// lag resend — idempotent, and only while the book is still there
					if (player.containerMenu.slots.get(this.stashSlot).getItem().is(Items.ENCHANTED_BOOK)) {
						mc.gameMode.handleContainerInput(player.containerMenu.containerId,
								this.stashSlot, 0, ContainerInput.QUICK_MOVE, player);
					}
				}
			}
			case DROP_STAGE -> {
				if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					return StepResult.RUNNING;
				}
				int invSlot = -1;
				for (int i = 0; i < 36; i++) {
					if (isCursed(player.getInventory().getItem(i))) {
						invSlot = i;
						break;
					}
				}
				if (invSlot < 0 && !isCursed(player.getOffhandItem())) {
					// no curse book left anywhere: resume grinding
					return toGrindstone(player);
				}
				if (invSlot < 0) {
					// offhand: the raw swap packet has no client-side prediction, so
					// the book only counts as staged once the server sync shows it
					this.dropSlot = player.getInventory().getSelectedSlot();
					this.dropStageResent = false;
					player.connection.send(new ServerboundPlayerActionPacket(
							ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
							BlockPos.ZERO, Direction.DOWN));
				} else if (invSlot < 9) {
					this.dropSlot = invSlot;
					if (player.getInventory().getSelectedSlot() != invSlot) {
						if (this.originalSlot < 0) this.originalSlot = player.getInventory().getSelectedSlot();
						Interactions.selectHotbarSlot(player, invSlot);
					}
					// already in the hotbar: straight to aiming (the SET_CARRIED_ITEM
					// packet precedes the drop packet on the wire — order guaranteed)
					this.waitTicks = 0;
					this.state = State.DROP_AIM;
					return StepResult.RUNNING;
				} else {
					// storage slot: SWAP-click it into a hotbar slot through the
					// inventory container, then wait for the sync
					int target = findEmptyHotbarSlot(player);
					if (target < 0) target = player.getInventory().getSelectedSlot();
					if (player.getInventory().getSelectedSlot() != target) {
						if (this.originalSlot < 0) this.originalSlot = player.getInventory().getSelectedSlot();
						Interactions.selectHotbarSlot(player, target);
					}
					this.dropSlot = target;
					mc.gameMode.handleContainerInput(player.containerMenu.containerId,
							inventoryMenuSlot(invSlot), target, ContainerInput.SWAP, player);
				}
				this.waitTicks = 0;
				this.state = State.DROP_STAGE_WAIT;
			}
			case DROP_STAGE_WAIT -> {
				if (isCursed(player.getInventory().getItem(this.dropSlot))) {
					this.waitTicks = 0;
					this.state = State.DROP_AIM;
				} else if (++this.waitTicks > 60) {
					if (!this.dropStageResent && this.dropSlot == player.getInventory().getSelectedSlot()
							&& isCursed(player.getOffhandItem())) {
						// the offhand swap packet was lost: one resend is idempotent — the
						// book is still in the offhand server-side, so this cannot swap it
						// back (same rule as OFFHAND_WAIT)
						this.dropStageResent = true;
						this.waitTicks = 0;
						player.connection.send(new ServerboundPlayerActionPacket(
								ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
								BlockPos.ZERO, Direction.DOWN));
						return StepResult.RUNNING;
					}
					return fail(player, "trademaster.message.curse_stage_failed");
				}
			}
			case DROP_AIM -> {
				TradeMasterConfig config = TradeMasterConfig.INSTANCE;
				if (config.curseDropYaw == null || config.curseDropPitch == null) {
					// the direction vanished mid-run: dropping blind is never allowed
					return fail(player, "trademaster.message.curse_drop_failed");
				}
				// aim by looking at a far point along the configured direction:
				// LookStep covers both legit modes, the explicit set covers the rest
				Vec3 target = player.getEyePosition().add(
						Vec3.directionFromRotation(config.curseDropPitch, config.curseDropYaw).scale(100.0));
				if (!this.look.tick(player, target)) {
					return StepResult.RUNNING;
				}
				if (!config.legitMode) {
					player.setYRot(config.curseDropYaw);
					player.setXRot(config.curseDropPitch);
				}
				// give the rotation a few ticks to reach the server before the drop
				// packet, or the book flies in the old direction
				if (++this.waitTicks >= 3) {
					this.waitTicks = 0;
					this.dropSent = false;
					this.state = State.DROP_WAIT;
				}
			}
			case DROP_WAIT -> {
				if (player.getInventory().getItem(this.dropSlot).isEmpty()) {
					// server sync confirms the drop — never move on before this
					this.dropSlot = -1;
					this.dropResent = false;
					return toGrindstone(player);
				}
				if (!this.dropSent) {
					player.connection.send(new ServerboundPlayerActionPacket(
							ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
							BlockPos.ZERO, Direction.DOWN));
					this.dropSent = true;
					this.waitTicks = 0;
				} else if (++this.waitTicks > 60) {
					if (!this.dropResent) {
						// one resend: idempotent — if the first packet already landed
						// server-side the selected slot is empty there and this does nothing
						this.dropResent = true;
						this.dropSent = false;
						this.waitTicks = 0;
					} else {
						return fail(player, "trademaster.message.curse_drop_failed");
					}
				}
			}
			default -> {
				// a state without a case would otherwise fall through to the trailing
				// RUNNING return and spin forever without doing anything
				return fail(player, "trademaster.message.grind_state_invalid");
			}
		}
		return StepResult.RUNNING;
	}

	@Override
	public void abortCleanly() {
		Minecraft mc = Minecraft.getInstance();
		var player = mc.player;
		if (player == null) return;
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		restoreHotbarSelection(player);
	}

	private StepResult beginChestStash(net.minecraft.client.player.LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		this.chestIndex = 0;
		this.stashSlot = -1;
		this.state = State.CHEST_SEEK;
		this.look.reset();
		this.delay = TradeMasterConfig.INSTANCE.interactDelay;
		return StepResult.RUNNING;
	}

	private StepResult beginDrop(net.minecraft.client.player.LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		this.dropSlot = -1;
		this.dropSent = false;
		this.dropResent = false;
		this.state = State.DROP_STAGE;
		this.look.reset();
		return StepResult.RUNNING;
	}

	/** Back to the grindstone after a chest/drop detour. */
	private StepResult toGrindstone(net.minecraft.client.player.LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		this.state = State.SEEK;
		this.look.reset();
		this.openRetries = 0;
		this.delay = TradeMasterConfig.INSTANCE.interactDelay;
		return StepResult.RUNNING;
	}

	private StepResult fail(net.minecraft.client.player.LocalPlayer player, String key) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		restoreHotbarSelection(player);
		return StepResult.failed(Component.translatable(key));
	}

	private void restoreHotbarSelection(net.minecraft.client.player.LocalPlayer player) {
		if (this.originalSlot >= 0 && player.getInventory().getSelectedSlot() != this.originalSlot) {
			Interactions.selectHotbarSlot(player, this.originalSlot);
		}
		this.originalSlot = -1;
	}

	private static boolean grindstoneHasItems(net.minecraft.client.player.LocalPlayer player) {
		for (int i = 0; i < 3; i++) {
			if (!player.containerMenu.slots.get(i).getItem().isEmpty()) return true;
		}
		return false;
	}

	/** First enchanted book in the open menu's player slots; cursed=true filters
	 * for curse books, cursed=false for grindable ones. */
	private static @Nullable Slot findBookSlot(net.minecraft.client.player.LocalPlayer player, boolean cursed) {
		for (Slot slot : player.containerMenu.slots) {
			if (slot.container == player.getInventory()
					&& slot.getItem().is(Items.ENCHANTED_BOOK)
					&& isCursed(slot.getItem()) == cursed) {
				return slot;
			}
		}
		return null;
	}

	/** Curse check: vanilla curse tag or a user-configured mod curse id. */
	private static boolean isCursed(ItemStack stack) {
		if (!stack.is(Items.ENCHANTED_BOOK)) return false;
		ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
		if (enchantments.isEmpty()) return false;
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		Minecraft mc = Minecraft.getInstance();
		Registry<Enchantment> registry = mc.level == null ? null
				: mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		for (Holder<Enchantment> holder : enchantments.keySet()) {
			if (holder.is(EnchantmentTags.CURSE)) return true;
			if (registry != null && !config.customCurseEnchantments.isEmpty()) {
				Identifier id = registry.getKey(holder.value());
				if (id != null && config.customCurseEnchantments.contains(id.toString())) return true;
			}
		}
		return false;
	}

	/** Inventory slot (0-35) to inventoryMenu slot index: storage maps 1:1
	 * (9-35), the hotbar sits at 36-44 (matches LecternRestock's offhand=45). */
	private static int inventoryMenuSlot(int invSlot) {
		return invSlot < 9 ? 36 + invSlot : invSlot;
	}

	private static int findNonBookHotbarSlot(net.minecraft.client.player.LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (!player.getInventory().getItem(i).is(Items.ENCHANTED_BOOK)) return i;
		}
		return -1;
	}

	private static int findEmptyHotbarSlot(net.minecraft.client.player.LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).isEmpty()) return i;
		}
		return -1;
	}
}
