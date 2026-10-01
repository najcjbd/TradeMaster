package dev.trademaster.gametest;

import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.strategy.LecternRestock;
import dev.trademaster.strategy.RigTarget;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Cross-feature: the DERIVED tool policy against a live client inventory. Uses real vanilla
 * tool speeds, so it also checks the algorithm against the game's own numbers (a golden axe
 * is faster than a diamond axe on a lectern; on stone only pickaxes beat the hand) and the
 * equip path (hotbar selection), which a pure-logic test cannot reach.
 *
 * <p>Three steps: the fastest tool wins; a nearly broken locked tool is released for a
 * healthy slower one; and switching the configured block to stone makes a pickaxe win.
 */
@SuppressWarnings("UnstableApiUsage")
public class ToolEquipClientGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			context.runOnClient((Minecraft mc) -> {
				if (mc.player == null) throw new AssertionError("no player");
				TradeMasterConfig.INSTANCE.preventAxeBreaking = true;
				TradeMasterConfig.INSTANCE.workBlockId = "minecraft:lectern";
				RigTarget.clearToolLock();
				mc.player.getInventory().setSelectedSlot(0);
			});

			// 1) the fastest tool wins: golden axe (12) over diamond axe (8) on a lectern
			give(context, world, Items.DIAMOND_AXE, "minecraft:diamond_axe",
					Items.GOLDEN_AXE, "minecraft:golden_axe");
			expectHeld(context, "fastest tool", Items.GOLDEN_AXE);

			// 2) the locked (now nearly broken) golden axe must be released for the healthy
			//    diamond axe, WITHOUT clearing the lock by hand
			world.getServer().runCommand("item replace entity @p hotbar.1 with minecraft:golden_axe[minecraft:damage=30]");
			context.waitFor((Minecraft mc) -> mc.player != null
					&& mc.player.getInventory().getItem(1).getDamageValue() >= 30);
			context.runOnClient((Minecraft mc) -> mc.player.getInventory().setSelectedSlot(1));
			expectHeld(context, "guard releases the broken locked tool", Items.DIAMOND_AXE);

			// 3) the tool follows the configured block: on stone only a pickaxe beats the
			//    hand, so the pickaxe must win over an axe
			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig.INSTANCE.workBlockId = "minecraft:stone";
				RigTarget.clearToolLock();
				mc.player.getInventory().setSelectedSlot(1);
			});
			give(context, world, Items.DIAMOND_PICKAXE, "minecraft:diamond_pickaxe",
					Items.DIAMOND_AXE, "minecraft:diamond_axe");
			expectHeld(context, "on stone the pickaxe wins", Items.DIAMOND_PICKAXE);

			// 4) a block that only drops for a tool we do NOT carry must be refused: breaking
			//    it with the legacy axe would destroy the work block without a drop
			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig.INSTANCE.workBlockId = "minecraft:stonecutter";
				RigTarget.clearToolLock();
			});
			give(context, world, Items.IRON_AXE, "minecraft:iron_axe", Items.STICK, "minecraft:stick");
			context.runOnClient((Minecraft mc) -> {
				var state = RigTarget.workBlock().defaultBlockState();
				if (!state.requiresCorrectToolForDrops()) {
					throw new AssertionError("premise failed: a stonecutter needs the correct tool");
				}
				if (LecternRestock.bringToolToHand(mc, mc.player, state) != LecternRestock.ToolState.NONE) {
					throw new AssertionError("an axe must not be equipped to break a stonecutter");
				}
				String key = RigTarget.toolRequiredKey(state, mc.player);
				if (!key.equals("trademaster.message.tool_cannot_harvest")) {
					throw new AssertionError("expected the cannot-harvest key, got " + key);
				}
			});
			System.out.println("[TM-GAMETEST] cannot-harvest tool refused ok");
			System.out.println("[TM-GAMETEST] tool equip ok");

			// clean up: restore the config and the inventory
			context.runOnClient((Minecraft mc) -> {
				TradeMasterConfig.INSTANCE.workBlockId = "minecraft:lectern";
				RigTarget.clearToolLock();
				mc.player.getInventory().setSelectedSlot(0);
			});
			world.getServer().runCommand("clear @p");
			context.waitTick();
		}
	}

	/** Fills hotbar slots 0/1 and waits until the CLIENT sees exactly those items (a plain
	 * "non-empty" wait would pass on the previous step's items before the command lands). */
	private static void give(ClientGameTestContext context, TestSingleplayerContext world,
			Item slot0Item, String slot0Spec, Item slot1Item, String slot1Spec) {
		world.getServer().runCommand("item replace entity @p hotbar.0 with " + slot0Spec);
		world.getServer().runCommand("item replace entity @p hotbar.1 with " + slot1Spec);
		context.waitFor((Minecraft mc) -> mc.player != null
				&& mc.player.getInventory().getItem(0).is(slot0Item)
				&& mc.player.getInventory().getItem(1).is(slot1Item));
	}

	private static void expectHeld(ClientGameTestContext context, String what, Item expected) {
		context.runOnClient((Minecraft mc) -> {
			var state = RigTarget.workBlock().defaultBlockState();
			LecternRestock.bringToolToHand(mc, mc.player, state);
			ItemStack held = mc.player.getInventory().getSelectedItem();
			if (!held.is(expected)) {
				throw new AssertionError(what + ": expected " + expected + ", held " + held.getItem());
			}
		});
	}
}
