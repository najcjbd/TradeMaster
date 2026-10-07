package dev.trademaster.anticheat;

import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.Vec3;

/**
 * Reusable legit-rotation handshake: in legit+slow mode waits for the
 * {@link SmoothRotation} to finish, in legit-only mode snaps instantly,
 * otherwise does nothing. Stateful per aim attempt; {@link #reset} between
 * distinct targets.
 */
public final class LookStep {

	private static final float LOOK_SPEED = 3;

	private boolean started = false;
	private boolean finished = false;

	/** @return true once the aim is established and the caller may act. */
	public boolean tick(LocalPlayer player, Vec3 target) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		if (config.legitMode && config.slowMode) {
			// start our own rotation first: checking isRotated before that could
			// consume a stale completion flag and skip the turn entirely
			if (!this.started && !this.finished) {
				SmoothRotation.smoothLookAt(target, LOOK_SPEED);
				this.started = true;
				return false;
			}
			if (SmoothRotation.isRotated && !this.finished) {
				this.finished = true;
				this.started = false;
				SmoothRotation.isRotated = false;
			} else if (!this.finished) {
				return false;
			}
		} else if (config.legitMode) {
			player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
		}
		return true;
	}

	/** Note: {@code finished} is deliberately sticky — the caller MUST call this when it
	 * changes the target, or the new aim is skipped. Comparing targets here instead was
	 * tried and rejected: a target that drifts (a wandering villager's eye position) would
	 * restart the smooth rotation every tick and stall the calling phase. */
	public void reset() {
		this.started = false;
		this.finished = false;
		// a rotation started here can complete while this LookStep is no longer
		// ticked; the dangling flag would let the next LookStep skip its own turn
		SmoothRotation.reset();
	}
}
