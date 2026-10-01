package dev.trademaster.exec;

import dev.trademaster.anticheat.LookStep;
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

	public enum Result { RUNNING, HAS_CART, NO_CART, NO_DISPENSER, FAILED }

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
	/** The container id of the dispenser menu THIS check opened, or -1 before it opened one.
	 * Closing is keyed on it so the player's own dispenser/chest is never touched. */
	private int openedContainerId = -1;

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
					rememberMenu(menu);
					enter(State.SCAN);
					return Result.RUNNING;
				}
				// a leftover menu of unknown origin would swallow the click
				if (player.containerMenu != player.inventoryMenu) {
					player.closeContainer();
					return Result.RUNNING;
				}
				BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(this.pos), Direction.UP, this.pos, false);
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
				player.swing(InteractionHand.MAIN_HAND, true);
				enter(State.WAIT_MENU);
			}
			case WAIT_MENU -> {
				if (player.containerMenu instanceof DispenserMenu menu) {
					rememberMenu(menu);
					enter(State.SCAN);
				} else if (this.stateTicks > MAX_MENU_WAIT_TICKS) {
					// the server refused the open (out of reach / desync): let the caller
					// wait and retry rather than jamming here
					return Result.FAILED;
				}
			}
			case SCAN -> {
				// a DispenserMenu carries the dispenser's 9 slots FIRST, then the player's
				for (int i = 0; i < 9 && i < player.containerMenu.slots.size(); i++) {
					if (player.containerMenu.slots.get(i).getItem().is(Items.MINECART)) {
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

	/** Closes the container THIS check opened, and nothing else: the player may have opened
	 * their own dispenser or chest around the same time. Safe with a null player (a
	 * disconnect mid-check) and before anything was opened. */
	private void closeOwnMenu(@Nullable LocalPlayer player) {
		if (player == null) return;
		if (!(player.containerMenu instanceof DispenserMenu menu)) return;
		if (menu.containerId != this.openedContainerId) return;
		player.closeContainer();
	}

	/** Releases an abandoned check (the gate was disabled mid-flight): closes the container
	 * THIS check opened, so it cannot swallow the next summon's button press. */
	public void abort(@Nullable LocalPlayer player) {
		closeOwnMenu(player);
	}
}
