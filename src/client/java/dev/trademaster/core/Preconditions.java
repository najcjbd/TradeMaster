package dev.trademaster.core;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.strategy.LecternRestock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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
		if (enabled.isEmpty()) {
			return Component.translatable("trademaster.message.no_enchantments");
		}

		// one inventory pass for every item requirement
		int emeralds = 0, books = 0, lecterns = 0;
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
			if (i < 36 && item == Items.EMERALD) emeralds += stack.getCount();
			else if (i < 36 && item == Items.BOOK) books += stack.getCount();
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
		if (emeralds < emeraldFloor(enabled)) {
			return Component.translatable("trademaster.message.material_short_emeralds", emeraldFloor(enabled));
		}
		if (books < 1) {
			return Component.translatable("trademaster.message.material_short_books");
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

	/** Re-checks lock materials right before executing the lock trade. */
	public static boolean checkLockMaterials(LocalPlayer player, ItemStack costA, ItemStack costB) {
		int needA = costA.getCount();
		int needB = costB.getCount();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (needA > 0 && ItemStack.isSameItemSameComponents(stack, costA)) {
				int take = Math.min(needA, stack.getCount());
				needA -= take;
			}
			if (needB > 0 && ItemStack.isSameItemSameComponents(stack, costB)) {
				int take = Math.min(needB, stack.getCount());
				needB -= take;
			}
		}
		return needA <= 0 && needB <= 0;
	}
}
