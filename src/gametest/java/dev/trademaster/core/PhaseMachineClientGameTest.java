package dev.trademaster.core;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;

/**
 * The phase machine, driven end to end on a real (miniature) rig in a live client. The mod
 * already ticks the controller itself, so this only sets up a world and watches the phase.
 *
 * <p>Two runs: with no villager present PRECHECK must rest in AWAIT_VILLAGER, and once an
 * unemployed villager stands at the wall's detection point PRECHECK must pick it and advance
 * to ENSURE_LECTERN. That covers start()'s gates, the precheck, the arrival-point scan and
 * the profession match against a real entity — none of which a pure-logic test can reach.
 */
@SuppressWarnings("UnstableApiUsage")
public class PhaseMachineClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());
			try {
			configure(context);

			// 1) no villager: the run must start and rest waiting for one
			context.runOnClient((Minecraft mc) -> {
				Component failure = Preconditions.check();
				if (failure != null) throw new AssertionError("preconditions not met: " + failure.getString());
				if (!RerollController.start()) throw new AssertionError("start() refused a valid rig");
			});
			context.waitFor((Minecraft mc) -> RerollController.currentPhase() == Phase.AWAIT_VILLAGER, 100);
			System.out.println("[TM-GAMETEST] phase AWAIT_VILLAGER ok");

			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				if (RerollController.isRunning()) throw new AssertionError("stopManual did not stop the run");
			});

			// 2) an unemployed villager at the detection point: PRECHECK picks it up
			world.getServer().runCommand("summon minecraft:villager 6.5 100 2.5");
			context.waitFor((Minecraft mc) -> !mc.level.getEntitiesOfClass(
					net.minecraft.world.entity.npc.villager.Villager.class,
					net.minecraft.world.phys.AABB.ofSize(
							new net.minecraft.world.phys.Vec3(6.5, 100, 2.5), 4, 4, 4)).isEmpty());

			context.runOnClient((Minecraft mc) -> {
				if (!RerollController.start()) throw new AssertionError("start() refused with a villager present");
			});
			context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.ENSURE_LECTERN.ordinal(), 100);
			System.out.println("[TM-GAMETEST] phase " + context.computeOnClient(mc -> RerollController.currentPhase())
					+ " ok");

			// 3) push deeper: ENSURE_LECTERN's guardian must finish its break/re-place cycle,
			//    the unemployed villager must claim the lectern (WAIT_PROFESSION) and the run
			//    must reach the trade check
			int toProfession = context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.WAIT_PROFESSION.ordinal()
							|| !RerollController.isRunning(), 600);
			context.runOnClient((Minecraft mc) -> {
				if (!RerollController.isRunning()) {
					throw new AssertionError("the run stopped before WAIT_PROFESSION");
				}
				if (RerollController.currentPhase().ordinal() < Phase.WAIT_PROFESSION.ordinal()) {
					throw new AssertionError("stuck in " + RerollController.currentPhase());
				}
			});
			System.out.println("[TM-GAMETEST] WAIT_PROFESSION ok after " + toProfession + " ticks");

			// A completed run resets the phase to IDLE, so the FINAL phase cannot prove the
			// run got here — a run that stopped early would also read as "not CHECK_TRADE".
			// Poll and record the MAX ordinal seen: the phase machine advances one phase per
			// client tick, so every phase the run passes through is observed.
			int maxOrdinal = context.computeOnClient((Minecraft mc) -> RerollController.currentPhase().ordinal());
			int toCheck = 0;
			for (; toCheck < 900; toCheck++) {
				context.waitTick();
				int ord = context.computeOnClient((Minecraft mc) -> RerollController.currentPhase().ordinal());
				if (ord > maxOrdinal) maxOrdinal = ord;
				if (ord >= Phase.CHECK_TRADE.ordinal()) break;
				if (!context.computeOnClient((Minecraft mc) -> RerollController.isRunning())) break;
			}
			final int reached = maxOrdinal;
			context.runOnClient((Minecraft mc) -> {
				System.out.println("[TM-GAMETEST] deep run maxPhaseOrdinal=" + reached
						+ " phase=" + RerollController.currentPhase()
						+ " running=" + RerollController.isRunning());
				if (reached < Phase.CHECK_TRADE.ordinal()) {
					throw new AssertionError("the run never reached CHECK_TRADE (max ordinal " + reached + ")");
				}
			});
			System.out.println("[TM-GAMETEST] reached CHECK_TRADE ok after " + toCheck + " ticks");

			} finally {
			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.lecternPos = null;
				c.summonButtonPos = null;
				c.sendAwayButtonPos = null;
				c.villagerRegionOrigin = null;
				c.villagerRegionDelta = null;
				c.dispenserPos = null;
				c.enchantments.clear();
				c.save();
			});
			}
		}
	}

	/** A small platform with the work block already in place, in reach of the player. */
	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 12 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		// stand right next to the work block: breaking, placing and picking the drop up all
		// need the player within reach
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 64");
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:lectern 2");
		// the guardian places the work block from the OFFHAND
		world.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:lectern");
	}

	/** The rig config plus one enabled, matchable target (so the precheck has demands). */
	private static void configure(ClientGameTestContext context) {
		context.runOnClient((Minecraft mc) -> {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			c.workBlockId = "minecraft:lectern";
			c.professionId = "minecraft:librarian";
			c.lecternPos = LECTERN;
			c.summonButtonPos = SUMMON_BUTTON;
			c.sendAwayButtonPos = SEND_AWAY_BUTTON;
			// a real region: start() otherwise demands the sphere-fallback confirmation
			c.villagerRegionOrigin = new BlockPos(0, 95, 0);
			c.villagerRegionDelta = new BlockPos(12, 10, 12);
			c.dispenserPos = null;
			c.enchantments.clear();
			Identifier mending = Identifier.withDefaultNamespace("mending");
			c.enchantments.put(mending, new EnchantmentTarget(mending, true, 1, 20));
		});
	}
}
