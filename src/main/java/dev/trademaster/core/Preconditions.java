package dev.trademaster.core;

import dev.trademaster.compat.McRegistry;
import dev.trademaster.compat.McTools;
import dev.trademaster.compat.McItem;
import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McText;
import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.matching.TradeMatcher;
import dev.trademaster.strategy.LecternRestock;
import dev.trademaster.strategy.RigTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Startup and per-loop precondition checks. */
public final class Preconditions {

	private Preconditions() {}

	/** @return failure reason, or null when all preconditions are met. */
	public static @Nullable Component check() {
		// the position the RUN is judged against, not the live edit: see
		// RerollController.runningLecternPos
		return check(RerollController.runningLecternPos());
	}

	/**
	 * Runtime variant: judges the lectern at the position the run actually
	 * started with. The config can be edited while a run is going (the settings
	 * rows commit per keystroke), and comparing those live edits against the
	 * running rig reported a bogus lectern_lost and stopped healthy runs.
	 *
	 * @param lectern the run's lectern position, or null when it was never set
	 */
	public static @Nullable Component check(@Nullable BlockPos lectern) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		if (player == null || client.level == null) {
			return McText.literal("not in game");
		}

		if (lectern == null) return missing("trademaster.settings.lecternPos");
		if (config.summonButtonPos == null) return missing("trademaster.settings.summonButtonPos");
		if (config.sendAwayButtonPos == null) return missing("trademaster.settings.sendAwayButtonPos");

		// the SAME live set the matcher can actually match: a row buildNeeds leaves out
		// (air result, vanilla minimum above its max, no longer tradeable) must not
		// demand materials here, or the run is refused (or stopped mid-run) for a trade
		// no villager can ever offer
		List<EnchantmentTarget> enabled = new ArrayList<>();
		for (EnchantmentTarget target : config.enabledTargets()) {
			if (TradeMatcher.targetMatchable(target)) enabled.add(target);
		}
		List<GoodSpec> goods = new ArrayList<>();
		for (GoodSpec good : config.enabledGoods()) {
			if (TradeMatcher.goodMatchable(good)) goods.add(good);
		}
		if (enabled.isEmpty() && goods.isEmpty()) {
			return McText.translatable("trademaster.message.no_enchantments");
		}

		// What the specs declare: for each target, the SUM of that target's slots per
		// item (an "unlimited" slot asks for at least one), then the largest such total
		// across targets. Slot 1 is the price slot: its item is required in that target's
		// own price amount. An air item means the slot is free. The per-target sum (not
		// the per-slot max) matches the matcher's allowance, which also sums a target's
		// slots of the same item.
		Map<Item, Integer> required = new LinkedHashMap<>();
		boolean emeraldPrice = false;
		for (EnchantmentTarget target : enabled) {
			TradeOfferSpec spec = config.specFor(target.id());
			List<TradeOfferSpec.TradeCost> costs = spec.costs();
			Map<Item, Integer> perTarget = new LinkedHashMap<>();
			for (int slot = 0; slot < costs.size(); slot++) {
				TradeOfferSpec.TradeCost cost = costs.get(slot);
				if (cost == null || cost.item() == null || config.isAirItem(cost.item())) continue;
				// the amount the player must CARRY, not the comparison cap: an
				// "unlimited" slot needs one item, while the cap is the comparison
				// sentinel used by the matcher
				// 0 = "this slot is not required" (the matcher skips it), so it must
				// not be demanded here either; a negative amount is "no limit" and
				// only needs one item to be payable
				int needed = slot == 0
						? target.maxPrice()
						: (cost.count() < 0 ? 1 : cost.count());
				if (needed <= 0) continue;
				Item item = McRegistry.value(McBuiltIn.items(), cost.item());
				if (item == null) continue;
				// ANY payable slot that charges emeralds arms the emerald floor: keying it on
				// slot 1 alone meant a spec that declares emeralds in a later slot skipped
				// the floor entirely and the run stopped late at the lock
				if (cost.item().equals(TradeOfferSpec.EMERALD)) emeraldPrice = true;
				perTarget.merge(item, needed, Integer::sum);
			}
			perTarget.forEach((item, amount) -> required.merge(item, amount, Math::max));
		}
		// custom goods declare the same kind of cost slots, with the good's price cap
		for (GoodSpec good : goods) {
			List<TradeOfferSpec.TradeCost> costs = good.spec().costs();
			Map<Item, Integer> perTarget = new LinkedHashMap<>();
			for (int slot = 0; slot < costs.size(); slot++) {
				TradeOfferSpec.TradeCost cost = costs.get(slot);
				if (cost == null || cost.item() == null || config.isAirItem(cost.item())) continue;
				int needed = slot == 0
						? good.maxPrice()
						: (cost.count() < 0 ? 1 : cost.count());
				if (needed <= 0) continue;
				Item item = McRegistry.value(McBuiltIn.items(), cost.item());
				if (item == null) continue;
				// ANY payable slot that charges emeralds arms the emerald floor: keying it on
				// slot 1 alone meant a spec that declares emeralds in a later slot skipped
				// the floor entirely and the run stopped late at the lock
				if (cost.item().equals(TradeOfferSpec.EMERALD)) emeraldPrice = true;
				perTarget.merge(item, needed, Integer::sum);
			}
			perTarget.forEach((item, amount) -> required.merge(item, amount, Math::max));
		}
		if (emeraldPrice) {
			// the emerald floor (largest max price, raised by hunt / the 64 cap) still
			// governs the emerald budget machinery
			required.merge(Items.EMERALD, emeraldFloor(enabled), Math::max);
		}

		// one inventory pass for every item requirement
		Map<Item, Integer> carried = new LinkedHashMap<>();
		int workBlocks = 0;
		boolean toolCarried = false;
		boolean toolNearlyBroken = false;
		// every mechanism breaks the WORK BLOCK at some point (task chain's break step,
		// guardian heal/break paths) — check its tool up front for all of them. The tool is
		// DERIVED from the block's state with the same function the runtime uses (no RNG),
		// falling back to an axe when the block has no better-than-hand tool.
		BlockState workState = RigTarget.stateAt(client.level, lectern);
		// a block that only drops for a tool the player does not carry must be refused up
		// front: breaking it would destroy the work block without a replacement
		boolean cannotHarvest = RigTarget.cannotHarvest(workState, player);
		Item needItem = cannotHarvest ? null : RigTarget.bestToolItem(workState, player);
		boolean needAxe = !cannotHarvest && needItem == null;
		Item workItem = RigTarget.workItem();
		var inventory = player.getInventory();
		for (int i = 0; i < 37; i++) {
			ItemStack stack = i < 36 ? inventory.getItem(i) : player.getOffhandItem();
			if (stack.isEmpty()) continue;
			Item item = stack.getItem();
			// trades can only pay from the main inventory: count emeralds/books
			// there exclusively so precheck matches checkLockMaterials' view
			// (the work block still counts the offhand — the rig places from it)
			if (i < 36 && required.containsKey(item)) carried.merge(item, stack.getCount(), Integer::sum);
			if (workItem != null && item == workItem) workBlocks += stack.getCount();
			// with preventAxeBreaking on, nearly-broken tools are unusable (the strategies
			// refuse them): don't count them, fail up front instead of mid-run at the first
			// break
			if (!toolCarried && (needAxe ? McTools.isAxe(stack) : item == needItem)) {
				if (!config.preventAxeBreaking || !LecternRestock.toolNearlyBroken(stack)) toolCarried = true;
				else toolNearlyBroken = true;
			}
		}

		// name the missing material and the amount: "not enough materials" left the
		// player guessing whether to restock emeralds, books, the work block or a tag
		for (Map.Entry<Item, Integer> entry : required.entrySet()) {
			int have = carried.getOrDefault(entry.getKey(), 0);
			if (have < entry.getValue()) {
				return McText.translatable("trademaster.message.material_short_item",
						itemName(entry.getKey()), entry.getValue(), have);
			}
		}

		if (workBlocks < 1 && !RigTarget.isWorkBlockAt(client.level, lectern)) {
			// the default lectern keeps its established wording
			return McText.translatable(RigTarget.workBlock() == net.minecraft.world.level.block.Blocks.LECTERN
					? "trademaster.message.lectern_lost"
					: "trademaster.message.workblock_lost");
		}

		if (!toolCarried) {
			// "a tool is present but the durability guard rejects it" vs "no tool at all":
			// report the same wording the runtime strategies use (R20-7)
			return McText.translatable(toolNearlyBroken
					? RigTarget.toolBreakingKey(workState, player)
					: RigTarget.toolRequiredKey(workState, player));
		}

		return null;
	}

	/** The item's display name (an ItemStack is needed to resolve it, and this only
	 * runs on the failure path). */
	private static Component itemName(Item item) {
		return new ItemStack(item).getHoverName();
	}

	private static Component missing(String positionKey) {
		return McText.translatable("trademaster.message.precheck_failed",
				McText.translatable(positionKey));
	}

	/** Emerald requirement: the configured floor, raised by the largest outstanding
	 * hunt budget when hunt-cheaper is on — a hunt can demand more than any
	 * configured maxPrice (budget = marked-1), and the run must be able to pay. */
	private static int emeraldFloor(List<EnchantmentTarget> enabled) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		int floor = config.emeraldFloor(enabled);
		if (config.huntCheaper) {
			floor = Math.max(floor, RerollController.maxHuntBudget());
		}
		if (config.multiEnchantProtection) {
			// modified librarian professions can put several enchantments on one
			// book: 64 is the hard price cap, so this keeps any roll payable
			floor = Math.max(floor, 64);
		}
		return floor;
	}

	public static int countItem(LocalPlayer player, Item item) {
		int count = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.is(item)) count += stack.getCount();
		}
		ItemStack offhand = player.getOffhandItem();
		if (offhand.is(item)) count += offhand.getCount();
		return count;
	}

	/** Whether the main inventory (slots 0-35) has an empty slot for a trade result. */
	public static boolean hasFreeSlot(LocalPlayer player) {
		for (int i = 0; i < 36; i++) {
			if (player.getInventory().getItem(i).isEmpty()) return true;
		}
		return false;
	}

	/** Re-checks lock materials right before executing the lock trade: every slot the
	 * LIVE offer charges must be payable (empty slots cost nothing, and an "air" item
	 * is a datapack's way of saying the same). */
	public static boolean checkLockMaterials(LocalPlayer player, ItemStack costA, ItemStack costB) {
		return checkLockMaterials(player, List.of(costA, costB));
	}

	public static boolean checkLockMaterials(LocalPlayer player, List<ItemStack> costs) {
		return shortfall(player, costs) == null;
	}

	/** What the player cannot pay, as a message naming the item and the amount, or
	 * null when every cost slot is covered. */
	public static @Nullable Component shortfall(LocalPlayer player, List<ItemStack> costs) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		List<ItemStack> pending = new ArrayList<>();
		for (ItemStack cost : costs) {
			if (cost == null || cost.isEmpty()) continue;
			ResourceLocation id = McBuiltIn.items().getKey(cost.getItem());
			if (config.isAirItem(id)) continue;
			pending.add(cost);
		}
		if (pending.isEmpty()) return null;
		// a stack matches a slot only when the components match too (a renamed or
		// enchanted book is not the plain book a trade asks for)
		int[] need = new int[pending.size()];
		for (int slot = 0; slot < pending.size(); slot++) need[slot] = pending.get(slot).getCount();
		// a stack may satisfy only as much as it holds: without tracking what is left,
		// one stack would cover several cost slots and the check would pass on items the
		// player does not have (the server then rejects the purchase)
		int[] remaining = new int[36];
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			remaining[i] = stack.isEmpty() ? 0 : stack.getCount();
		}
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.isEmpty()) continue;
			for (int slot = 0; slot < pending.size(); slot++) {
				if (need[slot] <= 0 || remaining[i] <= 0) continue;
				if (McItem.sameItemSameComponents(stack, pending.get(slot))) {
					int take = Math.min(need[slot], remaining[i]);
					need[slot] -= take;
					remaining[i] -= take;
				}
			}
		}
		for (int slot = 0; slot < pending.size(); slot++) {
			if (need[slot] > 0) {
				return McText.translatable("trademaster.message.material_short_item",
						itemName(pending.get(slot).getItem()), pending.get(slot).getCount(),
						pending.get(slot).getCount() - need[slot]);
			}
		}
		return null;
	}
}
