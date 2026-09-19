package dev.trademaster.anticheat;

import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * "Stop when the player moves" guard. Own teleports issued by the tpToVillager
 * option are exempted through {@link #permitTp} so they never trigger a stop.
 */
public class MovementGuard {

	private static final double TOLERANCE_SQR = 0.01;
	/** Close enough to the tp target to count as "the teleport landed". */
	private static final double TP_LANDED_TOLERANCE_SQR = 0.25;
	/** Ceiling for the exemption window; it normally ends early the moment the
	 * server's position sync lands the player at the tp target. */
	private static final int TP_EXEMPT_TICKS = 30;

	private @Nullable Vec3 baseline = null;
	private @Nullable Vec3 tpTarget = null;
	private int exemptTicks = 0;

	public void start(Vec3 position) {
		this.baseline = position;
		this.tpTarget = null;
		this.exemptTicks = 0;
	}

	/** Called right before/after we teleport the player ourselves. */
	public void permitTp(Vec3 target) {
		this.baseline = target;
		this.tpTarget = target;
		this.exemptTicks = TP_EXEMPT_TICKS;
	}

	public boolean hasMoved(LocalPlayer player) {
		boolean exempt = this.exemptTicks > 0;
		if (exempt) {
			this.exemptTicks--;
			this.baseline = player.position();
			// the teleport "lands" when the server syncs the position: resume
			// guarding immediately instead of staying blind for the whole window,
			// otherwise chained tps (villager -> drop -> back) keep the guard
			// disabled for an entire reroll round
			if (this.tpTarget != null
					&& player.position().distanceToSqr(this.tpTarget) < TP_LANDED_TOLERANCE_SQR) {
				this.exemptTicks = 0;
				this.tpTarget = null;
			}
		}
		if (!TradeMasterConfig.INSTANCE.stopOnPlayerMove) return false;
		if (exempt) return false;
		// Defensive, unreachable today (audited 2026-09-19): start() runs before any
		// hasMoved() call of a session and always sets a non-null baseline.
		if (this.baseline == null) {
			this.baseline = player.position();
			return false;
		}
		return player.position().distanceToSqr(this.baseline) > TOLERANCE_SQR;
	}
}
