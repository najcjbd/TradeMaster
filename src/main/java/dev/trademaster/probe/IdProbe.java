package dev.trademaster.probe;

/**
 * Proof-of-concept for the global {@code ResourceLocation -> Identifier} rename.
 * The source references {@code ResourceLocation} (the name on <=1.21.8); the Stonecutter
 * replacement in the build script rewrites it to {@code Identifier} on 26.1+.
 */
public final class IdProbe {

	public static Class<?> idClass() {
		return net.minecraft.resources.ResourceLocation.class;
	}

	private IdProbe() {}
}
