package dev.trademaster.strategy;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.Interactions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ensures the player holds a lectern in the offhand (ported from
 * LibrarianTradeFinder's PLACE state, GPL-3.0, Greenman999).
 */
public final class LecternRestock {

	private LecternRestock() {}

	/** Placement attempts are spaced out and capped; shared by all placement paths. */
	public static final int MAX_PLACE_ATTEMPTS = 5;
	public static final int PLACE_COOLDOWN_TICKS = 10;

	/** Ticks the in-flight restock batch has been waiting, or -1 when none is pending. A
	 * batch is RAW clicks (no client prediction): the client's slots only update when the
	 * server broadcast arrives, so a second batch before that would click a STALE slot —
	 * e.g. PICKUP the offhand and strand the lectern on the cursor. */
	private static int restockInFlightTicks = -1;
	/** Container slot the in-flight batch took the lectern FROM (to observe the move
	 * landing), or -1 for the hotbar-swap / close-container batches. */
	private static int restockFromSlot = -1;
	/** Bound on the wait, so a REJECTED batch is retried instead of stalling forever.
	 * Kept well under the callers' phase budgets (strategies 200, guardian 400 ticks):
	 * one rejected batch must not eat a large part of a phase. */
	private static final int RESTOCK_INFLIGHT_MAX_TICKS = 30;
	/** Ticks a dirty cursor has blocked the raw-PICKUP batch, and its bound: past it we
	 * report "no lectern" so the caller stops cleanly instead of waiting forever. */
	private static int restockCarriedWaitTicks = 0;
	private static final int RESTOCK_CARRIED_MAX_TICKS = 40;

	/** Marks a batch as in flight from {@code fromContainerSlot} (-1 when the effect is
	 * only visible on the offhand / in the menu state). */
	private static void markRestockInFlight(int fromContainerSlot) {
		restockInFlightTicks = 0;
		restockFromSlot = fromContainerSlot;
	}

	public static boolean isWorkBlockAt(BlockGetter level, BlockPos pos) {
		return RigTarget.isWorkBlockAt(level, pos);
	}

	/** How many consecutive absent readings prove a break landed on the server. */
	private static final int BREAK_CONFIRM_TICKS = 10;

	/** Per-break-action server-confirmation gate. A client-predicted break is NOT proof
	 * the server accepted it: {@code continueDestroyBlock} removes the block locally at
	 * once, and a rejected break restores it within a round trip. The lectern must read
	 * ABSENT for {@link #BREAK_CONFIRM_TICKS} consecutive ticks before it counts as gone.
	 * One instance PER BREAKER (so two breakers never share the counter) and {@link #observe}
	 * must be called EVERY tick for the target position: a present reading resets it — a
	 * re-appearing lectern must reset, not merely pause, or ten absent ticks spread over
	 * several rejected breaks would still confirm. */
	public static final class BreakGate {
		private BlockPos absentPos = null;
		private int absentTicks = 0;

		public boolean observe(BlockGetter level, BlockPos pos) {
			if (LecternRestock.isWorkBlockAt(level, pos)) {
				this.absentPos = null;
				this.absentTicks = 0;
				return false;
			}
			if (this.absentPos == null || !this.absentPos.equals(pos)) {
				this.absentPos = pos.immutable();
				this.absentTicks = 0;
			}
			return ++this.absentTicks >= BREAK_CONFIRM_TICKS;
		}

		public void reset() {
			this.absentPos = null;
			this.absentTicks = 0;
		}
	}

	/** Call at session start so a stale gate from a previous run doesn't delay restocking. */
	public static void resetRestock() {
		restockInFlightTicks = -1;
		restockFromSlot = -1;
		restockCarriedWaitTicks = 0;
		// a stale tool lock from a previous run may point at an item that is gone, and
		// the configured block may have changed
		RigTarget.clearToolLock();
		// a run that ended without stop() (disconnect / crash / world change) would otherwise
		// leak the remembered hotbar slot into the next run's restoreOriginalSelection
		originalSelected = -1;
	}

	/** Whether the offhand already holds the configured work block's item. */
	public static boolean offhandHasWorkItem(LocalPlayer player) {
		Item item = RigTarget.workItem();
		return item != null && player.getOffhandItem().getItem() == item;
	}

	/**
	 * Places the lectern from the offhand by sending the use-item packet
	 * directly, bypassing {@code gameMode.useItemOn} and its client-side block
	 * prediction. With prediction, a placement the server rejects still shows a
	 * lectern client-side (ghost block) and block-state "verification" passes;
	 * this way the lectern only appears once the server confirms it with a
	 * block update.
	 */
	public static void placeLectern(Minecraft mc, LocalPlayer player, BlockPos lecternPos) {
		BlockHitResult hit = new BlockHitResult(
				Vec3.atCenterOf(lecternPos), Direction.UP, lecternPos.below(), false);
		int sequence = mc.level.getBlockStatePredictionHandler().currentSequence();
		player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, sequence));
		player.swing(InteractionHand.OFF_HAND, true);
		player.connection.send(new ServerboundSwingPacket(InteractionHand.OFF_HAND));
	}

	/** Marks a close-container batch: its effect is only visible once the menu is gone. */
	private static final int RESTOCK_FROM_CLOSE = -2;

	/** Moves the whole work-block stack into the offhand via packets. Returns false when none is available. */
	public static boolean restock(LocalPlayer player) {
		if (offhandHasWorkItem(player)) {
			// the batch landed: retire the in-flight state HERE, or the stale slot would
			// make the NEXT restock wait out the whole bound before sending anything
			restockInFlightTicks = -1;
			restockFromSlot = -1;
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode == null) return false;
		// the configured work block's item (null when the block has none): with nothing to
		// stock there is nothing to restock
		Item work = RigTarget.workItem();
		if (work == null) return false;
		// a batch is in flight: wait until its effect is visible (the server broadcast),
		// bounded so a REJECTED batch is retried instead of stalling forever
		if (restockInFlightTicks >= 0) {
			boolean landed = (restockFromSlot == RESTOCK_FROM_CLOSE
							&& player.containerMenu == player.inventoryMenu)
					|| (restockFromSlot >= 0
							&& player.getInventory().getItem(restockFromSlot).getItem() != work);
			if (!landed && ++restockInFlightTicks < RESTOCK_INFLIGHT_MAX_TICKS) return true;
			restockInFlightTicks = -1;
			restockFromSlot = -1;
		}

		if (player.inventoryMenu == player.containerMenu) {
			// the raw PICKUP batch must not run with a dirty cursor: our clicks would
			// interleave with whatever is on it, and a leftover cursor is a state we do not
			// understand. Bounded — if it stays dirty, report "no lectern" so the caller
			// stops cleanly instead of waiting forever.
			if (!player.containerMenu.getCarried().isEmpty()) {
				if (++restockCarriedWaitTicks < RESTOCK_CARRIED_MAX_TICKS) return true;
				restockCarriedWaitTicks = 0;
				return false;
			}
			restockCarriedWaitTicks = 0;
			for (int i = 9; i < 45; i++) {
				if (player.getInventory().getItem(i >= 36 ? i - 36 : i).getItem() != work) continue;
				boolean itemInOffhand = !player.getOffhandItem().isEmpty();
				var container = player.containerMenu;

				ContainerInput pickupInput = ContainerInput.PICKUP;
				Int2ObjectMap<HashedStack> emptyChangedSlots = new Int2ObjectOpenHashMap<>();
				HashedStack emptyCarried = HashedStack.EMPTY;
				// the server increments the state id after each accepted click; predict
				// it for the follow-up packets or each mismatch forces a full resync
				int stateId = container.getStateId();

				player.connection.send(new ServerboundContainerClickPacket(
						container.containerId, stateId, (short) i, (byte) 0,
						pickupInput, emptyChangedSlots, emptyCarried));
				player.connection.send(new ServerboundContainerClickPacket(
						container.containerId, stateId + 1, (short) 45, (byte) 0,
						pickupInput, emptyChangedSlots, emptyCarried));
				if (itemInOffhand) {
					player.connection.send(new ServerboundContainerClickPacket(
							container.containerId, stateId + 2, (short) i, (byte) 0,
							pickupInput, emptyChangedSlots, emptyCarried));
				}
				markRestockInFlight(i >= 36 ? i - 36 : i);
				return true;
			}
		} else {
			for (int i = 0; i < 9; i++) {
				if (player.getInventory().getItem(i).getItem() != work) continue;
				rememberSelection(player);
				if (i != player.getInventory().getSelectedSlot()) {
					Interactions.selectHotbarSlot(player, i);
				}
				player.connection.send(new ServerboundPlayerActionPacket(
						ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
						BlockPos.ZERO, Direction.DOWN));
				// watch the HOTBAR slot: with a foreign menu open the offhand is not among
				// its slots, so the client never sees the offhand change — the swapped slot
				// does update, and its lectern leaving is the observable landing signal
				markRestockInFlight(i);
				return true;
			}
			// a lectern deep in the main inventory is unreachable while a foreign
			// menu is open: close it so the next retry takes the normal path
			// instead of reporting the lectern lost
			for (int i = 9; i < 36; i++) {
				if (player.getInventory().getItem(i).getItem() == work) {
					player.closeContainer();
					markRestockInFlight(RESTOCK_FROM_CLOSE);
					return true;
				}
			}
		}
		return false;
	}

	public enum ToolState { READY, WORKING, NONE }

	/**
	 * Makes the main hand hold the tool that breaks the CONFIGURED work block: its best
	 * tool among the player's items (see {@link RigTarget}, which also LOCKS the choice so
	 * equal tools are not swapped every tick). A hotbar item is selected directly, an
	 * inventory item is SWAPped into the selected slot, an offhand item is swapped in.
	 * WORKING means "try again next tick" (swap in flight or menu not open). With
	 * preventAxeBreaking on, nearly-broken tools are skipped in favour of healthy ones (a
	 * broken one is used last so the caller's durability guard still fires).
	 *
	 * <p>When the block has NO better-than-hand tool the LEGACY behaviour is kept: an axe
	 * is required (what a lectern rig has always used).
	 */
	public static ToolState bringToolToHand(Minecraft mc, LocalPlayer player, BlockState state) {
		Item tool = RigTarget.chooseTool(state, player);
		if (tool != null) return equipChosenTool(mc, player, tool);
		// a block that only drops for a tool the player does NOT carry must not be broken:
		// the legacy axe would destroy the work block without a drop and stall the run.
		// NONE makes the caller fail with RigTarget.toolRequiredKey's dedicated wording.
		if (RigTarget.cannotHarvest(state, player)) return ToolState.NONE;
		return bringLegacyAxeToHand(mc, player);
	}

	/** Equips the derived tool (by item identity), preferring a healthy copy. */
	private static ToolState equipChosenTool(Minecraft mc, LocalPlayer player, Item tool) {
		var inventory = player.getInventory();
		boolean guardDurability = TradeMasterConfig.INSTANCE.preventAxeBreaking;
		ItemStack held = inventory.getSelectedItem();
		// a nearly-broken copy already in hand must not short-circuit: the skip loop
		// below still has to run so a healthy spare can replace it
		if (held.getItem() == tool && (!guardDurability || !toolNearlyBroken(held))) {
			return ToolState.READY;
		}
		int brokenFallback = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.getItem() != tool) continue;
			if (guardDurability && toolNearlyBroken(stack)) {
				if (brokenFallback < 0) brokenFallback = i;
				continue;
			}
			return equipTool(mc, player, i);
		}
		if (player.getOffhandItem().getItem() == tool
				&& (!guardDurability || !toolNearlyBroken(player.getOffhandItem()) || brokenFallback < 0)) {
			return equipOffhandTool(mc, player);
		}
		return brokenFallback >= 0 ? equipTool(mc, player, brokenFallback) : ToolState.NONE;
	}

	/** The legacy path: the block has no better-than-hand tool, so an axe is required. */
	private static ToolState bringLegacyAxeToHand(Minecraft mc, LocalPlayer player) {
		var inventory = player.getInventory();
		boolean guardDurability = TradeMasterConfig.INSTANCE.preventAxeBreaking;
		ItemStack held = inventory.getSelectedItem();
		if (held.getItem() instanceof AxeItem && (!guardDurability || !toolNearlyBroken(held))) {
			return ToolState.READY;
		}
		int brokenFallback = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!(stack.getItem() instanceof AxeItem)) continue;
			if (guardDurability && toolNearlyBroken(stack)) {
				if (brokenFallback < 0) brokenFallback = i;
				continue;
			}
			return equipTool(mc, player, i);
		}
		ItemStack offhand = player.getOffhandItem();
		if (offhand.getItem() instanceof AxeItem
				&& (!guardDurability || !toolNearlyBroken(offhand) || brokenFallback < 0)) {
			return equipOffhandTool(mc, player);
		}
		return brokenFallback >= 0 ? equipTool(mc, player, brokenFallback) : ToolState.NONE;
	}

	/** Swaps the offhand item into the main hand. Menu slot 45 is the offhand in
	 * InventoryMenu (0 result, 1-4 craft, 5-8 armor, 9-35 main, 36-44 hotbar) — slot 40
	 * is hotbar slot 4! The predicted SWAP applies client-side immediately, so the next
	 * tick sees the result instead of resending a raw toggle packet. A broken offhand tool
	 * is swapped in only as last resort, so the caller's durability guard still fires. */
	private static ToolState equipOffhandTool(Minecraft mc, LocalPlayer player) {
		if (mc.gameMode == null) return ToolState.WORKING;
		if (player.containerMenu != player.inventoryMenu) {
			// the swap below needs the inventory menu: with a foreign menu open it never
			// runs and the caller loops until its phase timeout. Close it (like restock's
			// close branch) so the next tick takes the normal path.
			player.closeContainer();
			return ToolState.WORKING;
		}
		rememberSelection(player);
		mc.gameMode.handleContainerInput(0, 45, player.getInventory().getSelectedSlot(),
				ContainerInput.SWAP, player);
		return ToolState.WORKING;
	}

	/** The hotbar slot selected before this run started moving items around; -1 when
	 * nothing was moved. Restored by stop() so an interrupted run does not leave the
	 * player's hand on the axe / lectern slot. */
	private static int originalSelected = -1;

	private static void rememberSelection(LocalPlayer player) {
		if (originalSelected < 0) originalSelected = player.getInventory().getSelectedSlot();
	}

	/** Puts the hand back on the slot it was on before the run started moving items.
	 * Only the SELECTION is restored: an inventory/offhand SWAP that already went
	 * out is not undone (swapping back needs the original pair and races the
	 * server's own slot sync; the moved item simply ends up in a hotbar slot, which
	 * is visible and harmless). */
	public static void restoreOriginalSelection() {
		int slot = originalSelected;
		originalSelected = -1;
		if (slot < 0) return;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;
		if (player.getInventory().getSelectedSlot() != slot) {
			Interactions.selectHotbarSlot(player, slot);
		}
	}

	private static ToolState equipTool(Minecraft mc, LocalPlayer player, int slot) {
		rememberSelection(player);
		if (slot < 9) {
			Interactions.selectHotbarSlot(player, slot);
			return ToolState.READY;
		}
		if (mc.gameMode == null) return ToolState.WORKING;
		if (player.containerMenu != player.inventoryMenu) {
			// the swap below needs the inventory menu: with a foreign menu open it never runs
			// and the caller loops until its phase timeout. Close it, like the offhand path.
			player.closeContainer();
			return ToolState.WORKING;
		}
		// InventoryMenu maps main-inventory slots 9-35 onto themselves
		mc.gameMode.handleContainerInput(0, slot, player.getInventory().getSelectedSlot(),
				ContainerInput.SWAP, player);
		return ToolState.WORKING;
	}

	/** Whether the given TOOL stack is at or below the preventAxeBreaking durability
	 * threshold (5, as before). Non-damageable stacks are never "nearly broken". */
	public static boolean toolNearlyBroken(ItemStack stack) {
		return RigTarget.isNearlyBroken(stack);
	}

	/** Whether the held tool is at or below the preventAxeBreaking durability threshold. */
	public static boolean toolNearlyBroken(LocalPlayer player) {
		return toolNearlyBroken(player.getInventory().getSelectedItem());
	}

	/**
	 * Placement-clear gate: blocks plus living entities intersecting the lectern
	 * block. Level.noCollision(AABB) alone is blind to villagers — it only tests
	 * entities with canBeCollidedWith() true, which villagers are not — yet the
	 * server still rejects placements that collide with them. The local player is
	 * excluded: it never moves during a run (raw tp packets don't move the client
	 * position), so waiting on it would stall forever.
	 */
	public static boolean lecternSpotClear(Level level, BlockPos pos, LocalPlayer player) {
		AABB box = new AABB(pos);
		if (!level.noCollision(box)) return false;
		return level.getEntitiesOfClass(LivingEntity.class, box,
				e -> e != player && e.isAlive()).isEmpty();
	}

	/** One main-hand breaking tick on the block, swinging visibly and server-side. */
	public static void breakBlock(Minecraft mc, LocalPlayer player, BlockPos pos) {
		player.swing(InteractionHand.MAIN_HAND, true);
		mc.gameMode.continueDestroyBlock(pos, Direction.UP);
		player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
	}
}
