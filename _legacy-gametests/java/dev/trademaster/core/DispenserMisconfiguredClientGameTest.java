package dev.trademaster.core;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Cross-feature: a dispenser position that holds something OTHER than a dispenser must end the
 * run with a message instead of waiting for a minecart for ever.
 *
 * <p>The gate is reached from SUMMON, so this needs a run that actually gets there: a villager
 * inside the staging region but away from the ray's detection point makes AWAIT_VILLAGER
 * dispatch it, which is the only path into SUMMON. Every dispenser result except "there is a
 * cart" keeps the summon blocked, so the observable difference is that the run STOPS here
 * rather than hanging with the summon gate still waiting.
 */
@SuppressWarnings("UnstableApiUsage")
public class DispenserMisconfiguredClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	/** A stone block: a valid, reachable position that is NOT a dispenser. */
	private static final BlockPos NOT_A_DISPENSER = new BlockPos(2, 100, 4);
	private static final Vec3 VILLAGER_AT = new Vec3(10.5, 100, 2.5);
	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());

			// in the staging region (so it is dispatched) but off the detection ray (so the
			// precheck does not pick it up directly) — the only way into SUMMON
			world.getServer().runCommand("summon minecraft:villager 10.5 100 2.5 {NoAI:1b,VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:librarian\",level:1}}");
			context.waitFor((Minecraft mc) -> !mc.level.getEntitiesOfClass(Villager.class,
					AABB.ofSize(VILLAGER_AT, 6, 6, 6)).isEmpty());

			try {
			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.workBlockId = "minecraft:lectern";
				c.professionId = "minecraft:librarian";
				c.lecternPos = LECTERN;
				c.summonButtonPos = SUMMON_BUTTON;
				c.sendAwayButtonPos = SEND_AWAY_BUTTON;
				c.villagerRegionOrigin = new BlockPos(0, 95, 0);
				c.villagerRegionDelta = new BlockPos(12, 10, 12);
				c.dispenserPos = NOT_A_DISPENSER;
				c.namingEnabled = false;
				c.goods.clear();
				c.chestPositions.clear();
				c.enchantments.clear();
				c.enchantments.put(MENDING, new EnchantmentTarget(MENDING, true, 1, 16));
				if (!RerollController.start()) throw new AssertionError("start() refused");
			});

			boolean sawSummon = false;
			boolean stopped = false;
			for (int i = 0; i < 300 && !stopped; i++) {
				context.waitTick();
				if (context.computeOnClient((Minecraft mc) ->
						RerollController.currentPhase() == Phase.SUMMON)) {
					sawSummon = true;
				}
				stopped = context.computeOnClient((Minecraft mc) -> !RerollController.isRunning());
			}
			if (!sawSummon) throw new AssertionError("the run never reached SUMMON");
			if (!stopped) {
				throw new AssertionError("a non-dispenser position must stop the run, not hang: phase="
						+ context.computeOnClient(mc -> RerollController.currentPhase()));
			}
			System.out.println("[TM-GAMETEST] misconfigured dispenser stopped the run ok");

			} finally {
			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.professionId = "minecraft:librarian";
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

	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 14 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 2 100 4 minecraft:stone");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 64");
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
	}
}
