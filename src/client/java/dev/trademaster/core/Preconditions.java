package dev.trademaster.core;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.strategy.LecternRestock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
			return Component.literal("not in game");
		}

		if (lectern == null) return missing("trademaster.settings.lecternPos");
		if (config.summonButtonPos == null) return missing("trademaster.settings.summonButtonPos");
		if (config.sendAwayButtonPos == null) return missing("trademaster.settings.sendAwayButtonPos");

		List<EnchantmentTarget> enabled = config.enabledTargets();
		List<GoodSpec> goods = config.enabledGoods();
		if (enabled.isEmpty() && goods.isEmpty()) {
			return Component.translatable("trademaster.message.no_enchantments");
		}

		// What the specs declare: the largest cap any enabled target asks for per item
		// (an "unlimited" slot asks for at least one), so the run can always pay for the
		// target that needs an item most. Slot 1 is the price slot: its item is required
		// in that target's own price amount. An air item means the slot is free.
		Map<Item, Integer> required = new LinkedHashMap<>();
		boolean emeraldPrice = false;
		for (EnchantmentTarget target : enabled) {
			TradeOfferSpec spec = config.specFor(target.id());
			List<TradeOfferSpec.TradeCost> costs = spec.costs();
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
				Item item = BuiltInRegistries.ITEM.get(cost.item()).map(Holder::value).orElse(null);
				if (item == null) continue;
				if (slot == 0 && cost.item().equals(TradeOfferSpec.EMERALD)) emeraldPrice = true;
				required.merge(item, needed, Math::max);
			}
		}
		// custom goods declare the same kind of cost slots, with the good's price cap
		for (GoodSpec good : goods) {
			List<TradeOfferSpec.TradeCost> costs = good.spec().costs();
			for (int slot = 0; slot < costs.size(); slot++) {
				TradeOfferSpec.TradeCost cost = costs.get(slot);
				if (cost == null || cost.item() == null || config.isAirItem(cost.item())) continue;
				int needed = slot == 0
						? good.maxPrice()
						: (cost.count() < 0 ? 1 : cost.count());
				if (needed <= 0) continue;
				Item item = BuiltInRegistries.ITEM.get(cost.item()).map(Holder::value).orElse(null);
				if (item == null) continue;
				if (slot == 0 && cost.item().equals(TradeOfferSpec.EMERALD)) emeraldPrice = true;
				required.merge(item, needed, Math::max);
			}
		}
		if (emeraldPrice) {
			// the emerald floor (largest max price, raised by hunt / the 64 cap) still
			// governs the emerald budget machinery
			required.merge(Items.EMERALD, emeraldFloor(enabled), Math::max);
		}

		// one inventory pass for every item requirement
		Map<Item, Integer> carried = new LinkedHashMap<>();
		int lecterns = 0;
		boolean axeCarried = false;
		boolean axeNearlyBroken = false;
		// every mechanism breaks the lectern at some point (task chain's break step,
		// guardian heal/break paths) — check the axe up front for all of them
		boolean needAxe = true;
		var inventory = player.getInventory();
		for (int i = 0; i < 37; i++) {
			ItemStack stack = i < 36 ? inventory.getItem(i) : player.getOffhandItem();
			if (stack.isEmpty()) continue;
			Item item = stack.getItem();
			// trades can only pay from the main inventory: count emeralds/books
			// there exclusively so precheck matches checkLockMaterials' view
			// (lecterns still count the offhand — the rig places from it)
			if (i < 36 && required.containsKey(item)) carried.merge(item, stack.getCount(), Integer::sum);
			else if (item == Items.LECTERN) lecterns += stack.getCount();
			// with preventAxeBreaking on, nearly-broken axes are unusable (the
			// strategies refuse them): don't count them, fail up front instead
			// of mid-run at the first lectern break
			else if (needAxe && !axeCarried && item instanceof AxeItem) {
				if (!config.preventAxeBreaking || !LecternRestock.axeNearlyBroken(stack)) axeCarried = true;
				else axeNearlyBroken = true;
			}
		}

		// name the missing material and the amount: "not enough materials" left the
		// player guessing whether to restock emeralds, books, a lectern or a tag
		for (Map.Entry<Item, Integer> entry : required.entrySet()) {
			int have = carried.getOrDefault(entry.getKey(), 0);
			if (have < entry.getValue()) {
				return Component.translatable("trademaster.message.material_short_item",
						itemName(entry.getKey()), entry.getValue(), have);
			}
		}

		if (lecterns < 1 && !LecternRestock.isLecternAt(client.level, lectern)) {
			return Component.translatable("trademaster.message.lectern_lost");
		}

		if (needAxe && !axeCarried) {
			// axe_present but rejected by the axe guard vs. no axe at all:
			// report the same wording the runtime strategies use (R20-7)
			return Component.translatable(axeNearlyBroken
					? "trademaster.message.axe_breaking"
					: "trademaster.message.axe_required");
		}

		return null;
	}

	/** The item's display name (an ItemStack is needed to resolve it, and this only
	 * runs on the failure path). */
	private static Component itemName(Item item) {
		return new ItemStack(item).getHoverName();
	}

	private static Component missing(String positionKey) {
		return Component.translatable("trademaster.message.precheck_failed",
				Component.translatable(positionKey));
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
			Identifier id = BuiltInRegistries.ITEM.getKey(cost.getItem());
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
				if (ItemStack.isSameItemSameComponents(stack, pending.get(slot))) {
					int take = Math.min(need[slot], remaining[i]);
					need[slot] -= take;
					remaining[i] -= take;
				}
			}
		}
		for (int slot = 0; slot < pending.size(); slot++) {
			if (need[slot] > 0) {
				return Component.translatable("trademaster.message.material_short_item",
						itemName(pending.get(slot).getItem()), pending.get(slot).getCount(),
						pending.get(slot).getCount() - need[slot]);
			}
		}
		return null;
	}
}
