package dev.trademaster.core;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;

/**
 * Cross-feature: an offer that does NOT match any demand must send the run into REROLL (break
 * and re-place the work block to force a fresh roll) instead of locking or stopping.
 *
 * <p>The villager sells a composter while the mod watches for a mending book, so the offer can
 * never match — deterministic, unlike a real librarian's random trades.
 */
@SuppressWarnings("UnstableApiUsage")
public class RerollClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	private static final net.minecraft.world.phys.Vec3 VILLAGER_AT = new net.minecraft.world.phys.Vec3(6.5, 100, 2.5);
	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());

			// NoAI pins the villager so it cannot claim the lectern and self-generate trades
			world.getServer().runCommand("summon minecraft:villager 6.5 100 2.5");
			world.getServer().runCommand("data merge entity @e[type=minecraft:villager,limit=1] "
					+ "{NoAI:1b,VillagerData:{type:\"minecraft:plains\",profession:\"minecraft:librarian\",level:1},Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:10},"
					+ "sell:{id:\"minecraft:composter\",count:1},"
					+ "uses:0,maxUses:12,rewardExp:true,specialPrice:0,demand:0,priceMultiplier:0.05,xp:0}]}}");
			context.waitFor((Minecraft mc) -> !mc.level.getEntitiesOfClass(
					net.minecraft.world.entity.npc.villager.Villager.class,
					net.minecraft.world.phys.AABB.ofSize(VILLAGER_AT, 8, 8, 8)).isEmpty());
			context.waitTicks(5);

			// confirm the offer actually landed before judging the phase: without it the run
			// would have NO offers, and a REROLL failure could not be told from a rig that
			// never produced an offer at all
			String offers = world.getServer().computeOnServer(server -> {
				var villagers = server.overworld().getEntitiesOfClass(
						net.minecraft.world.entity.npc.villager.Villager.class,
						net.minecraft.world.phys.AABB.ofSize(VILLAGER_AT, 8, 8, 8));
				if (villagers.isEmpty()) return "no villager";
				StringBuilder sb = new StringBuilder("offers=" + villagers.get(0).getOffers().size());
				for (MerchantOffer o : villagers.get(0).getOffers()) {
					sb.append(" [").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(o.getResult().getItem())).append(']');
				}
				return sb.toString();
			});
			System.out.println("[TM-DIAG] " + offers);
			if (!offers.contains("minecraft:composter")) {
				throw new AssertionError("the crafted offer did not stick: " + offers);
			}

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
				c.dispenserPos = null;
				c.namingEnabled = false;
				c.goods.clear();
				c.chestPositions.clear();
				c.enchantments.clear();
				// watch for a mending book: the composter offer can never match it
				c.enchantments.put(MENDING, new EnchantmentTarget(MENDING, true, 1, 16));
				if (!RerollController.start()) throw new AssertionError("start() refused");
			});

			context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.REROLL.ordinal()
							|| !RerollController.isRunning(), 900);
			context.runOnClient((Minecraft mc) -> {
				Phase phase = RerollController.currentPhase();
				System.out.println("[TM-GAMETEST] non-matching offer phase=" + phase
						+ " running=" + RerollController.isRunning());
				if (phase.ordinal() < Phase.REROLL.ordinal()) {
					throw new AssertionError("a non-matching offer did not reach REROLL, phase=" + phase);
				}
			});
			System.out.println("[TM-GAMETEST] non-matching offer reached REROLL ok");

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
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 64");
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:lectern 2");
		world.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:lectern");
	}
}
