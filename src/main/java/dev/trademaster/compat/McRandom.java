package dev.trademaster.compat;

/** Version-gated RNG bridge: {@code LivingEntity.getRandom()} returns a {@code RandomSource}
 * from 1.19 and a {@code java.util.Random} before it. */
public final class McRandom {

	public static int nextInt(Object random, int bound) {
		//? if >=1.19 {
		return ((net.minecraft.util.RandomSource) random).nextInt(bound);
		//?} else {
		/*return ((java.util.Random) random).nextInt(bound);*/
		//?}
	}

	private McRandom() {}
}
