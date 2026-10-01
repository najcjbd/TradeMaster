package dev.trademaster.exec;

import dev.trademaster.config.TradeMasterConfig;
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

	private final BlockPos pos;
	private final LookStep look = new LookStep();
	private State state = State.LOOK;
	private boolean found = false;
	private int ticks = 0;

	public DispenserCheck(BlockPos pos) {
		this.pos = pos.immutable();
	}

	public Result tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) return Result.FAILED;
		if (++this.ticks > 80) {
			close(player);
			return Result.FAILED;
		}
		switch (this.state) {
			case LOOK -> {
				// a distinct result: this position holds no dispenser, so waiting out a
				// "missing cart" cycle could never help (wrong coordinate / replaced block)
				if (!mc.level.getBlockState(this.pos).is(Blocks.DISPENSER)) return Result.NO_DISPENSER;
				if (!this.look.tick(player, Vec3.atCenterOf(this.pos))) return Result.RUNNING;
				this.state = State.USE;
			}
			case USE -> {
				if (player.containerMenu instanceof DispenserMenu) {
					this.state = State.SCAN;
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
				this.state = State.WAIT_MENU;
			}
			case WAIT_MENU -> {
				if (player.containerMenu instanceof DispenserMenu) {
					this.state = State.SCAN;
				} else if (this.ticks > 40) {
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
				close(player);
				return this.found ? Result.HAS_CART : Result.NO_CART;
			}
		}
		return Result.RUNNING;
	}

	private static void close(LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
	}

	/** Releases an abandoned check (the gate was disabled mid-flight): closes a container
	 * THIS check opened, so it cannot swallow the next summon's button press. Safe to call
	 * when nothing was opened — LOOK has not interacted yet. */
	public void abort(LocalPlayer player) {
		if (this.state != State.LOOK) close(player);
	}
}
