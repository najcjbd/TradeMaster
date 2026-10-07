package dev.trademaster.gametest;

import dev.trademaster.strategy.RigTarget;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

/**
 * Smoke test: proves the client-gametest harness boots, that the test mod can reach the
 * main mod's classes across the mod classloader, and that the registries a real client
 * boots are populated.
 */
@SuppressWarnings("UnstableApiUsage")
public class TradeMasterClientGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient((Minecraft mc) -> {
			if (mc.level != null || mc.player != null) {
				throw new AssertionError("client game test should start on the title screen");
			}
			// reach into the mod: the default config resolves the lectern through the real
			// block registry a running client has
			if (RigTarget.workBlock() != Blocks.LECTERN) {
				throw new AssertionError("expected the lectern, got " + RigTarget.workBlock());
			}
			System.out.println("[TM-GAMETEST] smoke ok; workBlock=" + RigTarget.workBlock()
					+ " professionKnown=" + RigTarget.professionKnown());
		});
		System.out.println("[TM-GAMETEST] smoke finished");
	}
}
