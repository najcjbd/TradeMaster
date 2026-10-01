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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;

/**
 * Cross-feature: with a locked book but NO experience level, naming is skipped — the villager
 * goes to SEND_AWAY unnamed rather than the run failing at the anvil.
 *
 * <p>Naming a villager costs a level. The offer rewards no experience and the player is put on
 * level 0, so the moment NAME starts it must divert to SEND_AWAY and leave the villager
 * unnamed. (The player is also forced to survival: in creative no level is needed at all.)
 */
@SuppressWarnings("UnstableApiUsage")
public class NamingXpShortClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos ANVIL = new BlockPos(3, 100, 4);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	private static final Vec3 VILLAGER_AT = new Vec3(6.5, 100, 2.5);
	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());

			world.getServer().runCommand("summon minecraft:villager 6.5 100 2.5");
			// a matching book, but the trade rewards no experience (the level must stay 0)
			world.getServer().runCommand("data merge entity @e[type=minecraft:villager,limit=1] "
					+ "{NoAI:1b,VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:librarian\",level:1},Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:10},"
					+ "sell:{id:\"minecraft:enchanted_book\",count:1,components:{"
					+ "\"minecraft:stored_enchantments\":{\"minecraft:mending\":1}}},"
					+ "uses:0,maxUses:12,rewardExp:false,specialPrice:0,demand:0,priceMultiplier:0.05,xp:0}]}}");
			context.waitFor((Minecraft mc) ->
					!mc.level.getEntitiesOfClass(Villager.class, AABB.ofSize(VILLAGER_AT, 8, 8, 8)).isEmpty());
			context.waitTicks(5);

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
				c.namingEnabled = true;
				c.goods.clear();
				c.chestPositions.clear();
				c.enchantments.clear();
				c.enchantments.put(MENDING, new EnchantmentTarget(MENDING, true, 1, 16));
				if (!RerollController.start()) throw new AssertionError("start() refused");
			});

			context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.NAME.ordinal()
							|| !RerollController.isRunning(), 1500);
			context.runOnClient((Minecraft mc) -> {
				if (RerollController.currentPhase().ordinal() < Phase.NAME.ordinal()) {
					throw new AssertionError("the book run did not reach NAME, phase="
							+ RerollController.currentPhase());
				}
				if (mc.player.experienceLevel >= 1 || mc.player.hasInfiniteMaterials()) {
					throw new AssertionError("the test needs a level-0 survival player, level="
							+ mc.player.experienceLevel);
				}
			});
			System.out.println("[TM-GAMETEST] reached NAME with no XP ok");

			// no level: naming is skipped and the villager is dispatched unnamed
			context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.SEND_AWAY.ordinal()
							|| !RerollController.isRunning(), 900);
			context.runOnClient((Minecraft mc) -> {
				Phase phase = RerollController.currentPhase();
				System.out.println("[TM-GAMETEST] xp-short phase=" + phase
						+ " running=" + RerollController.isRunning());
				if (phase.ordinal() < Phase.SEND_AWAY.ordinal()) {
					throw new AssertionError("without XP the run should skip naming, phase=" + phase);
				}
			});
			context.waitTicks(2);
			context.runOnClient((Minecraft mc) -> {
				for (Villager villager : mc.level.getEntitiesOfClass(Villager.class,
						AABB.ofSize(VILLAGER_AT, 24, 24, 24))) {
					if (villager.getCustomName() != null) {
						throw new AssertionError("the villager was named without XP: "
								+ villager.getCustomName().getString());
					}
				}
			});
			System.out.println("[TM-GAMETEST] xp-short naming skipped ok");

			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.professionId = "minecraft:librarian";
				c.namingEnabled = false;
				c.lecternPos = null;
				c.summonButtonPos = null;
				c.sendAwayButtonPos = null;
				c.villagerRegionOrigin = null;
				c.villagerRegionDelta = null;
				c.enchantments.clear();
				c.save();
			});
		}
	}

	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 12 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 3 100 4 minecraft:anvil");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		// survival + level 0: naming costs a level, so it must be skipped
		world.getServer().runCommand("gamemode survival @p");
		world.getServer().runCommand("xp set @p 0 levels");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 64");
		world.getServer().runCommand("give @p minecraft:name_tag 1");
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:lectern 2");
		world.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:lectern");
	}
}
