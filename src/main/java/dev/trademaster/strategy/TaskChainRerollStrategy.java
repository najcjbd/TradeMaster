package dev.trademaster.strategy;

import dev.trademaster.anticheat.LookStep;
import dev.trademaster.anticheat.SmoothRotation;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RunSession;
import dev.trademaster.ingame.StepRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.item.ItemEntity;
import dev.trademaster.compat.McVillager;
import dev.trademaster.compat.McLevel;
import dev.trademaster.compat.McPackets;
import dev.trademaster.compat.McText;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Task-chain reroll (ported from LPCTools tradeReroller, LGPLv3, Fymasa):
 * break the lectern, wait for the villager to lose its profession, place
 * the lectern back, wait for it to become a librarian again. Implemented on
 * top of a small {@link StepRunner} instead of LPCTools' InGameFuture stack.
 */
public class TaskChainRerollStrategy implements RerollStrategy {

	private StepRunner runner = new StepRunner();
	private RunSession session;
	private PickupLecternStep pickupStep;
	private boolean placementBlocked = false;

	@Override
	public void begin(RunSession session) {
		this.session = session;
		this.placementBlocked = false;
		// defensive parity with AxeRerollStrategy.begin: a stale in-flight rotation
		// from an aborted run must not fight the new chain's LookSteps
		SmoothRotation.reset();
		this.pickupStep = new PickupLecternStep();
		// the CONFIGURED profession (RigTarget defaults a blank/garbage id to the librarian)
		ResourceKey<VillagerProfession> wanted = RigTarget.professionKey();
		// NOTE: an unemployed ("none") target is refused by the Villagers tab and warned about
		// at start, so it is only reachable by hand-editing. The final wait is kept for it: an
		// unemployed villager claims the work block the moment it is placed, so waiting for
		// "none" here can never end and the 400t timeout stops the run with a clear reason —
		// better than skipping the wait and looping through the WAIT_PROFESSION self-heal.
		this.runner = new StepRunner()
				.add(new BreakLecternStep())
				.add(this.pickupStep)
				.add(new WaitProfessionStep(McVillager.none(), "trademaster.error.wait-none-timeout"))
				.add(new PlaceLecternStep())
				.add(new WaitProfessionStep(wanted, wanted.equals(McVillager.none())
						? "trademaster.error.wait-none-timeout"
						: "trademaster.error.wait-librarian-timeout"));
	}

	@Override
	public StepResult tick() {
		if (this.session == null) return StepResult.failed(McText.literal("TradeMaster: session not started"));
		return this.runner.tick(this.session);
	}

	@Override
	public void abort() {
		this.runner.abort();
		if (this.pickupStep != null) this.pickupStep.tpBack(this.session);
		this.session = null;
		SmoothRotation.reset();
	}

	@Override
	public boolean placementBlocked() {
		return this.placementBlocked;
	}

	private static Vec3 lecternAim(BlockPos lecternPos) {
		return new Vec3(lecternPos.getX() + 0.5, lecternPos.getY(), lecternPos.getZ() + 0.5);
	}

	private abstract class AbstractLookStep implements StepRunner.Step {
		final LookStep look = new LookStep();

		boolean ensureLook(LocalPlayer player, Vec3 target) {
			return this.look.tick(player, target);
		}
	}

	private class BreakLecternStep extends AbstractLookStep {
		int ticks = 0;
		/** slow-mode pacing between repeated break ticks (mirrors the place gate). */
		int breakDelay = 0;
		/** Server-confirmation gate for the break (fresh per reroll: the chain is rebuilt
		 * in begin()). */
		final LecternRestock.BreakGate breakGate = new LecternRestock.BreakGate();

		@Override
		public StepResult tick(RunSession session) {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (player == null || mc.level == null || mc.gameMode == null) {
				return StepResult.failed(McText.literal("TradeMaster: player/level unavailable"));
			}
			this.ticks++;
			if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 200) return StepResult.failed(McText.translatable("trademaster.error.reroll-timeout"));

			BlockPos lecternPos = session.lecternPos;
			// update the break gate EVERY tick (a present reading resets it); only a
			// server-confirmed break may advance, and while it is client-absent but
			// unconfirmed we must not keep swinging at air
			if (this.breakGate.observe(mc.level, lecternPos)) return StepResult.DONE;
			if (!LecternRestock.isWorkBlockAt(mc.level, lecternPos)) return StepResult.RUNNING;

			if (!ensureLook(player, lecternAim(lecternPos))) return StepResult.RUNNING;

			// Never break empty-handed: put an axe into the main hand first.
			switch (LecternRestock.bringToolToHand(mc, player, RigTarget.stateAt(mc.level, lecternPos))) {
				case NONE -> {
					return StepResult.failed(McText.translatable(RigTarget.toolRequiredKey(RigTarget.stateAt(mc.level, lecternPos), player)));
				}
				case WORKING -> {
					return StepResult.RUNNING;
				}
				case READY -> {}
			}
			if (TradeMasterConfig.INSTANCE.preventAxeBreaking && LecternRestock.toolNearlyBroken(player)) {
				return StepResult.failed(McText.translatable(RigTarget.toolBreakingKey(RigTarget.stateAt(mc.level, lecternPos), player)));
			}

			if (TradeMasterConfig.INSTANCE.slowMode) {
				if (this.breakDelay > 0) {
					this.breakDelay--;
					return StepResult.RUNNING;
				}
				this.breakDelay = TradeMasterConfig.INSTANCE.interactDelay;
			}
			LecternRestock.breakBlock(mc, player, lecternPos);
			return StepResult.RUNNING;
		}
	}

	/**
	 * Collect the broken lectern's item drop before re-placing: without this,
	 * every reroll leaves one lectern on the ground and burns one from the
	 * inventory until restock fails with lectern_lost. With tpToItem on, teleport
	 * to the drop (AxeRerollStrategy style); otherwise just give the natural
	 * pickup a short window, since the player stands right next to the rig.
	 */
	private class PickupLecternStep implements StepRunner.Step {
		int ticks = 0;
		boolean seenDrop = false;
		Vec3 tpReturnPos = null;
		ItemEntity lastPickupTarget = null;

		@Override
		public StepResult tick(RunSession session) {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (player == null || mc.level == null) return StepResult.DONE;
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			Vec3 center = Vec3.atCenterOf(session.lecternPos);
			List<ItemEntity> drops = mc.level.getEntitiesOfClass(ItemEntity.class,
					AABB.ofSize(center, 7, 7, 7),
					e -> RigTarget.workItem() != null && e.getItem().is(RigTarget.workItem())
							&& e.position().distanceTo(center) <= 3);
			this.ticks++;
			if (!drops.isEmpty()) this.seenDrop = true;
			// an unpickable drop (clipped into a block) must not stall the run;
			// also the drop-entity packet trails the client-side block removal by
			// up to a round trip, so an empty read is only "picked up" once a drop
			// was actually seen — otherwise wait ~1s for it to appear
			int limit = config.tpToItem ? 60 : 25;
			if (this.ticks > limit
					|| (drops.isEmpty() && (this.seenDrop || this.ticks > 20))) {
				tpBack(session);
				return StepResult.DONE;
			}
			if (!config.tpToItem) return StepResult.RUNNING;
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
				McPackets.sendMovePlayerPos(mc.getConnection(), target.x, target.y, target.z, true, false);
			}
			return StepResult.RUNNING;
		}

		/** Teleport back to the rig after (or when interrupted during) the pickup trip. */
		void tpBack(RunSession session) {
			if (this.tpReturnPos == null) return;
			Minecraft mc = Minecraft.getInstance();
			if (session != null && mc.getConnection() != null) {
				session.movementGuard.permitTp(this.tpReturnPos);
				McPackets.sendMovePlayerPos(mc.getConnection(), this.tpReturnPos.x, this.tpReturnPos.y, this.tpReturnPos.z, true, false);
			}
			this.tpReturnPos = null;
		}
	}

	private static class WaitProfessionStep implements StepRunner.Step {
		final ResourceKey<VillagerProfession> wanted;
		final String timeoutKey;
		int ticks = 0;

		WaitProfessionStep(ResourceKey<VillagerProfession> wanted, String timeoutKey) {
			this.wanted = wanted;
			this.timeoutKey = timeoutKey;
		}

		@Override
		public StepResult tick(RunSession session) {
			this.ticks++;
			if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 400) return StepResult.failed(McText.translatable(this.timeoutKey));
			if (session.villager == null || !session.villager.isAlive()) {
				return StepResult.failed(McText.translatable("trademaster.error.villager-gone"));
			}
			return McVillager.profession(session.villager).is(this.wanted)
					? StepResult.DONE
					: StepResult.RUNNING;
		}
	}

	private class PlaceLecternStep extends AbstractLookStep {
		int ticks = 0;
		int delay = TradeMasterConfig.INSTANCE.placeDelay;
		int placeCooldown = 0;
		int placeAttempts = 0;

		@Override
		public StepResult tick(RunSession session) {
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer player = mc.player;
			if (player == null || mc.level == null || mc.gameMode == null) {
				return StepResult.failed(McText.literal("TradeMaster: player/level unavailable"));
			}
			this.ticks++;
			if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.ticks > 200) {
				// most often the villager keeps occupying the spot: recoverable by
				// dispatching it, like the guardian ENSURE path does
				TaskChainRerollStrategy.this.placementBlocked = true;
				return StepResult.failed(McText.translatable("trademaster.error.reroll-timeout"));
			}

			BlockPos lecternPos = session.lecternPos;
			if (LecternRestock.isWorkBlockAt(mc.level, lecternPos)) return StepResult.DONE;
			if (lecternPos.getY() >= McLevel.maxY(mc.level)) {
				return StepResult.failed(McText.translatable("trademaster.message.out_of_bounds"));
			}

			if (!ensureLook(player, lecternAim(lecternPos))) return StepResult.RUNNING;

			// the villager often leans into the lectern block; the server rejects
			// placements that collide with an entity, so wait for the spot to clear
			// instead of burning all place attempts on it (noCollision alone is
			// blind to villagers — lecternSpotClear also scans living entities)
			if (!LecternRestock.lecternSpotClear(mc.level, lecternPos, player)) return StepResult.RUNNING;

			if (TradeMasterConfig.INSTANCE.slowMode && this.delay > 0) {
				this.delay--;
				return StepResult.RUNNING;
			}

			if (this.placeCooldown > 0) {
				this.placeCooldown--;
				return StepResult.RUNNING;
			}

			if (!LecternRestock.offhandHasWorkItem(player)) {
				if (!LecternRestock.restock(player)) {
					return StepResult.failed(McText.translatable("trademaster.message.lectern_lost"));
				}
				// restock clicks only take effect on the server next tick
				return StepResult.RUNNING;
			}

			if (this.placeAttempts >= LecternRestock.MAX_PLACE_ATTEMPTS) {
				TaskChainRerollStrategy.this.placementBlocked = true;
				return StepResult.failed(McText.translatable("trademaster.message.place_failed"));
			}

			LecternRestock.placeLectern(mc, player, lecternPos);
			this.placeAttempts++;
			this.placeCooldown = LecternRestock.PLACE_COOLDOWN_TICKS;
			return StepResult.RUNNING;
		}
	}
}
