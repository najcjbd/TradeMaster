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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
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
	/** Bound on the wait, so a REJECTED batch is retried instead of stalling forever. */
	private static final int RESTOCK_INFLIGHT_MAX_TICKS = 60;

	/** Marks a batch as in flight from {@code fromContainerSlot} (-1 when the effect is
	 * only visible on the offhand / in the menu state). */
	private static void markRestockInFlight(int fromContainerSlot) {
		restockInFlightTicks = 0;
		restockFromSlot = fromContainerSlot;
	}

	public static boolean isLecternAt(BlockGetter level, BlockPos pos) {
		return level.getBlockState(pos).getBlock() instanceof LecternBlock;
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
			if (LecternRestock.isLecternAt(level, pos)) {
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
	}

	public static boolean offhandHasLectern(LocalPlayer player) {
		return player.getOffhandItem().getItem().equals(Items.LECTERN);
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

	/** Moves the whole lectern stack into the offhand via packets. Returns false when none is available. */
	public static boolean restock(LocalPlayer player) {
		if (offhandHasLectern(player)) return true;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode == null) return false;
		// a batch is in flight: wait until its effect is visible (the server broadcast),
		// bounded so a REJECTED batch is retried instead of stalling forever
		if (restockInFlightTicks >= 0) {
			boolean landed = player.getOffhandItem().getItem() == Items.LECTERN
					|| (restockFromSlot == RESTOCK_FROM_CLOSE
							&& player.containerMenu == player.inventoryMenu)
					|| (restockFromSlot >= 0
							&& player.getInventory().getItem(restockFromSlot).getItem() != Items.LECTERN);
			if (!landed && ++restockInFlightTicks < RESTOCK_INFLIGHT_MAX_TICKS) return true;
			restockInFlightTicks = -1;
			restockFromSlot = -1;
		}
		// never batch while the cursor is dirty: our raw clicks would interleave with
		// whatever is on it, and a leftover cursor is a state we do not understand
		if (!player.containerMenu.getCarried().isEmpty()) return true;

		if (player.inventoryMenu == player.containerMenu) {
			for (int i = 9; i < 45; i++) {
				if (player.getInventory().getItem(i >= 36 ? i - 36 : i).getItem() != Items.LECTERN) continue;
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
				if (player.getInventory().getItem(i).getItem() != Items.LECTERN) continue;
				rememberSelection(player);
				if (i != player.getInventory().getSelectedSlot()) {
					Interactions.selectHotbarSlot(player, i);
				}
				player.connection.send(new ServerboundPlayerActionPacket(
						ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
						BlockPos.ZERO, Direction.DOWN));
				markRestockInFlight(-1);
				return true;
			}
			// a lectern deep in the main inventory is unreachable while a foreign
			// menu is open: close it so the next retry takes the normal path
			// instead of reporting the lectern lost
			for (int i = 9; i < 36; i++) {
				if (player.getInventory().getItem(i).getItem() == Items.LECTERN) {
					player.closeContainer();
					markRestockInFlight(RESTOCK_FROM_CLOSE);
					return true;
				}
			}
		}
		return false;
	}

	public enum AxeState { READY, WORKING, NONE }

	/**
	 * Makes the main hand hold an axe in a single inventory pass: hotbar axes
	 * are selected directly, inventory axes are SWAPped into the selected slot.
	 * WORKING means "try again next tick" (swap in flight or menu not open).
	 * With preventAxeBreaking on, nearly-broken axes are skipped in favour of
	 * healthy ones (a broken one is used last so the caller's guard still fires),
	 * and an offhand axe — which Preconditions counts as carried — is swapped
	 * into the main hand instead of failing at the first lectern break.
	 */
	public static AxeState bringAxeToHand(Minecraft mc, LocalPlayer player) {
		var inventory = player.getInventory();
		boolean guardDurability = TradeMasterConfig.INSTANCE.preventAxeBreaking;
		ItemStack held = inventory.getSelectedItem();
		// a nearly-broken axe already in hand must not short-circuit: the skip
		// loop below still has to run so a healthy spare can replace it
		if (held.getItem() instanceof AxeItem && (!guardDurability || !axeNearlyBroken(held))) {
			return AxeState.READY;
		}
		int brokenFallback = -1;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = inventory.getItem(i);
			if (!(stack.getItem() instanceof AxeItem)) continue;
			if (guardDurability && axeNearlyBroken(stack)) {
				if (brokenFallback < 0) brokenFallback = i;
				continue;
			}
			return equipAxe(mc, player, i);
		}
		ItemStack offhand = player.getOffhandItem();
		if (offhand.getItem() instanceof AxeItem
				&& (!guardDurability || !axeNearlyBroken(offhand) || brokenFallback < 0)) {
			// menu slot 45 is the offhand in InventoryMenu (0 result, 1-4 craft,
			// 5-8 armor, 9-35 main, 36-44 hotbar) — slot 40 is hotbar slot 4!
			// The predicted SWAP applies client-side immediately, so the next tick
			// sees the result instead of resending a raw toggle packet. A broken
			// offhand axe is swapped in only as last resort, so the caller's
			// durability guard reports axe_breaking rather than axe_required.
			if (mc.gameMode == null || player.containerMenu != player.inventoryMenu) return AxeState.WORKING;
			rememberSelection(player);
			mc.gameMode.handleContainerInput(0, 45, inventory.getSelectedSlot(),
					ContainerInput.SWAP, player);
			return AxeState.WORKING;
		}
		return brokenFallback >= 0 ? equipAxe(mc, player, brokenFallback) : AxeState.NONE;
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

	private static AxeState equipAxe(Minecraft mc, LocalPlayer player, int slot) {
		rememberSelection(player);
		if (slot < 9) {
			Interactions.selectHotbarSlot(player, slot);
			return AxeState.READY;
		}
		if (mc.gameMode == null || player.containerMenu != player.inventoryMenu) return AxeState.WORKING;
		// InventoryMenu maps main-inventory slots 9-35 onto themselves
		mc.gameMode.handleContainerInput(0, slot, player.getInventory().getSelectedSlot(),
				ContainerInput.SWAP, player);
		return AxeState.WORKING;
	}

	/** Whether the given axe stack is at or below the preventAxeBreaking durability threshold. */
	public static boolean axeNearlyBroken(ItemStack stack) {
		return stack.getItem() instanceof AxeItem
				&& stack.getMaxDamage() - stack.getDamageValue() <= 5;
	}

	/** Whether the held axe is at or below the preventAxeBreaking durability threshold. */
	public static boolean axeNearlyBroken(LocalPlayer player) {
		return axeNearlyBroken(player.getInventory().getSelectedItem());
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
