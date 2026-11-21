package dev.trademaster.core;

import dev.trademaster.anticheat.MovementGuard;
import dev.trademaster.matching.TradeMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Mutable context for one pipeline run. */
public class RunSession {

	public final BlockPos lecternPos;
	public final BlockPos summonButtonPos;
	public final BlockPos sendAwayButtonPos;
	public final MovementGuard movementGuard = new MovementGuard();
	public final TradeMatcher matcher = new TradeMatcher();
	/** Enchantments locked during this run: the needs rebuild after each dispatch
	 * must not re-demand them, even when naming/auto-disable left no voucher. */
	public final Set<Identifier> lockedIds = new HashSet<>();

	/** How many of each configured good's result item the player carried when the run
	 * started. Disposal only ever touches the EXCESS over this, so a player's own items
	 * that happen to match a good's result are neither stored nor dropped. */
	public final Map<Item, Integer> goodBaseline = new HashMap<>();

	/** Every distinct stack (item AND components) a locked trade has yielded, per result
	 * item: the disposal takes a good only when the stack IS one of this run's outputs,
	 * never a component-distinct copy the player already carried. A LIST because the same
	 * good can be locked more than once, and the server may re-roll the components. */
	public final Map<Item, java.util.List<net.minecraft.world.item.ItemStack>> lockedResults = new HashMap<>();

	public @Nullable Villager villager = null;
	public int tries = 0;
	public @Nullable TradeMatcher.MatchedTrade matchedTrade = null;
	/** Whether the current match's unproven purchase was already vouched: a second
	 * voucher (e.g. a forced stop after a lock failure) would double the expected
	 * produced count and make the depositor take the player's own copies. */
	public boolean unprovenPurchaseVouchered = false;
	/** Whether an in-flight unproven purchase was already carried into the next run. */
	public boolean unprovenCarryOverCaptured = false;

	public RunSession(BlockPos lecternPos, BlockPos summonButtonPos, BlockPos sendAwayButtonPos) {
		this.lecternPos = lecternPos.immutable();
		this.summonButtonPos = summonButtonPos.immutable();
		this.sendAwayButtonPos = sendAwayButtonPos.immutable();
	}
}
