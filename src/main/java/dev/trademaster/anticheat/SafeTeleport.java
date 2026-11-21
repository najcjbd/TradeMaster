package dev.trademaster.anticheat;

import dev.trademaster.compat.McPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-friendly replacement for the single-packet teleport. It walks the CLIENT and
 * the SERVER together in small steps (one packet per tick), so the server sees a normal
 * player movement instead of a multi-block jump and the client never sends a stale
 * position that snaps the server back.
 *
 * <p>The caller starts a teleport and returns RUNNING; {@link #tick()} is driven from
 * the controller once per tick, and the phase action continues only after arrival.
 * The target is snapped to the player's current floor and the whole straight path is
 * validated before moving; if it is blocked the start returns false and the caller
 * falls back to not teleporting (the server must never see a raw jump in safe mode).
 */
public final class SafeTeleport {

	private static final double MAX_STEP = 0.25;

	private static @Nullable SafeTeleport active = null;

	private final LocalPlayer player;
	private final MovementGuard guard;
	private final List<Vec3> path;
	private final @Nullable Runnable after;
	private int index = 1;

	private SafeTeleport(LocalPlayer player, MovementGuard guard, List<Vec3> path,
			@Nullable Runnable after) {
		this.player = player;
		this.guard = guard;
		this.path = path;
		this.after = after;
	}

	public static boolean isActive() {
		return active != null;
	}

	/** Starts a safe teleport; false means the caller should skip this teleport. */
	public static boolean start(Vec3 target, MovementGuard guard, @Nullable Runnable after) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.getConnection() == null || active != null) {
			return false;
		}
		Vec3 to = standPos(mc, player, target);
		if (to == null) {
			dev.trademaster.core.RerollController.debug("safe-tp: no stand position for " + target);
			return false;
		}
		Vec3 from = player.position();
		if (from.distanceTo(to) < 0.01) return false;
		// A jump-arc path: rise clear of the rig step/blocks, move at that clearance,
		// then drop onto the target. Every point is collision-checked; the start and
		// final points additionally require a floor. This is normal jump/fall motion
		// for the server, not a multi-block jump.
		double clearance = Math.max(from.y, to.y) + 1.0;
		List<Vec3> path = new ArrayList<>();
		path.add(from);
		addSegment(path, from, new Vec3(from.x, clearance, from.z));
		addSegment(path, new Vec3(from.x, clearance, from.z),
				new Vec3(to.x, clearance, to.z));
		addSegment(path, new Vec3(to.x, clearance, to.z), to);
		for (int i = 1; i < path.size(); i++) {
			Vec3 next = path.get(i);
			if (!canPass(mc, player, next, i == path.size() - 1)) {
				dev.trademaster.core.RerollController.debug(
						"safe-tp: blocked at step " + i + "/" + (path.size() - 1) + " " + next);
				return false;
			}
		}
		active = new SafeTeleport(player, guard, path, after);
		dev.trademaster.core.RerollController.debug(
				"safe-tp: " + from + " -> " + to + " in " + (path.size() - 1) + " steps");
		return true;
	}

	/** Advances one tick; true once the teleport has arrived (and ran its continuation). */
	public static boolean tick() {
		SafeTeleport teleport = active;
		if (teleport == null) return true;
		Vec3 next = teleport.path.get(teleport.index);
		boolean landed = teleport.index == teleport.path.size() - 1;
		teleport.player.setPos(next.x, next.y, next.z);
		teleport.player.setDeltaMovement(Vec3.ZERO);
		teleport.player.fallDistance = 0.0f;
		teleport.player.setOnGround(landed);
		teleport.guard.permitTp(next);
		sendPos(next, landed);
		teleport.index++;
		if (teleport.index >= teleport.path.size()) {
			active = null;
			dev.trademaster.core.RerollController.debug("safe-tp: arrived " + next);
			if (teleport.after != null) teleport.after.run();
			return true;
		}
		return false;
	}

	private static void sendPos(Vec3 pos, boolean onGround) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null) {
			McPackets.sendMovePlayerPos(mc.getConnection(), pos.x, pos.y, pos.z, onGround, false);
		}
	}

	/** Drops an in-flight safe teleport without moving further (stop/abort). */
	public static void abort() {
		if (active != null) {
			dev.trademaster.core.RerollController.debug("safe-tp: aborted at " + active.player.position());
		}
		active = null;
	}

	/** A standable position at the target x/z, preferring the player's current floor. */
	private static @Nullable Vec3 standPos(Minecraft mc, LocalPlayer player, Vec3 desired) {
		// A drop can sit over the void next to a platform (the rig's gap): the exact
		// item x/z has no floor, but the platform edge within pickup reach does. Search
		// the nearest standable spot around the desired point (<=1.5 blocks).
		double[] ys = {player.getY(), desired.y, desired.y + 0.5, desired.y + 1.0,
				desired.y - 0.5};
		Vec3 best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int ring = 0; ring <= 3; ring++) {
			double radius = ring * 0.5;
			for (int dx = -ring; dx <= ring; dx++) {
				for (int dz = -ring; dz <= ring; dz++) {
					if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
					double x = desired.x + dx * 0.5;
					double z = desired.z + dz * 0.5;
					for (double y : ys) {
						Vec3 candidate = new Vec3(x, y, z);
						double distance = candidate.distanceTo(desired);
						if (distance > 1.5 || distance >= bestDistance) continue;
						if (canStand(mc, player, candidate)) {
							best = candidate;
							bestDistance = distance;
						}
					}
				}
			}
		}
		return best;
	}

	private static void addSegment(List<Vec3> path, Vec3 from, Vec3 to) {
		double distance = from.distanceTo(to);
		if (distance < 1.0E-4) return;
		int steps = Math.max(1, (int) Math.ceil(distance / MAX_STEP));
		for (int i = 1; i <= steps; i++) {
			path.add(from.lerp(to, i / (double) steps));
		}
	}

	private static boolean canPass(Minecraft mc, LocalPlayer player, Vec3 position,
			boolean requireFloor) {
		if (mc.level == null) return false;
		AABB box = player.getBoundingBox().move(
				position.x - player.getX(), position.y - player.getY(), position.z - player.getZ());
		if (!mc.level.noCollision(box)) return false;
		return !requireFloor || hasFloor(mc, position);
	}

	private static boolean hasFloor(Minecraft mc, Vec3 position) {
		for (double drop = 0.05; drop <= 0.65; drop += 0.2) {
			BlockPos below = new BlockPos((int) Math.floor(position.x),
					(int) Math.floor(position.y - drop), (int) Math.floor(position.z));
			//? if >=1.20 {
			if (mc.level.getBlockState(below).isSolid()) return true;
			//?} else {
			/*if (mc.level.getBlockState(below).getMaterial().isSolid()) return true;*/
			//?}
		}
		return false;
	}

	private static boolean canStand(Minecraft mc, LocalPlayer player, Vec3 position) {
		return canPass(mc, player, position, true);
	}
}
