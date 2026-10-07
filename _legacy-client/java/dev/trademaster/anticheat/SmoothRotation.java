package dev.trademaster.anticheat;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Smooth player rotation (ported from LibrarianTradeFinder's RotationTools,
 * GPL-3.0, Greenman999). Call {@link #smoothLookAt} to start a rotation and
 * {@link #renderTick} every frame; {@link #isRotated} flips once finished.
 */
public final class SmoothRotation {

	public static boolean isRotated = false;
	private static float destPitch = 0;
	private static float destYaw = 0;
	private static float startPitch = 0;
	private static float startYaw = 0;
	private static float duration = 0;
	private static float elapsedTime = 0;
	// monotonic clock: wall time can jump backwards on NTP sync and stall the rotation
	private static long prevTimeNanos = 0;
	/** Largest wall-clock step one update may consume (a hitch is spread over the
	 * following updates instead of finishing the turn instantly). */
	private static final float MAX_STEP_SECONDS = 0.05f;

	private SmoothRotation() {}

	public static void smoothLookAt(Vec3 target, float speed) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Vec3 eyes = EntityAnchorArgument.Anchor.EYES.apply(mc.player);
		double dx = target.x - eyes.x;
		double dy = target.y - eyes.y;
		double dz = target.z - eyes.z;
		if (dx == 0 && dy == 0 && dz == 0) {
			// zero vector has no direction: atan2 would aim at an arbitrary
			// -90° yaw; nothing to turn towards, so complete immediately
			isRotated = true;
			duration = 0;
			return;
		}
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		destPitch = Mth.wrapDegrees((float) (-(Mth.atan2(dy, horizontal) * 57.2957763671875)));
		destYaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * 57.2957763671875) - 90.0F);
		startPitch = mc.player.getXRot();
		startYaw = mc.player.getYRot();
		if (destPitch == startPitch && destYaw == startYaw) {
			isRotated = true;
			return;
		}
		duration = speed / 10f;
		elapsedTime = 0;
		prevTimeNanos = System.nanoTime();
		isRotated = false;
	}

	/** Instant look-at for non-legit mode. */
	public static void snapLookAt(Vec3 target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
		isRotated = true;
	}

	public static void reset() {
		isRotated = false;
		duration = 0;
	}

	public static void renderTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || isRotated || duration == 0) return;
		long now = System.nanoTime();
		if (mc.isPaused()) {
			// paused: keep the clock anchored so the first frame after unpause
			// doesn't jump the rotation to completion
			prevTimeNanos = now;
			return;
		}
		// The prevTimeNanos == 0 fallback is effectively unreachable (audited
		// 2026-09-19, unconfirmed): duration is only non-zero after a prior call set
		// prevTimeNanos together with it.
		float delta = (now - (prevTimeNanos == 0 ? now : prevTimeNanos)) / 1_000_000_000f;
		prevTimeNanos = now;
		// clamp: a main-thread hitch (GC, Android stall) must not consume the whole
		// rotation in one step — that turned the "smooth" turn into an instant snap
		// exactly when the client was visibly stuttering
		delta = Math.min(delta, MAX_STEP_SECONDS);
		elapsedTime += delta;
		float progress = elapsedTime / duration;
		if (progress >= 1) {
			mc.player.setXRot(destPitch);
			mc.player.setYRot(destYaw);
			isRotated = true;
			duration = 0;
			return;
		}
		mc.player.setXRot(Mth.lerp(progress, startPitch, destPitch));
		// take the short way around instead of sweeping across the ±180° seam
		float deltaYaw = Mth.wrapDegrees(destYaw - startYaw);
		mc.player.setYRot(Mth.wrapDegrees(startYaw + deltaYaw * progress));
	}
}
