package dev.trademaster.exec;

import dev.trademaster.anticheat.LookStep;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.Interactions;
import dev.trademaster.strategy.RerollStrategy.StepResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Presses a button by sending the use/swing packets directly (LPCTools
 * style). Respects legit rotation and slow mode; switches to an empty
 * hotbar slot first so no block is accidentally placed. The press is only
 * reported DONE once the button state actually reads POWERED — a dropped
 * packet or a still-powered button would otherwise sail into the watchdog-
 * exempt AWAIT_VILLAGER and hang the run forever.
 */
public class ButtonPresser {

	private final BlockPos buttonPos;
	private final LookStep look = new LookStep();
	private int ticks = 0;
	private int delay;
	private boolean switchedSlot = false;
	private boolean pressed = false;
	private int confirmTicks = 0;
	private int presses = 0;
	/** Ticks spent waiting for residual power to decay before the first press. */
	private int poweredWaitTicks = 0;
	/** Hotbar slot selected before the empty-slot switch; restored on every
	 * terminal exit so the player keeps their original hand (R22-13). */
	private int originalSlot = -1;

	public ButtonPresser(BlockPos buttonPos) {
		this.buttonPos = buttonPos.immutable();
		this.delay = TradeMasterConfig.INSTANCE.interactDelay;
	}

	public StepResult tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			// restoreSlot only needs player.connection: keep the exit symmetric
			// with ChestDepositor's early guard even in the disconnect window
			if (player != null) restoreSlot(player);
			return StepResult.failed(Component.literal("TradeMaster: player/level unavailable"));
		}
		this.ticks++;
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 100) {
			restoreSlot(player);
			return StepResult.failed(Component.translatable("trademaster.error.button-timeout"));
		}

		// The stored position must actually hold a button; clicking the UP face of
		// whatever else is there could place a block or trigger the build limit.
		BlockState state = mc.level.getBlockState(this.buttonPos);
		if (!(state.getBlock() instanceof ButtonBlock)) {
			restoreSlot(player);
			return StepResult.failed(Component.translatable("trademaster.message.button_missing"));
		}

		if (this.pressed) {
			this.confirmTicks++;
			// POWERED right after our own use packet is a CLIENT PREDICTION (useItemOn
			// applies locally before sending); a press the server rejects is rolled back
			// a round trip later. Require the state to persist ~0.5s so a prediction
			// cannot pass as confirmation, or SUMMON reports DONE and the next phase
			// (AWAIT_VILLAGER, watchdog-exempt) hangs forever.
			if (state.getValue(ButtonBlock.POWERED)) {
				if (this.confirmTicks >= 10) {
					restoreSlot(player);
					return StepResult.DONE;
				}
				return StepResult.RUNNING;
			}
			if (this.confirmTicks > 40) {
				restoreSlot(player);
				return StepResult.failed(Component.translatable("trademaster.message.button_not_pressed"));
			}
			// the use packet may have been dropped, or the button was still
			// powered from a recent manual touch: retry a few times
			if (this.confirmTicks % 10 == 0 && this.presses < 3) {
				this.presses++;
				press(mc, player, state);
			}
			return StepResult.RUNNING;
		}

		// residual power from a previous (manual or pipeline) press: wait for it
		// to decay before pressing, otherwise pressing now is a no-op and the
		// POWERED confirm below could not be attributed to our own press
		if (state.getValue(ButtonBlock.POWERED)) {
			// wooden buttons stay lit ~15 ticks, stone ~10: power lasting longer
			// than this is external (redstone), and pressing can never land —
			// report it directly instead of burning the shared timeout and
			// failing with a misleading reroll-timeout
			if (++this.poweredWaitTicks > 60) {
				restoreSlot(player);
				return StepResult.failed(Component.translatable("trademaster.message.button_still_powered"));
			}
			return StepResult.RUNNING;
		}

		Vec3 center = Vec3.atCenterOf(this.buttonPos);
		if (!this.look.tick(player, center)) return StepResult.RUNNING;

		if (!this.switchedSlot) {
			this.switchedSlot = true;
			this.originalSlot = player.getInventory().getSelectedSlot();
			int empty = findEmptyHotbarSlot(player);
			if (empty >= 0 && empty != this.originalSlot) {
				Interactions.selectHotbarSlot(player, empty);
			// Defensive, unreachable today (audited 2026-09-19): a sneaking player
			// returns earlier in the pipeline, so isShiftKeyDown() is always false.
			} else if (empty < 0 && player.isShiftKeyDown()
					&& player.getInventory().getSelectedItem().getItem() instanceof BlockItem) {
				// only sneak makes the held block win over the button interaction:
				// pressing while sneaking with a placeable would put it on the
				// button's face and wreck the rig layout
				return StepResult.failed(Component.translatable("trademaster.message.presser_no_empty_slot"));
			}
			return StepResult.RUNNING;
		}

		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		if (config.slowMode && this.delay > 0) {
			this.delay--;
			return StepResult.RUNNING;
		}

		press(mc, player, state);
		this.pressed = true;
		this.presses = 1;
		return StepResult.RUNNING;
	}

	private void press(Minecraft mc, LocalPlayer player, BlockState state) {
		Direction face = switch (state.getValue(ButtonBlock.FACE)) {
			case FLOOR -> Direction.UP;
			case CEILING -> Direction.DOWN;
			case WALL -> state.getValue(ButtonBlock.FACING);
		};
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(this.buttonPos), face, this.buttonPos, false);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND, true);
		player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
	}

	private static int findEmptyHotbarSlot(LocalPlayer player) {
		for (int i = 0; i < 9; i++) {
			if (player.getInventory().getItem(i).isEmpty()) return i;
		}
		return -1;
	}

	/** Stop-time cleanup: put the hotbar selection back where it was before the press
	 * started. Dropping the reference alone (a bare {@code presser = null} in stop())
	 * would strand the selection on the temporary empty slot the press parks it in. */
	public void abortCleanly() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) restoreSlot(player);
	}

	private void restoreSlot(LocalPlayer player) {
		if (this.originalSlot >= 0 && player.getInventory().getSelectedSlot() != this.originalSlot) {
			Interactions.selectHotbarSlot(player, this.originalSlot);
		}
		this.originalSlot = -1;
	}
}
