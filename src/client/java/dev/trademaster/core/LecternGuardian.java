package dev.trademaster.core;

import dev.trademaster.anticheat.LookStep;
import dev.trademaster.anticheat.SmoothRotation;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.strategy.LecternRestock;
import dev.trademaster.strategy.RerollStrategy.StepResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Watches the configured lectern position and drives it toward the state the
 * current pipeline step needs. Three modes:
 * <ul>
 *   <li>{@link Mode#ENSURE}: a lectern must be present; place it from the
 *       offhand when missing (LTF-style restock). Used so the rig never stays
 *       broken right before a trade check.</li>
 *   <li>{@link Mode#REFRESH}: break the lectern once, then re-place it. Used to
 *       drop a stale profession claim and force the villager to re-bind.</li>
 *   <li>{@link Mode#BREAK_ONLY}: break the lectern and stop, leaving the spot
 *       empty. Used right after a locked villager is sent away: the lectern is
 *       broken to release its claim, but NOT re-placed until the next villager
 *       actually arrives, so no passing/still-falling villager can grab it.</li>
 * </ul>
 */
public class LecternGuardian {

	public enum Mode { ENSURE, REFRESH, BREAK_ONLY }

	private final Mode mode;
	private final LookStep look = new LookStep();
	private int ticks = 0;
	private int delay;
	private boolean brokenOnce = false;
	private boolean announcedBreak = false;
	private boolean announcedClearWait = false;
	private int placeCooldown = 0;
	private int placeAttempts = 0;
	private boolean placementBlocked = false;
	private Vec3 tpReturnPos = null;
	private ItemEntity lastPickupTarget = null;
	private int pickupTicks = 0;
	private boolean seenDrop = false;
	private RunSession session = null;

	public LecternGuardian() {
		this(Mode.ENSURE);
	}

	private LecternGuardian(Mode mode) {
		this.mode = mode;
		this.delay = TradeMasterConfig.INSTANCE.placeDelay;
		RerollController.debug("lectern guardian started, mode=" + mode);
	}

	public static LecternGuardian refresh() {
		return new LecternGuardian(Mode.REFRESH);
	}

	public static LecternGuardian breakOnly() {
		return new LecternGuardian(Mode.BREAK_ONLY);
	}

	public StepResult tick(RunSession session) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			return StepResult.failed(Component.literal("TradeMaster: player/level unavailable"));
		}
		this.session = session;
		BlockPos lecternPos = session.lecternPos;
		boolean present = LecternRestock.isLecternAt(mc.level, lecternPos);

		// Terminal states for each mode.
		if (present && this.mode == Mode.ENSURE) return StepResult.DONE;
		if (present && this.mode == Mode.REFRESH && this.brokenOnce) {
			// the pickup trip may have displaced the player, and the arrival point is
			// defined relative to the PLAYER: send the return teleport first, or
			// CHECK_TRADE reads the villager as having walked off the point
			if (this.tpReturnPos != null && mc.getConnection() != null) {
				session.movementGuard.permitTp(this.tpReturnPos);
				mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
						this.tpReturnPos.x, this.tpReturnPos.y, this.tpReturnPos.z, true, false));
				this.tpReturnPos = null;
			}
			return StepResult.DONE;
		}
		if (!present && this.mode == Mode.BREAK_ONLY) return StepResult.DONE;

		this.ticks++;
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 20 * 20) {
			if (!present && this.mode != Mode.BREAK_ONLY) this.placementBlocked = true;
			return StepResult.failed(Component.translatable("trademaster.error.reroll-timeout"));
		}

		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		Vec3 aimAt = new Vec3(lecternPos.getX() + 0.5, lecternPos.getY(), lecternPos.getZ() + 0.5);
		if (!this.look.tick(player, aimAt)) return StepResult.RUNNING;

		if (present) {
			// REFRESH / BREAK_ONLY: break the lectern to drop the previous claim.
			switch (LecternRestock.bringAxeToHand(mc, player)) {
				case NONE -> {
					return StepResult.failed(Component.translatable("trademaster.message.axe_required"));
				}
				case WORKING -> {
					return StepResult.RUNNING;
				}
				case READY -> {}
			}
			if (config.preventAxeBreaking && LecternRestock.axeNearlyBroken(player)) {
				return StepResult.failed(Component.translatable("trademaster.message.axe_breaking"));
			}
			if (!this.announcedBreak) {
				this.announcedBreak = true;
				RerollController.debug("lectern guardian: breaking lectern at " + lecternPos.toShortString());
			}
			LecternRestock.breakBlock(mc, player, lecternPos);
			return StepResult.RUNNING;
		}

		// Not present: BREAK_ONLY already returned DONE above, so from here on we
		// are placing (ENSURE, or the re-place half of REFRESH).
		this.brokenOnce = true;
		if (lecternPos.getY() >= mc.level.getMaxY()) {
			return StepResult.failed(Component.translatable("trademaster.message.out_of_bounds"));
		}

		// REFRESH broke the lectern: collect the drop before re-placing, otherwise
		// it stays on the ground and restock eventually burns the inventory stock.
		if (this.mode == Mode.REFRESH && tickPickupDrops(mc, session, player, lecternPos)) {
			return StepResult.RUNNING;
		}

		// Back from the pickup trip before any placement attempt.
		if (this.tpReturnPos != null && mc.getConnection() != null) {
			session.movementGuard.permitTp(this.tpReturnPos);
			mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
					this.tpReturnPos.x, this.tpReturnPos.y, this.tpReturnPos.z, true, false));
			this.tpReturnPos = null;
		}

		// The arriving villager is often still being pushed through the lectern
		// block; the server rejects placements that collide with an entity, so
		// wait for the spot to clear instead of burning place attempts on it
		// (noCollision alone is blind to villagers — lecternSpotClear also
		// scans living entities).
		if (!LecternRestock.lecternSpotClear(mc.level, lecternPos, player)) {
			if (!this.announcedClearWait) {
				this.announcedClearWait = true;
				RerollController.debug("lectern guardian: waiting for the lectern spot to clear");
			}
			return StepResult.RUNNING;
		}

		if (config.slowMode && this.delay > 0) {
			this.delay--;
			return StepResult.RUNNING;
		}

		if (this.placeCooldown > 0) {
			this.placeCooldown--;
			return StepResult.RUNNING;
		}

		if (!LecternRestock.offhandHasLectern(player)) {
			if (!LecternRestock.restock(player)) {
				return StepResult.failed(Component.translatable("trademaster.message.lectern_lost"));
			}
			// restock clicks only take effect on the server next tick
			return StepResult.RUNNING;
		}

		if (this.placeAttempts >= LecternRestock.MAX_PLACE_ATTEMPTS) {
			this.placementBlocked = true;
			return StepResult.failed(Component.translatable("trademaster.message.place_failed"));
		}

		LecternRestock.placeLectern(mc, player, lecternPos);
		this.placeAttempts++;
		RerollController.debug("lectern guardian: placing lectern, attempt " + this.placeAttempts);
		this.placeCooldown = LecternRestock.PLACE_COOLDOWN_TICKS;
		return StepResult.RUNNING;
	}

	/**
	 * Collect the broken lectern's item drop(s): teleport to the drop nearest
	 * to the player among those within 3 blocks of the lectern spot, one at a
	 * time. Returns true while pickup is still in progress; an unpickable drop
	 * (clipped into a block) must not stall the run, so give up after 60 ticks.
	 */
	private boolean tickPickupDrops(Minecraft mc, RunSession session, LocalPlayer player, BlockPos lecternPos) {
		Vec3 center = Vec3.atCenterOf(lecternPos);
		List<ItemEntity> drops = mc.level.getEntitiesOfClass(ItemEntity.class,
				AABB.ofSize(center, 7, 7, 7),
				e -> e.getItem().is(Items.LECTERN)
						&& e.position().distanceTo(center) <= 3);
		this.pickupTicks++;
		if (!drops.isEmpty()) this.seenDrop = true;
		// the drop-entity packet trails the client-side block removal by up to a
		// round trip, so an empty read is only "collected" once a drop was seen
		// (or ~1s grace elapsed); an unpickable drop must not stall the run
		if ((drops.isEmpty() && (this.seenDrop || this.pickupTicks > 20))
				|| this.pickupTicks > 60) {
			this.pickupTicks = 0;
			this.seenDrop = false;
			return false;
		}
		// tpToItem off: wait for the drops to vanish naturally (hopper, water
		// stream, nearby player pickup) instead of teleporting to them
		if (!TradeMasterConfig.INSTANCE.tpToItem) return true;
		ItemEntity nearest = null;
		double best = Double.MAX_VALUE;
		for (ItemEntity drop : drops) {
			double d = drop.position().distanceToSqr(player.position());
			if (d < best) {
				best = d;
				nearest = drop;
			}
		}
		if (mc.getConnection() != null && nearest != null && nearest != this.lastPickupTarget) {
			if (this.tpReturnPos == null) this.tpReturnPos = player.position();
			this.lastPickupTarget = nearest;
			Vec3 target = nearest.position();
			session.movementGuard.permitTp(target);
			mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
					target.x, target.y, target.z, true, false));
		}
		return true;
	}

	/** True when the failure happened on the placement side (recoverable by swapping villagers). */
	public boolean placementBlocked() {
		return this.placementBlocked;
	}

	public void abort() {
		// interrupted mid pickup-trip: bring the player back to the rig, otherwise
		// they are stranded at the last teleport target
		if (this.tpReturnPos != null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getConnection() != null && this.session != null) {
				this.session.movementGuard.permitTp(this.tpReturnPos);
				mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
						this.tpReturnPos.x, this.tpReturnPos.y, this.tpReturnPos.z, true, false));
			}
			this.tpReturnPos = null;
		}
		this.look.reset();
		SmoothRotation.reset();
	}
}
