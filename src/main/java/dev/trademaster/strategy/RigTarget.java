package dev.trademaster.strategy;

import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Single authority for the rig's CONFIGURED target: the job-site block the rig breaks,
 * re-places and restocks, the profession the villager should take, and the tool DERIVED
 * from the block.
 *
 * <p>Only the block id, the profession id and the non-tradeable profession list are
 * configured (the "Villagers" tab). The breaking tool is derived: the block's best tool among
 * the player's items (slots 0..35 plus the offhand, and only one that would actually DROP the
 * block). Ties are broken by remaining durability, then Unbreaking, then its level, and
 * finally a RANDOM pick that is then LOCKED so the mod never flip-flops between equal tools.
 * A block with no better-than-hand tool keeps the legacy behaviour (an axe is required); a
 * block that only drops for a tool the player does not carry is refused instead of being
 * destroyed for nothing. An unknown block id falls back to the lectern, so a bad config
 * cannot wedge the break/place loop.
 *
 * <p>NOTE: the matching / naming / deduction still assume the profession SELLS ENCHANTED
 * BOOKS. Only the id is configurable; the trade shape is not.
 */
public final class RigTarget {

	private RigTarget() {}

	/** Below this a stack is no better than the bare hand. */
	private static final float HAND_SPEED = 1.0f;

	// ---- block / item -------------------------------------------------------

	/** The configured job-site block, or the lectern when the id is unknown (an unknown
	 * block resolves to air, which would wedge the break/place loop). */
	public static Block workBlock() {
		ResourceLocation id = TradeMasterConfig.INSTANCE.workBlockIdentifier();
		Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
		return block == null || block == Blocks.AIR ? Blocks.LECTERN : block;
	}

	/** The block's item (what is restocked / placed), or null when the block has none. */
	public static @Nullable Item workItem() {
		Item item = workBlock().asItem();
		return item == Items.AIR ? null : item;
	}

	/** Whether the configured block sits at {@code pos}. */
	public static boolean isWorkBlockAt(BlockGetter level, BlockPos pos) {
		return level.getBlockState(pos).is(workBlock());
	}

	/** The block's state at {@code pos}, or the block's default state when absent (the
	 * tool selection still has something sensible to grade). */
	public static BlockState stateAt(BlockGetter level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.is(workBlock()) ? state : workBlock().defaultBlockState();
	}

	/** Whether {@code block} is a job site some villager profession can work at. */
	public static boolean isJobSiteBlock(Block block) {
		for (PoiType poi : matchingPois(block)) {
			Holder<PoiType> holder = BuiltInRegistries.POINT_OF_INTEREST_TYPE.wrapAsHolder(poi);
			for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
				if (profession.heldJobSite().test(holder)) return true;
			}
		}
		return false;
	}

	/** Whether {@code block} is a job site for THAT profession. {@link #isJobSiteBlock(Block)}
	 * only asks whether some profession uses it — a composter is a job site, but never for a
	 * librarian, and such a pairing makes the run wait for a profession for ever. */
	public static boolean isJobSiteBlockFor(Block block, ResourceKey<VillagerProfession> professionKey) {
		VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.get(professionKey)
				.map(holder -> holder.value()).orElse(null);
		if (profession == null) return false;
		for (PoiType poi : matchingPois(block)) {
			if (profession.heldJobSite()
					.test(BuiltInRegistries.POINT_OF_INTEREST_TYPE.wrapAsHolder(poi))) {
				return true;
			}
		}
		return false;
	}

	/** The point-of-interest types whose matching states include {@code block}. */
	private static List<PoiType> matchingPois(Block block) {
		List<PoiType> matched = new ArrayList<>();
		if (block == null || block == Blocks.AIR) return matched;
		for (PoiType poi : BuiltInRegistries.POINT_OF_INTEREST_TYPE) {
			for (BlockState state : poi.matchingStates()) {
				if (state.is(block)) { matched.add(poi); break; }
			}
		}
		return matched;
	}

	// ---- profession ---------------------------------------------------------

	/** The configured profession. A blank/garbage id degrades to the DEFAULT (librarian)
	 * rather than matching nothing — otherwise every employed villager would read as
	 * "another profession" and the rig would summon/dispatch in a loop. The same degradation
	 * applies to a profession that can never trade (a nitwit, or one listed as non-tradeable),
	 * which would otherwise make every villager unusable. A syntactically valid but UNKNOWN id
	 * (a typo) still yields that key, so {@link #professionKnown()} reports it and start()
	 * warns. */
	public static ResourceKey<VillagerProfession> professionKey() {
		ResourceLocation id = TradeMasterConfig.INSTANCE.professionIdentifier();
		if (id == null) return VillagerProfession.LIBRARIAN;
		ResourceKey<VillagerProfession> key = ResourceKey.create(Registries.VILLAGER_PROFESSION, id);
		return isNonTradeable(key) ? VillagerProfession.LIBRARIAN : key;
	}

	public static boolean professionKnown() {
		ResourceLocation id = TradeMasterConfig.INSTANCE.professionIdentifier();
		// VILLAGER_PROFESSION is a DefaultedRegistry: getValue() returns the DEFAULT (none)
		// for an unknown id and so can never report "unknown". getOptional does not default.
		return id != null && BuiltInRegistries.VILLAGER_PROFESSION.getOptional(id).isPresent();
	}

	/** Whether the CONFIGURED profession id is a non-tradeable one: {@link #professionKey()}
	 * then degrades to the librarian, and start() warns about it. */
	public static boolean professionConfiguredNonTradeable() {
		ResourceLocation id = TradeMasterConfig.INSTANCE.professionIdentifier();
		return id != null && isNonTradeable(ResourceKey.create(Registries.VILLAGER_PROFESSION, id));
	}

	/** A profession that can never sell the target trade: the built-in NITWIT plus every id
	 * listed in the Villagers tab. Such a villager is handled like a nitwit (dispatched as a
	 * blocker), and the list may not name the configured target itself. UNEMPLOYMENT is never
	 * non-tradeable: it is the very state that lets a villager TAKE the configured job, so
	 * listing it must not make the candidate unusable. */
	public static boolean isNonTradeable(ResourceKey<VillagerProfession> key) {
		if (key.equals(VillagerProfession.NONE)) return false;
		if (key.equals(VillagerProfession.NITWIT)) return true;
		for (String raw : TradeMasterConfig.INSTANCE.nonTradeableProfessions) {
			ResourceLocation id = TradeMasterConfig.parseId(raw);
			if (id != null && key.identifier().equals(id)) return true;
		}
		return false;
	}

	/** {@link #isNonTradeable(ResourceKey)} for a live holder (a stand-alone holder has no
	 * key and is never non-tradeable). */
	public static boolean isNonTradeable(Holder<VillagerProfession> profession) {
		ResourceKey<VillagerProfession> key = profession.unwrapKey().orElse(null);
		return key != null && isNonTradeable(key);
	}

	/** Whether the villager already has the CONFIGURED profession. */
	public static boolean isTarget(Holder<VillagerProfession> profession) {
		return profession.is(professionKey());
	}

	/** NITWIT, a configured non-tradeable profession, or employed in a profession other than
	 * the configured one. UNEMPLOYED is usable: it can still take the configured job at the
	 * work block. The EFFECTIVE target is always usable — even when its raw id was listed as
	 * non-tradeable and {@link #professionKey()} therefore degraded to the librarian —
	 * otherwise the very villager the rig wants would be dispatched as a blocker and nothing
	 * could ever lock. */
	public static boolean isUnusable(Holder<VillagerProfession> profession) {
		if (isTarget(profession)) return false;
		if (isNonTradeable(profession)) return true;
		if (profession.is(VillagerProfession.NONE)) return false;
		return true;
	}

	// ---- tool ---------------------------------------------------------------

	/** The tool currently locked in (its ITEM), and the block it was chosen for. */
	private static @Nullable Item lockedTool = null;
	private static @Nullable Block lockedBlock = null;

	public static void clearToolLock() {
		lockedTool = null;
		lockedBlock = null;
	}

	/** The block's best tool among {@code carried} (the tool pool: slots 0..35 plus the
	 * offhand). Only a stack that would actually break AND DROP the block is considered (a
	 * block needing the correct tool ignores one that would destroy it), and only one that
	 * beats the bare hand. With preventAxeBreaking on, a HEALTHY option is preferred over a
	 * nearly-broken faster one, so a tool is only kept while it can still be used — the mod
	 * then chains to the next one instead of stopping at the first nearly-broken tool.
	 * IGNORES the tie-break RNG: the material precheck and the runtime use this same
	 * function, so a start that passes cannot stop a tick later on a tie-break. Null when
	 * nothing can harvest the block. */
	public static @Nullable Item bestToolItem(BlockState state, List<ItemStack> carried) {
		Item bestItem = null;
		float best = HAND_SPEED;
		for (ItemStack stack : rankable(carried, state)) {
			float speed = stack.getDestroySpeed(state);
			if (speed > best) {
				best = speed;
				bestItem = stack.getItem();
			}
		}
		return bestItem;
	}

	/** {@link #bestToolItem(BlockState, List)} over the player's own items. */
	public static @Nullable Item bestToolItem(BlockState state, LocalPlayer player) {
		return bestToolItem(state, carriedStacks(player));
	}

	/** The tool to equip: {@link #bestToolItem} plus the tie-break order (remaining
	 * durability → Unbreaking → its level → random) and a LOCK so equal tools are not
	 * swapped every tick. The lock is released as soon as the locked item can no longer be
	 * used (destroyed — or, with the guard on, down to its last few points), so the mod
	 * moves on to the NEXT tool and only fails when none is left. It is cleared by
	 * {@link #clearToolLock()} at run start / when the block changes. */
	public static @Nullable Item chooseTool(BlockState state, List<ItemStack> carried, RandomSource random) {
		Block block = workBlock();
		if (lockedTool != null && lockedBlock == block && hasUsable(carried, lockedTool)) {
			return lockedTool;
		}
		Item chosen = pickTool(state, carried, random);
		lockedTool = chosen;
		lockedBlock = block;
		return chosen;
	}

	/** {@link #chooseTool(BlockState, List, RandomSource)} over the player's own items,
	 * with the player's RNG. */
	public static @Nullable Item chooseTool(BlockState state, LocalPlayer player) {
		return chooseTool(state, carriedStacks(player), player.getRandom());
	}

	/** The best tool with the tie-break RNG applied once (see {@link #chooseTool}). */
	private static @Nullable Item pickTool(BlockState state, List<ItemStack> carried, RandomSource random) {
		List<ItemStack> best = new ArrayList<>();
		float top = HAND_SPEED;
		for (ItemStack stack : rankable(carried, state)) {
			float speed = stack.getDestroySpeed(state);
			if (speed <= HAND_SPEED) continue;
			if (speed > top) {
				top = speed;
				best.clear();
				best.add(stack);
			} else if (speed == top) {
				best.add(stack);
			}
		}
		if (best.isEmpty()) return null;
		// tie-break: remaining durability first, then Unbreaking, then its level; among
		// the remaining ties pick ONE at random (the caller then LOCKS it).
		List<ItemStack> winners = new ArrayList<>();
		int bestRemaining = -1;
		int bestUnbreaking = -1;
		for (ItemStack stack : best) {
			int remaining = remainingDurability(stack);
			int unbreaking = unbreakingLevel(stack);
			if (remaining > bestRemaining
					|| (remaining == bestRemaining && unbreaking > bestUnbreaking)) {
				bestRemaining = remaining;
				bestUnbreaking = unbreaking;
				winners.clear();
				winners.add(stack);
			} else if (remaining == bestRemaining && unbreaking == bestUnbreaking) {
				winners.add(stack);
			}
		}
		return winners.get(random.nextInt(winners.size())).getItem();
	}

	/** The player's raw tool pool: slots 0..35 plus the offhand (empties dropped). */
	private static List<ItemStack> carriedStacks(LocalPlayer player) {
		List<ItemStack> carried = new ArrayList<>(37);
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty()) carried.add(stack);
		}
		ItemStack offhand = player.getOffhandItem();
		if (!offhand.isEmpty()) carried.add(offhand);
		return carried;
	}

	/** The tool candidates for {@code state}: the carried stacks that could actually break AND
	 * drop it (i.e. not a stack that would destroy the block without a drop) and that beat the
	 * bare hand. With preventAxeBreaking on they are further narrowed to the HEALTHY ones — but
	 * only when at least one healthy option exists, so a nearly-broken tool is still used as a
	 * last resort (and chains to the next one afterwards). Narrowing here, not on the whole
	 * inventory, keeps unrelated items (a stick, paper) from suppressing that last resort. */
	private static List<ItemStack> rankable(List<ItemStack> carried, BlockState state) {
		List<ItemStack> tools = new ArrayList<>();
		for (ItemStack stack : carried) {
			if (!canHarvest(stack, state)) continue;
			if (stack.getDestroySpeed(state) <= HAND_SPEED) continue;
			tools.add(stack);
		}
		if (!TradeMasterConfig.INSTANCE.preventAxeBreaking) return tools;
		List<ItemStack> healthy = new ArrayList<>();
		for (ItemStack stack : tools) {
			if (!isNearlyBroken(stack)) healthy.add(stack);
		}
		return healthy.isEmpty() ? tools : healthy;
	}

	/** Whether a copy of {@code item} the tool may still use is carried: any copy, or (with
	 * the guard on) at least one that is not nearly broken. */
	private static boolean hasUsable(List<ItemStack> carried, Item item) {
		boolean any = false;
		boolean healthy = !TradeMasterConfig.INSTANCE.preventAxeBreaking;
		for (ItemStack stack : carried) {
			if (stack.getItem() != item) continue;
			any = true;
			if (!isNearlyBroken(stack)) healthy = true;
		}
		return any && healthy;
	}

	/** Whether a stack is down to the last few durability points (the preventAxeBreaking
	 * threshold, 5 as before). Non-damageable stacks are never nearly broken. */
	public static boolean isNearlyBroken(ItemStack stack) {
		return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 5;
	}

	/** The Unbreaking level of a stack, 0 when it has none. Read from the stack's own
	 * enchantment component, so it needs no registry lookup. */
	public static int unbreakingLevel(ItemStack stack) {
		for (var entry : stack.getEnchantments().entrySet()) {
			if (entry.getKey().unwrapKey().map(key -> key.equals(Enchantments.UNBREAKING)).orElse(false)) {
				return entry.getIntValue();
			}
		}
		return 0;
	}

	/** How much durability is left, 0 for a non-damageable stack. */
	public static int remainingDurability(ItemStack stack) {
		if (!stack.isDamageableItem()) return 0;
		return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
	}

	/** Whether breaking the block with this stack would actually DROP it. A block that needs
	 * the correct tool only drops for a tool whose own rules allow it (e.g. a golden pickaxe
	 * is fast but drops nothing from obsidian); every other block drops for anything. */
	private static boolean canHarvest(ItemStack stack, BlockState state) {
		return !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
	}

	/** Whether the block needs the correct tool and NO carried stack is one: breaking it then
	 * would destroy the work block without a replacement, so the run must stop instead of
	 * falling back to the legacy axe. */
	public static boolean cannotHarvest(BlockState state, List<ItemStack> carried) {
		if (!state.requiresCorrectToolForDrops()) return false;
		for (ItemStack stack : carried) {
			if (stack.isCorrectToolForDrops(state)) return false;
		}
		return true;
	}

	/** {@link #cannotHarvest(BlockState, List)} over the player's own items. */
	public static boolean cannotHarvest(BlockState state, LocalPlayer player) {
		return cannotHarvest(state, carriedStacks(player));
	}

	/** The message key for "no usable tool". A block that can only be harvested with a tool
	 * the player does not carry gets its own wording: the legacy axe hint would be wrong (and
	 * an axe would destroy it without a drop). */
	public static String toolRequiredKey(BlockState state, List<ItemStack> carried) {
		if (cannotHarvest(state, carried)) return "trademaster.message.tool_cannot_harvest";
		return bestToolItem(state, carried) != null
				? "trademaster.message.tool_required"
				: "trademaster.message.axe_required";
	}

	/** {@link #toolRequiredKey(BlockState, List)} over the player's own items. */
	public static String toolRequiredKey(BlockState state, LocalPlayer player) {
		return toolRequiredKey(state, carriedStacks(player));
	}

	/** The message key for "the chosen tool is nearly broken" (same split as above). */
	public static String toolBreakingKey(BlockState state, List<ItemStack> carried) {
		// a nearly-broken tool means one WAS chosen, so the cannot-harvest case cannot apply
		return bestToolItem(state, carried) != null
				? "trademaster.message.tool_breaking"
				: "trademaster.message.axe_breaking";
	}

	/** {@link #toolBreakingKey(BlockState, List)} over the player's own items. */
	public static String toolBreakingKey(BlockState state, LocalPlayer player) {
		return toolBreakingKey(state, carriedStacks(player));
	}
}
