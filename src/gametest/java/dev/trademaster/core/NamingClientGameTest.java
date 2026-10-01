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
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Cross-feature: the WHOLE book flow, from a matching enchanted-book trade through the buy and
 * the anvil naming. A locked book is named onto the villager (so a later re-encounter can be
 * identified and deducted) — the one part of the pipeline that needs a second real GUI (the
 * anvil) and a name tag.
 *
 * <p>The offer is written onto the villager directly (a real novice's random trades are not
 * deterministic): pay emeralds, receive an enchanted book carrying {@code mending 1}. The
 * villager's target profession is the unemployed id, so no job-site claim is needed.
 */
@SuppressWarnings("UnstableApiUsage")
public class NamingClientGameTest implements FabricClientGameTest {

	private static final BlockPos LECTERN = new BlockPos(5, 100, 2);
	private static final BlockPos ANVIL = new BlockPos(3, 100, 4);
	private static final BlockPos SUMMON_BUTTON = new BlockPos(1, 100, 1);
	private static final BlockPos SEND_AWAY_BUTTON = new BlockPos(1, 100, 3);
	private static final Vec3 VILLAGER_AT = new Vec3(6.5, 100, 2.5);
	private static final Identifier MENDING = Identifier.withDefaultNamespace("mending");
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
			// NoAI pins the villager: without it it would claim the placed lectern, become a
			// librarian and REGENERATE its trades, wiping the crafted offer below
			world.getServer().runCommand("data merge entity @e[type=minecraft:villager,limit=1] "
					+ "{NoAI:1b,"
					+ "Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:" + OFFER_PRICE + "},"
					+ "sell:{id:\"minecraft:enchanted_book\",count:1,components:{"
					+ "\"minecraft:stored_enchantments\":{\"minecraft:mending\":1}}},"
					+ "uses:0,maxUses:12,rewardExp:true,specialPrice:0,demand:0,priceMultiplier:0.05,xp:0}]}}");
			context.waitFor((Minecraft mc) ->
					!mc.level.getEntitiesOfClass(Villager.class, AABB.ofSize(VILLAGER_AT, 8, 8, 8)).isEmpty());
			context.waitTicks(5);

			String offers = world.getServer().computeOnServer(server -> {
				var villagers = server.overworld().getEntitiesOfClass(Villager.class,
						AABB.ofSize(VILLAGER_AT, 8, 8, 8));
				if (villagers.isEmpty()) return "no villager";
				StringBuilder sb = new StringBuilder("offers=" + villagers.get(0).getOffers().size());
				for (MerchantOffer o : villagers.get(0).getOffers()) {
					sb.append(" [").append(net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(o.getResult().getItem()))
							.append(" ench=")
							.append(net.minecraft.world.item.enchantment.EnchantmentHelper
									.getEnchantmentsForCrafting(o.getResult()).size())
							.append(" price=").append(o.getBaseCostA().getCount()).append(']');
				}
				return sb.toString();
			});
			System.out.println("[TM-DIAG] " + offers);
			if (!offers.contains("enchanted_book") || !offers.contains("ench=1")) {
				throw new AssertionError("the crafted enchanted-book offer did not stick: " + offers);
			}

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
				c.namingEnabled = true;
				c.goods.clear();
				c.enchantments.clear();
				c.enchantments.put(MENDING, new EnchantmentTarget(MENDING, true, 1, OFFER_PRICE + 6));
				if (!RerollController.start()) throw new AssertionError("start() refused");
			});

			context.waitFor((Minecraft mc) ->
					RerollController.currentPhase().ordinal() >= Phase.NAME.ordinal()
							|| !RerollController.isRunning(), 1500);
			context.runOnClient((Minecraft mc) -> {
				Phase phase = RerollController.currentPhase();
				System.out.println("[TM-GAMETEST] book run phase=" + phase
						+ " running=" + RerollController.isRunning());
				if (phase.ordinal() < Phase.NAME.ordinal()) {
					throw new AssertionError("the matching book did not reach NAME, phase=" + phase);
				}
			});
			System.out.println("[TM-GAMETEST] reached NAME ok");

			// the naming must actually put a custom name on the villager
			try {
				context.waitFor((Minecraft mc) -> namedVillager(mc) != null, 1800);
			} catch (AssertionError e) {
				Phase phase = context.computeOnClient(mc -> RerollController.currentPhase());
				throw new AssertionError("no villager was named; phase=" + phase, e);
			}
			String name = context.computeOnClient((Minecraft mc) -> {
				Villager villager = namedVillager(mc);
				return villager == null ? "<gone>" : villager.getCustomName().getString();
			});
			// the exact composed name is the mod's contract with its own deduction parser
			// (display name + roman level + price): assert it, not just that a name exists
			// the client runs en_us: pin the LITERAL so the assertion does not duplicate the
			// production language lookup (and a missing en_us key surfaces as a failure)
			String expected = "Mending I " + OFFER_PRICE;
			if (!expected.equals(name)) {
				throw new AssertionError("expected the villager to be named \"" + expected + "\", got \"" + name + "\"");
			}
			System.out.println("[TM-GAMETEST] villager named: " + name);

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

	private static Villager namedVillager(Minecraft mc) {
		for (Villager villager : mc.level.getEntitiesOfClass(Villager.class,
				AABB.ofSize(VILLAGER_AT, 24, 24, 24))) {
			if (villager.getCustomName() != null) return villager;
		}
		return null;
	}

	private static void buildRig(TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 99 0 12 99 12 minecraft:stone");
		world.getServer().runCommand("setblock 5 100 2 minecraft:lectern");
		world.getServer().runCommand("setblock 3 100 4 minecraft:anvil");
		world.getServer().runCommand("setblock 1 100 1 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("setblock 1 100 3 minecraft:stone_button[face=floor]");
		world.getServer().runCommand("tp @p 4.5 100 2.5");
		world.getServer().runCommand("give @p minecraft:emerald 64");
		world.getServer().runCommand("give @p minecraft:book 64");
		world.getServer().runCommand("give @p minecraft:name_tag 1");
		world.getServer().runCommand("give @p minecraft:diamond_axe 1");
		world.getServer().runCommand("give @p minecraft:lectern 2");
		world.getServer().runCommand("item replace entity @p weapon.offhand with minecraft:lectern");
		// a fresh name tag costs one experience level
		world.getServer().runCommand("xp add @p 10 levels");
	}
}
