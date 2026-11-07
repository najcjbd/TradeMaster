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
	/** A rotation was applied on an EARLIER tick, so the server has it by now. The
	 * rotation packet is sent during the player tick, while this runs after it: acting
	 * in the same tick would send the interact with the pre-turn view. */
	private boolean snapped = false;

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
			if (!this.finished) {
				if (SmoothRotation.isRotated) {
					this.finished = true;
					this.started = false;
					SmoothRotation.isRotated = false;
				} else {
					return false;
				}
			}
			// the final rotation reaches the server with the NEXT player tick
			if (!this.snapped) {
				this.snapped = true;
				return false;
			}
			return true;
		} else if (config.legitMode) {
			// re-snap every tick so a drifting target stays tracked, but only act on a
			// later tick: the packet for the snap goes out during the next player tick
			player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
			boolean ready = this.snapped;
			this.snapped = true;
			return ready;
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
		this.snapped = false;
		// a rotation started here can complete while this LookStep is no longer
		// ticked; the dangling flag would let the next LookStep skip its own turn
		SmoothRotation.reset();
	}
}
