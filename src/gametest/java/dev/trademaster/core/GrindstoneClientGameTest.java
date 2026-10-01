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
 * Cross-feature: a full inventory with the grindstone configured sends the run into the
 * GRINDSTONE recycler, which must open the grindstone, grind an enchanted book into a plain
 * one and take it back.
 *
 * <p>The proof is the book counts: one enchanted book, one plain book beforehand, and the run
 * is only done once the enchanted book is gone and a second plain book exists. That is a real
 * grindstone GUI (stage, grind, take, verify) rather than a bookkeeping assertion.
 */
@SuppressWarnings("UnstableApiUsage")
public class GrindstoneClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos GRINDSTONE = new BlockPos(4, 100, 4);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(GRINDSTONE).is(Blocks.GRINDSTONE)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());
			context.runOnClient((Minecraft mc) -> {
				if (Preconditions.hasFreeSlot(mc.player)) {
					throw new AssertionError("the inventory should have been filled completely");
				}
			});

			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.workBlockId = "minecraft:lectern";
				c.professionId = "minecraft:none";
				c.lecternPos = LECTERN;
				c.summonButtonPos = SUMMON_BUTTON;
				c.sendAwayButtonPos = SEND_AWAY_BUTTON;
				c.villagerRegionOrigin = new BlockPos(0, 95, 0);
				c.villagerRegionDelta = new BlockPos(12, 10, 12);
				c.dispenserPos = null;
				c.namingEnabled = false;
				c.goods.clear();
				c.chestPositions.clear();
				// the grindstone path needs both its position AND a drop direction
				c.grindstoneEnabled = true;
				c.grindstonePos = GRINDSTONE;
				c.curseDropYaw = 0.0f;
				c.curseDropPitch = 0.0f;
				c.enchantments.clear();
				c.enchantments.put(MENDING, new EnchantmentTarget(MENDING, true, 1, 16));
				if (!RerollController.start()) throw new AssertionError("start() refused");
			});

			boolean[] sawDeposit = { false };
			context.waitFor((Minecraft mc) -> {
				if (RerollController.currentPhase() == Phase.DEPOSIT) sawDeposit[0] = true;
				// count the RAW inventory: the recycler verifies with the very same
				// Preconditions.countItem, so using that as the oracle too would hide a bug in it
				int enchanted = 0;
				int plain = 0;
				for (int i = 0; i < 36; i++) {
					var stack = mc.player.getInventory().getItem(i);
					if (stack.is(Items.ENCHANTED_BOOK)) enchanted += stack.getCount();
					else if (stack.is(Items.BOOK)) plain += stack.getCount();
				}
				return enchanted == 0 && plain >= 2;
			}, 900);
			if (!sawDeposit[0]) throw new AssertionError("the run never entered DEPOSIT");
			System.out.println("[TM-GAMETEST] grindstone recycled the book ok");

			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.professionId = "minecraft:librarian";
				c.lecternPos = null;
				c.summonButtonPos = null;
				c.sendAwayButtonPos = null;
				c.villagerRegionOrigin = null;
				c.villagerRegionDelta = null;
				c.grindstonePos = null;
				c.curseDropYaw = null;
				c.curseDropPitch = null;
				c.enchantments.clear();
				c.save();
			});
		}
	}

	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 12 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 4 100 4 minecraft:grindstone");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		// one plain book (the precheck needs one) and one enchanted book to grind
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 1");
		world.getServer().runCommand("give @p minecraft:enchanted_book["
				+ "minecraft:stored_enchantments={\"minecraft:mending\":1}] 1");
		world.getServer().runCommand("give @p minecraft:stone 2048");
	}
}
