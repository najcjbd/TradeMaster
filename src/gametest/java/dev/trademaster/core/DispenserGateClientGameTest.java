package dev.trademaster.core;

import dev.trademaster.config.TradeMasterConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.DispenserMenu;

/**
 * The summon gate against a REAL dispenser in a live client: {@code dispenserReady} must
 * open the block, read its slots (which only exist server-side) and allow the summon only
 * when a minecart is inside.
 *
 * <p>A cart is a positive control (the gate must eventually say yes); an empty dispenser is
 * the negative control (it must never say yes) — and the dispenser is proven to have been
 * OPENED, so the "no" comes from the scan, not from a timeout.
 *
 * <p>The phases run in one method because the gate keeps process-wide state: the empty
 * dispenser sets a one-minute wait, so it must be the last phase.
 */
@SuppressWarnings("UnstableApiUsage")
public class DispenserGateClientGameTest implements FabricClientGameTest {

	private static final BlockPos DISPENSER = new BlockPos(2, 100, 0);
	private static final BlockPos FAR_AWAY = new BlockPos(400, 100, 400);

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world, /*withCart=*/ true);

			// an out-of-reach coordinate is treated like "unset": the gate must not block
			context.runOnClient((Minecraft mc) -> TradeMasterConfig.INSTANCE.dispenserPos = FAR_AWAY);
			context.runOnClient((Minecraft mc) -> {
				if (!RerollController.dispenserReady()) {
					throw new AssertionError("an unreachable dispenser must not block the summon");
				}
			});

			// no dispenser configured at all: also fine
			context.runOnClient((Minecraft mc) -> TradeMasterConfig.INSTANCE.dispenserPos = null);
			context.runOnClient((Minecraft mc) -> {
				if (!RerollController.dispenserReady()) {
					throw new AssertionError("an unset dispenser must not block the summon");
				}
			});

			// positive control: a dispenser holding a minecart eventually allows the summon
			context.runOnClient((Minecraft mc) -> TradeMasterConfig.INSTANCE.dispenserPos = DISPENSER);
			boolean[] opened = { false };
			boolean ready = false;
			for (int i = 0; i < 150 && !ready; i++) {
				context.waitTick();
				ready = context.computeOnClient((Minecraft mc) -> {
					if (mc.player != null && mc.player.containerMenu instanceof DispenserMenu) opened[0] = true;
					return RerollController.dispenserReady();
				});
			}
			if (!ready) throw new AssertionError("a dispenser with a minecart should allow the summon");
			if (!opened[0]) throw new AssertionError("the dispenser was never opened");
			System.out.println("[TM-GAMETEST] dispenser with cart ok");

			// negative control: empty the dispenser; the gate must open it and still refuse
			world.getServer().runCommand("item replace block 2 100 0 container.0 with minecraft:air");
			awaitEmpty(world, context);
			boolean[] openedEmpty = { false };
			for (int i = 0; i < 150; i++) {
				context.waitTick();
				boolean allowed = context.computeOnClient((Minecraft mc) -> {
					if (mc.player != null && mc.player.containerMenu instanceof DispenserMenu) openedEmpty[0] = true;
					return RerollController.dispenserReady();
				});
				if (allowed) throw new AssertionError("an empty dispenser must not allow the summon");
			}
			if (!openedEmpty[0]) throw new AssertionError("the empty dispenser was never opened");
			System.out.println("[TM-GAMETEST] dispenser empty ok");

			context.runOnClient((Minecraft mc) -> TradeMasterConfig.INSTANCE.dispenserPos = null);
		}
	}

	/** A small floor with the dispenser next to where the player stands, in reach. */
	private static void buildRig(TestSingleplayerContext world, boolean withCart) {
		world.getServer().runCommand("fill -3 99 -3 6 99 3 minecraft:stone");
		world.getServer().runCommand("setblock 2 100 0 minecraft:dispenser");
		if (withCart) {
			world.getServer().runCommand("item replace block 2 100 0 container.0 with minecraft:minecart");
		}
		world.getServer().runCommand("tp @p 0.5 100 0.5");
	}

	/** Waits until the (server-side) dispenser slot is actually empty before reading it. */
	private static void awaitEmpty(TestSingleplayerContext world, ClientGameTestContext context) {
		for (int i = 0; i < 60; i++) {
			boolean empty = world.getServer().computeOnServer(server -> {
				var blockEntity = server.overworld().getBlockEntity(DISPENSER);
				return blockEntity instanceof net.minecraft.world.Container container
						&& container.getItem(0).isEmpty();
			});
			if (empty) return;
			context.waitTick();
		}
		throw new AssertionError("the dispenser was never emptied");
	}
}
