package dev.trademaster.core;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;

/**
 * Cross-feature: with no staging region configured, start() must REFUSE and ask for the
 * sphere-fallback confirmation; the real client command {@code /trademaster goon} then grants
 * it, and the next start() proceeds.
 *
 * <p>That covers the confirmation gate and the command that clears it — the only entry point
 * into a run that needs a deliberate second action.
 */
@SuppressWarnings("UnstableApiUsage")
public class RegionFallbackClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());

			try {
			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.workBlockId = "minecraft:lectern";
				c.professionId = "minecraft:librarian";
				c.lecternPos = LECTERN;
				c.summonButtonPos = SUMMON_BUTTON;
				c.sendAwayButtonPos = SEND_AWAY_BUTTON;
				// NO region: start() must ask for the sphere-fallback confirmation
				c.villagerRegionOrigin = null;
				c.villagerRegionDelta = null;
				c.dispenserPos = null;
				c.namingEnabled = false;
				c.goods.clear();
				c.chestPositions.clear();
				c.enchantments.clear();
				c.enchantments.put(MENDING, new EnchantmentTarget(MENDING, true, 1, 16));
			});

			// 1) without the confirmation the start is refused and nothing runs
			context.runOnClient((Minecraft mc) -> {
				if (RerollController.start()) {
					throw new AssertionError("start() must refuse without the region confirmation");
				}
				if (RerollController.isRunning()) {
					throw new AssertionError("a refused start must not leave the run active");
				}
			});
			System.out.println("[TM-GAMETEST] unconfirmed start refused ok");

			// 2) the real command grants it — and starts the run itself
			context.runOnClient((Minecraft mc) -> mc.player.connection.sendCommand("trademaster goon"));
			boolean started = false;
			for (int i = 0; i < 100 && !started; i++) {
				context.waitTick();
				started = context.computeOnClient((Minecraft mc) -> RerollController.isRunning());
			}
			if (!started) throw new AssertionError("/trademaster goon did not start the run");
			System.out.println("[TM-GAMETEST] goon started the run ok");

			// 3) and the run advances normally from there
			context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.AWAIT_VILLAGER.ordinal()
							|| !RerollController.isRunning(), 200);
			context.runOnClient((Minecraft mc) -> {
				Phase phase = RerollController.currentPhase();
				System.out.println("[TM-GAMETEST] confirmed run phase=" + phase);
				if (phase.ordinal() < Phase.AWAIT_VILLAGER.ordinal()) {
					throw new AssertionError("the confirmed run did not advance, phase=" + phase);
				}
			});
			System.out.println("[TM-GAMETEST] region fallback confirmed ok");

			} finally {
			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.professionId = "minecraft:librarian";
				c.lecternPos = null;
				c.summonButtonPos = null;
				c.sendAwayButtonPos = null;
				c.enchantments.clear();
				c.save();
			});
			}
		}
	}

	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 12 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 64");
	}
}
