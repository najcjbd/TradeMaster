package dev.trademaster.core;

import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Cross-feature: a MATCHING trade drives the run past the trade check into the buy.
 *
 * <p>The offered trade is written straight onto the villager so the test is deterministic (a
 * real novice librarian's random trades might never include the wanted book). It sells a plain
 * item — a {@code composter} — so the offer needs no component NBT, and the mod watches for it
 * as a custom GOOD. The target profession is the unemployed id, so the villager needs no AI
 * job-site claim: the run can reach the trade check immediately.
 *
 * <p>A match must carry the run into LOCK; a run with no match only ever oscillates
 * CHECK_TRADE &lt;-&gt; REROLL and never reaches LOCK. That exercises the demand table, the offer
 * matcher (result + count + cost slots) and the transition into the buy.
 */
@SuppressWarnings("UnstableApiUsage")
public class TradeMatchClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	private static final Vec3 VILLAGER_AT = new Vec3(6.5, 100, 2.5);
	private static final Identifier GOOD = Identifier.withDefaultNamespace("composter");
	private static final int OFFER_PRICE = 10;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			buildRig(world);
			context.waitFor((Minecraft mc) -> mc.level != null
					&& mc.level.getBlockState(LECTERN).is(Blocks.LECTERN)
					&& mc.player != null && !mc.player.getInventory().getItem(0).isEmpty());

			world.getServer().runCommand("summon minecraft:villager 6.5 100 2.5");
			// write one deterministic offer: pay emeralds, receive a composter. NoAI pins the
			// villager so it cannot claim the placed lectern and regenerate its own trades,
			// which would wipe the crafted offer.
			world.getServer().runCommand("data merge entity @e[type=minecraft:villager,limit=1] "
					+ "{NoAI:1b,"
					+ "Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:" + OFFER_PRICE + "},"
					+ "sell:{id:\"minecraft:composter\",count:1},"
					+ "uses:0,maxUses:12,rewardExp:true,specialPrice:0,demand:0,priceMultiplier:0.05,xp:0}]}}");
			context.waitFor((Minecraft mc) ->
					!mc.level.getEntitiesOfClass(Villager.class, AABB.ofSize(VILLAGER_AT, 8, 8, 8)).isEmpty());
			context.waitTicks(5);

			// confirm the offer actually landed (server side)
			String offers = world.getServer().computeOnServer(server -> {
				var villagers = server.overworld().getEntitiesOfClass(Villager.class,
						AABB.ofSize(VILLAGER_AT, 8, 8, 8));
				if (villagers.isEmpty()) return "no villager";
				StringBuilder sb = new StringBuilder("offers=" + villagers.get(0).getOffers().size());
				for (MerchantOffer o : villagers.get(0).getOffers()) {
					sb.append(" [").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(o.getResult().getItem())).append(" price=").append(o.getBaseCostA().getCount())
							.append(']');
				}
				return sb.toString();
			});
			System.out.println("[TM-DIAG] " + offers);
			if (!offers.contains("minecraft:composter")) {
				throw new AssertionError("the crafted offer did not stick: " + offers);
			}

			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.workBlockId = "minecraft:lectern";
				// the villager is unemployed; targeting that id needs no job-site claim
				c.professionId = "minecraft:none";
				c.lecternPos = LECTERN;
				c.summonButtonPos = SUMMON_BUTTON;
				c.sendAwayButtonPos = SEND_AWAY_BUTTON;
				c.villagerRegionOrigin = new BlockPos(0, 95, 0);
				c.villagerRegionDelta = new BlockPos(12, 10, 12);
				c.dispenserPos = null;
				c.enchantments.clear();
				c.goods.clear();
				c.goods.add(new GoodSpec(GOOD, 1, true, OFFER_PRICE + 6,
						TradeOfferSpec.defaultGoodCosts()));
				if (!RerollController.start()) throw new AssertionError("start() refused");
			});

			context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.LOCK.ordinal()
							|| !RerollController.isRunning(), 1200);
			context.runOnClient((Minecraft mc) -> {
				Phase phase = RerollController.currentPhase();
				System.out.println("[TM-GAMETEST] matched run phase=" + phase
						+ " running=" + RerollController.isRunning());
				if (phase.ordinal() < Phase.LOCK.ordinal()) {
					throw new AssertionError("the matching trade did not reach LOCK, phase=" + phase);
				}
			});
			System.out.println("[TM-GAMETEST] matched trade reached LOCK ok");

			context.runOnClient((Minecraft mc) -> {
				RerollController.stopManual();
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				c.professionId = "minecraft:librarian";
				c.lecternPos = null;
				c.summonButtonPos = null;
				c.sendAwayButtonPos = null;
				c.villagerRegionOrigin = null;
				c.villagerRegionDelta = null;
				c.goods.clear();
				c.save();
			});
		}
	}

	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 12 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:lectern 2");
		world.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:lectern");
	}
}
