package dev.trademaster.strategy;

import dev.trademaster.anticheat.LookStep;
import dev.trademaster.anticheat.SmoothRotation;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RunSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Axe break / offhand place reroll (ported from LibrarianTradeFinder,
 * GPL-3.0, Greenman999). Keeps all of LTF's anti-cheat behavior: legit
 * rotation, slow-mode delays, axe durability protection, optional teleport.
 */
public class AxeRerollStrategy implements RerollStrategy {

	private static final int PHASE_TIMEOUT_TICKS = 200;

	private enum Phase { BREAK, PICKUP, PLACE }

	private final LookStep look = new LookStep();
	private @Nullable RunSession session;
	private Phase phase = Phase.BREAK;
	private int phaseTicks = 0;
	private int delayCounter = 0;
	private @Nullable Vec3 tpReturnPos = null;
	private @Nullable ItemEntity lastPickupTarget = null;
	private boolean seenDrop = false;
	private int placeAttempts = 0;
	private int placeCooldown = 0;
	private boolean placementBlocked = false;

	@Override
	public void begin(RunSession session) {
		this.session = session;
		this.phase = Phase.BREAK;
		this.phaseTicks = 0;
		this.delayCounter = 0;
		this.look.reset();
		this.tpReturnPos = null;
		this.lastPickupTarget = null;
		this.seenDrop = false;
		this.placeAttempts = 0;
		this.placeCooldown = 0;
		this.placementBlocked = false;
	}

	@Override
	public boolean placementBlocked() {
		return this.placementBlocked;
	}

	@Override
	public StepResult tick() {
		Minecraft mc = Minecraft.getInstance();
		RunSession session = this.session;
		if (mc.player == null || mc.level == null || mc.gameMode == null || session == null) {
			return StepResult.failed(Component.literal("TradeMaster: player/level unavailable"));
		}
		this.phaseTicks++;
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.phaseTicks > PHASE_TIMEOUT_TICKS) {
			if (this.phase == Phase.PLACE) this.placementBlocked = true;
			return StepResult.failed(Component.translatable("trademaster.error.reroll-timeout"));
		}
		return switch (this.phase) {
			case BREAK -> tickBreak(mc, session);
			case PICKUP -> tickPickup(mc, session);
			case PLACE -> tickPlace(mc, session);
		};
	}

	private StepResult tickBreak(Minecraft mc, RunSession session) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		LocalPlayer player = mc.player;
		BlockPos lecternPos = session.lecternPos;
		Vec3 aimAt = Vec3.atCenterOf(lecternPos.below());

		if (!this.look.tick(player, aimAt)) return StepResult.RUNNING;

		// Never break empty-handed: put an axe into the main hand first.
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

		if (LecternRestock.isLecternAt(mc.level, lecternPos)) {
			LecternRestock.breakBlock(mc, player, lecternPos);
			return StepResult.RUNNING;
		}

		// Lectern is gone: optional teleport to the villager, then place. Never
		// tp to a dead or departing villager (falling down the send-away chute),
		// otherwise the player ends up displaced mid-void.
		if (config.tpToVillager && mc.getConnection() != null && session.villager != null
				&& session.villager.isAlive()
				&& session.villager.position().distanceTo(Vec3.atCenterOf(lecternPos)) <= 2) {
			this.tpReturnPos = player.position();
			session.movementGuard.permitTp(new Vec3(session.villager.getX(), session.villager.getY(), session.villager.getZ()));
			mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
					session.villager.getX(), session.villager.getY(), session.villager.getZ(), true, false));
		}
		if (config.tpToItem) {
			this.phase = Phase.PICKUP;
		} else {
			this.phase = Phase.PLACE;
			this.look.reset();
			this.delayCounter = config.placeDelay;
		}
		this.phaseTicks = 0;
		return StepResult.RUNNING;
	}

	/**
	 * Collect the broken lectern's item drop(s): teleport to the lectern drop
	 * nearest to the player among those within 3 blocks of the lectern spot,
	 * one at a time, until none are left — otherwise the drop can be lost.
	 */
	private StepResult tickPickup(Minecraft mc, RunSession session) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		LocalPlayer player = mc.player;
		BlockPos lecternPos = session.lecternPos;
		Vec3 center = Vec3.atCenterOf(lecternPos);

		List<ItemEntity> drops = mc.level.getEntitiesOfClass(ItemEntity.class,
				AABB.ofSize(center, 7, 7, 7),
				e -> e.getItem().is(Items.LECTERN)
						&& e.position().distanceTo(center) <= 3);
		if (!drops.isEmpty()) this.seenDrop = true;
		// an unpickable drop (clipped into a block) must not stall or fail the
		// run: give up after 3s and continue placing. An empty read right after
		// the break is a race (the drop-entity packet trails block removal), so
		// only treat empty as done once a drop was seen or ~1s grace elapsed
		if ((drops.isEmpty() && (this.seenDrop || this.phaseTicks > 20))
				|| this.phaseTicks > 60) {
			this.phase = Phase.PLACE;
			this.phaseTicks = 0;
			this.look.reset();
			this.delayCounter = config.placeDelay;
			return StepResult.RUNNING;
		}

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
		// fresh drops have a short pickup delay; standing on them is enough, the
		// phase timeout (or no remaining drops) ends the loop
		return StepResult.RUNNING;
	}

	private StepResult tickPlace(Minecraft mc, RunSession session) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		LocalPlayer player = mc.player;
		BlockPos lecternPos = session.lecternPos;

		if (lecternPos.getY() >= mc.level.getMaxY()) {
			return StepResult.failed(Component.translatable("trademaster.message.out_of_bounds"));
		}

		// Teleport back immediately, like LTF does at the start of PLACE.
		if (this.tpReturnPos != null && mc.getConnection() != null) {
			session.movementGuard.permitTp(this.tpReturnPos);
			mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
					this.tpReturnPos.x, this.tpReturnPos.y, this.tpReturnPos.z, true, false));
			this.tpReturnPos = null;
		}

		// Done only when the lectern is verified present — a blind click can be
		// rejected by the server while the client happily predicts success.
		if (LecternRestock.isLecternAt(mc.level, lecternPos)) {
			return StepResult.DONE;
		}

		Vec3 aimAt = Vec3.atCenterOf(lecternPos.below());
		if (!this.look.tick(player, aimAt)) return StepResult.RUNNING;

		// the villager often leans into the lectern block; the server rejects
		// placements that collide with an entity, so wait for the spot to clear
		// instead of burning all place attempts on it (noCollision alone is
		// blind to villagers — lecternSpotClear also scans living entities)
		if (!LecternRestock.lecternSpotClear(mc.level, lecternPos, player)) return StepResult.RUNNING;

		if (config.slowMode && this.delayCounter > 0) {
			this.delayCounter--;
			return StepResult.RUNNING;
		}

		// Click only with a lectern really in the offhand: restock() sends raw
		// packets that take effect next tick, so clicking immediately would be an
		// empty-hand click that places nothing.
		if (!LecternRestock.offhandHasLectern(player)) {
			if (!LecternRestock.restock(player)) {
				return StepResult.failed(Component.translatable("trademaster.message.lectern_lost"));
			}
			return StepResult.RUNNING;
		}

		// Space the attempts out: a rejected placement needs a tick or two for the
		// server correction to arrive, spamming predictions causes ghost blocks.
		if (this.placeCooldown > 0) {
			this.placeCooldown--;
			return StepResult.RUNNING;
		}
		if (this.placeAttempts >= LecternRestock.MAX_PLACE_ATTEMPTS) {
			// most often the villager keeps occupying the spot: recoverable by
			// dispatching it, like the guardian ENSURE path does
			this.placementBlocked = true;
			return StepResult.failed(Component.translatable("trademaster.message.place_failed"));
		}

		LecternRestock.placeLectern(mc, player, lecternPos);
		this.placeAttempts++;
		this.placeCooldown = LecternRestock.PLACE_COOLDOWN_TICKS;
		return StepResult.RUNNING;
	}

	@Override
	public void abort() {
		// interrupted mid-teleport (BREAK tp'd to the villager, PICKUP tp'd to a
		// drop): bring the player back to the rig, otherwise they are stranded at
		// the last teleport target — the normal path only tp's back in tickPlace
		if (this.tpReturnPos != null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getConnection() != null && this.session != null) {
				this.session.movementGuard.permitTp(this.tpReturnPos);
				mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
						this.tpReturnPos.x, this.tpReturnPos.y, this.tpReturnPos.z, true, false));
			}
			this.tpReturnPos = null;
		}
		this.session = null;
		this.look.reset();
		SmoothRotation.reset();
	}
}
