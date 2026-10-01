package dev.trademaster.core;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Cross-feature: a FULL inventory diverts the run into DEPOSIT, which must open the bound chest,
 * move the enchanted books into it and hand control back to the loop.
 *
 * <p>Filling every one of the 36 main-inventory slots (with stones, the precheck materials and
 * one spare enchanted book) makes the very first PRECHECK tick take the disposal branch — no
 * villager is needed, so the whole test is about the chest flow. It is a real container GUI
 * (open, click, close) plus the loop resuming once a slot is free.
 */
@SuppressWarnings("UnstableApiUsage")
public class DepositClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos CHEST = new BlockPos(2, 100, 4);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(CHEST).is(Blocks.CHEST)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());
			context.runOnClient((Minecraft mc) -> {
				if (Preconditions.hasFreeSlot(mc.player)) {
					throw new AssertionError("the inventory should have been filled completely");
				}
			});

			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.workBlockId = "minecraft:lectern";
				c.professionId = "minecraft:librarian";
				c.lecternPos = LECTERN;
				c.summonButtonPos = SUMMON_BUTTON;
				c.sendAwayButtonPos = SEND_AWAY_BUTTON;
				c.villagerRegionOrigin = new BlockPos(0, 95, 0);
				c.villagerRegionDelta = new BlockPos(12, 10, 12);
				c.dispenserPos = null;
				c.namingEnabled = false;
				c.goods.clear();
				// neutralise the grindstone explicitly rather than relying on whatever the
				// previous entrypoint happened to leave in the shared config singleton
				c.grindstoneEnabled = false;
				c.grindstonePos = null;
				c.curseDropYaw = null;
				c.curseDropPitch = null;
				c.chestPositions.clear();
				c.addChest(CHEST);
				c.enchantments.clear();
				c.enchantments.put(MENDING, new EnchantmentTarget(MENDING, true, 1, 16));
				if (!RerollController.start()) throw new AssertionError("start() refused");
			});

			// the first precheck tick must enter DEPOSIT; then it must LEAVE it (loop resumed)
			boolean[] sawDeposit = { false };
			context.waitFor((Minecraft mc) -> {
				if (RerollController.currentPhase() == Phase.DEPOSIT) sawDeposit[0] = true;
				return sawDeposit[0] && RerollController.currentPhase() != Phase.DEPOSIT;
			}, 600);
			if (!sawDeposit[0]) throw new AssertionError("the run never entered DEPOSIT");
			System.out.println("[TM-GAMETEST] DEPOSIT left, phase="
					+ context.computeOnClient(mc -> RerollController.currentPhase()));

			boolean inChest = world.getServer().computeOnServer(server -> {
				var blockEntity = server.overworld().getBlockEntity(CHEST);
				if (!(blockEntity instanceof net.minecraft.world.Container container)) return false;
				for (int i = 0; i < container.getContainerSize(); i++) {
					if (container.getItem(i).is(Items.ENCHANTED_BOOK)) return true;
				}
				return false;
			});
			if (!inChest) throw new AssertionError("the enchanted book was not deposited into the chest");
			System.out.println("[TM-GAMETEST] deposit into chest ok");

			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.professionId = "minecraft:librarian";
				c.lecternPos = null;
				c.summonButtonPos = null;
				c.sendAwayButtonPos = null;
				c.villagerRegionOrigin = null;
				c.villagerRegionDelta = null;
				c.chestPositions.clear();
				c.enchantments.clear();
				c.save();
			});
		}
	}

	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 12 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 2 100 4 minecraft:chest");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		// the precheck materials, one spare book to dispose of, then fill every other slot
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 64");
		world.getServer().runCommand("give @p minecraft:enchanted_book 1");
		world.getServer().runCommand("give @p minecraft:stone 2048");
	}
}
