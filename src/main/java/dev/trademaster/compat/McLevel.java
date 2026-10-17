package dev.trademaster.compat;

import net.minecraft.world.level.LevelHeightAccessor;

/** Version-gated level queries. 1.21.2 renamed {@code getMaxBuildHeight()} to
 * {@code getMaxY()} (on LevelHeightAccessor). */
public final class McLevel {

	/** The build-height limit (exclusive top). */
	public static int maxY(LevelHeightAccessor level) {
		//? if >=1.21.2 {
		return level.getMaxY();
		//?} else {
		/*return level.getMaxBuildHeight();*/
		//?}
	}

	private McLevel() {}
}
