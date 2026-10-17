package dev.trademaster.compat;

import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Version-gated point-of-interest checks. 1.19 replaced the {@code PoiType.is(BlockState)}
 * predicate with a {@code matchingStates()} set. */
public final class McPoi {

	/** Whether some state of {@code block} is handled by {@code poi}. */
	public static boolean matchesBlock(PoiType poi, Block block) {
		//? if >=1.19 {
		for (BlockState state : poi.matchingStates()) {
			if (state.is(block)) return true;
		}
		return false;
		//?} else {
		/*for (BlockState state : block.getStateDefinition().getPossibleStates()) {
			if (poi.is(state)) return true;
		}
		return false;*/
		//?}
	}

	private McPoi() {}
}
