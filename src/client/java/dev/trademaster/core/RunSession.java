package dev.trademaster.core;

import dev.trademaster.anticheat.MovementGuard;
import dev.trademaster.matching.TradeMatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
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

	public @Nullable Villager villager = null;
	public int tries = 0;
	public @Nullable TradeMatcher.MatchedTrade matchedTrade = null;

	public RunSession(BlockPos lecternPos, BlockPos summonButtonPos, BlockPos sendAwayButtonPos) {
		this.lecternPos = lecternPos.immutable();
		this.summonButtonPos = summonButtonPos.immutable();
		this.sendAwayButtonPos = sendAwayButtonPos.immutable();
	}
}
