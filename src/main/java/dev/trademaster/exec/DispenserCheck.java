package dev.trademaster.exec;


import dev.trademaster.anticheat.LookStep;
import dev.trademaster.compat.McInteract;
import dev.trademaster.compat.McPackets;
import dev.trademaster.core.Interactions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Opens the configured dispenser and reports whether it holds a minecart.
 *
 * <p>A dispenser's contents are SERVER-side block-entity data: the only client-side way
 * to see its slots is to open the container once, read them, and close it. The rig's
 * minecart dispenser is continuously refilled by a clone command block, so a missing cart
 * is normally transient — the caller waits and retries rather than stopping.
 */
public final class DispenserCheck {

	public enum Result { RUNNING, HAS_CART, NO_CART, NO_DISPENSER, CURSOR_BLOCKED, MENU_BLOCKED, FAILED }

	private enum State { LOOK, USE, WAIT_MENU, SCAN }

	/** Whole-cycle safety net (a wedged rotation, a menu that never opens). */
	private static final int MAX_TICKS = 200;
	/** Per-state budget for waiting for the menu the use packet should open (2s),
	 * measured from entering WAIT_MENU — the whole-cycle counter also counts the rotation,
	 * so using it here could declare a refusal before the packet had a chance to land. */
	private static final int MAX_MENU_WAIT_TICKS = 40;

	private final BlockPos pos;
	private final LookStep look = new LookStep();
	private State state = State.LOOK;
	private boolean found = false;
	private int ticks = 0;
	private int stateTicks = 0;
	/** Ticks a menu that belongs to the PLAYER has blocked the use click. */
	private int foreignMenuTicks = 0;
	/** Container id of the dispenser menu THIS check opened, or -1 before it opened one.
	 * Closing is keyed on it so the player's own dispenser/chest is never touched. */
	private int openedContainerId = -1;
	/** Position whose use packet a previous check sent before timing out with no menu,
	 * and the game time it happened. A DispenserMenu appearing there shortly after is
	 * that check's LATE response (the controller keeps the position open across its
	 * retry wait), so the next check may adopt and close it as its own. */
	private static @Nullable BlockPos lateOpenPos = null;
	private static long lateOpenTick = 0;
	/** Covers the controller's 100-tick retry wait; a wider window would attribute an
	 * unrelated menu to the old send. */
	private static final long LATE_OPEN_WINDOW_TICKS = 120;

	public DispenserCheck(BlockPos pos) {
		this.pos = pos.immutable();
	}

	public Result tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) return Result.FAILED;
		// a wedged rotation or a menu that never opens must not hang the caller: this is a
		// safety net only, the per-state budgets below do the real bounding
		if (++this.ticks > MAX_TICKS) {
			closeOwnMenu(player);
			if (player.containerMenu != player.inventoryMenu) {
				// in WAIT_MENU the menu that just appeared is ours even before the id
				// was captured (the use packet was sent this cycle)
				boolean ours = player.containerMenu instanceof DispenserMenu
						&& (this.openedContainerId >= 0
								? player.containerMenu.containerId == this.openedContainerId
								: this.state == State.WAIT_MENU);
				if (ours) {
					// our menu survived the safe close: unreturnable cursor vs a
					// stale/duplicated container that the player has to close
					return !player.containerMenu.getCarried().isEmpty()
							&& !Interactions.hasRoomForCarried(player)
							? Result.CURSOR_BLOCKED : Result.MENU_BLOCKED;
				}
				// a player-opened container (any cursor state) is what blocks this check
				return Result.MENU_BLOCKED;
			}
			return Result.FAILED;
		}
		this.stateTicks++;
		switch (this.state) {
			case LOOK -> {
				// a distinct result: this position holds no dispenser, so waiting out a
				// "missing cart" cycle could never help (wrong coordinate / replaced block)
				if (!mc.level.getBlockState(this.pos).is(Blocks.DISPENSER)) return Result.NO_DISPENSER;
				if (!this.look.tick(player, Vec3.atCenterOf(this.pos))) return Result.RUNNING;
				enter(State.USE);
			}
			case USE -> {
				if (player.containerMenu instanceof DispenserMenu menu) {
					// a menu already open here is NOT ours (this cycle has not sent its use
					// packet yet) unless it is the one we opened before: adopting the player's
					// own dispenser would SCAN their slots and then close their container.
					// Never close a foreign menu either — wait briefly for its owner to close
					// it, then report it as the blocker instead of stealing their container.
					if (this.openedContainerId >= 0 && menu.containerId == this.openedContainerId) {
						enter(State.SCAN);
						return Result.RUNNING;
					}
					if (takeLateOwnMenu(mc)) {
						// a previous check's use packet was merely slow: adopt the menu
						// instead of waiting out a foreign-menu timeout and stopping
						rememberMenu(menu);
						enter(State.SCAN);
						return Result.RUNNING;
					}
					if (++this.foreignMenuTicks > 40) return Result.MENU_BLOCKED;
					return Result.RUNNING;
				}
				// a leftover menu of unknown origin would swallow the click — and it belongs
				// to the PLAYER, so closing it is not this check's business either
				if (player.containerMenu != player.inventoryMenu) {
					if (++this.foreignMenuTicks > 40) return Result.MENU_BLOCKED;
					return Result.RUNNING;
				}
				this.foreignMenuTicks = 0;
				// this check now has its own use in flight: a previous attempt's late
				// memory must not attribute an unrelated menu to it later
				lateOpenPos = null;
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(this.pos), Direction.UP, this.pos, false);
				McInteract.useItemOn(mc.gameMode, player, InteractionHand.MAIN_HAND, hit);
				McPackets.swingOnly(player, InteractionHand.MAIN_HAND);
				enter(State.WAIT_MENU);
			}
			case WAIT_MENU -> {
				if (player.containerMenu instanceof DispenserMenu menu) {
					// the use packet was sent this cycle, so this menu is ours
					rememberMenu(menu);
					enter(State.SCAN);
				} else if (this.stateTicks > MAX_MENU_WAIT_TICKS) {
					// the server refused the open (out of reach / desync) or its answer is
					// merely late: remember the send so a menu that still arrives during
					// the caller's retry wait is recognized as OURS by the next check
					rememberLateOpen(mc);
					return Result.FAILED;
				}
			}
			case SCAN -> {
				// the menu detected last tick can vanish or be replaced before this one; reading
				// whatever is open now (the player inventory's craft/armour slots, or a foreign
				// container) would silently scan the WRONG slots and misreport HAS_CART/NO_CART.
				// Only the dispenser THIS check opened is scanned.
				if (!(player.containerMenu instanceof DispenserMenu menu)
						|| menu.containerId != this.openedContainerId) {
					closeOwnMenu(player);
					return Result.FAILED;
				}
				// a DispenserMenu carries the dispenser's 9 slots FIRST, then the player's
				for (int i = 0; i < 9 && i < menu.slots.size(); i++) {
					if (menu.slots.get(i).getItem().is(Items.MINECART)) {
						this.found = true;
						break;
					}
				}
				closeOwnMenu(player);
				return this.found ? Result.HAS_CART : Result.NO_CART;
			}
		}
		return Result.RUNNING;
	}

	private void enter(State next) {
		this.state = next;
		this.stateTicks = 0;
	}

	private void rememberMenu(DispenserMenu menu) {
		if (this.openedContainerId < 0) this.openedContainerId = menu.containerId;
	}

	/** Records that this check sent a use packet and timed out before the menu arrived. */
	private void rememberLateOpen(Minecraft mc) {
		if (mc.level == null) return;
		lateOpenPos = this.pos;
		lateOpenTick = mc.level.getGameTime();
	}

	/** Whether an already-open dispenser menu is the LATE answer to the previous check's
	 * use packet for this same position (bounded to the caller's retry window). */
	private boolean takeLateOwnMenu(Minecraft mc) {
		if (lateOpenPos == null || !lateOpenPos.equals(this.pos)) return false;
		long elapsed = mc.level == null ? Long.MAX_VALUE : mc.level.getGameTime() - lateOpenTick;
		if (elapsed < 0 || elapsed > LATE_OPEN_WINDOW_TICKS) {
			lateOpenPos = null;
			return false;
		}
		lateOpenPos = null;
		return true;
	}

	/** Clears the cross-check late-open memory (a new run must not adopt an old menu). */
	public static void resetLateOpenMemory() {
		lateOpenPos = null;
		lateOpenTick = 0;
	}

	/** Closes the container THIS check opened, and nothing else: the player may have opened
	 * their own dispenser or chest around the same time. Safe with a null player (a
	 * disconnect mid-check) and before anything was opened. */
	private void closeOwnMenu(@Nullable LocalPlayer player) {
		if (player == null) return;
		if (!(player.containerMenu instanceof DispenserMenu menu)) return;
		// ours by id; and in WAIT_MENU the menu that just appeared is ours even before the id
		// was captured (a one-tick window in which an abort could otherwise leave it behind)
		boolean ours = menu.containerId == this.openedContainerId
				|| (this.openedContainerId < 0 && this.state == State.WAIT_MENU);
		if (!ours) return;
		Interactions.closeMenuSafely(player);
	}

	/** Releases an abandoned check (the gate was disabled mid-flight): closes the container
	 * THIS check opened, so it cannot swallow the next summon's button press. */
	public void abort(@Nullable LocalPlayer player) {
		closeOwnMenu(player);
	}
}
