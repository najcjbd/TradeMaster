package dev.trademaster.compat;

import net.minecraft.core.Registry;
import net.minecraft.world.item.Item;

/**
 * Version-gated access to a built-in (static) registry.
 *
 * <p>{@code net.minecraft.core.registries.BuiltInRegistries} is absent before 1.19.3,
 * where the built-in registries were static fields on {@code net.minecraft.core.Registry}.
 */
public final class McBuiltIn {

	/** The built-in item registry. */
	public static Registry<Item> items() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM;
		//?} else {
		/*return net.minecraft.core.Registry.ITEM;*/
		//?}
	}

	private McBuiltIn() {}
}
