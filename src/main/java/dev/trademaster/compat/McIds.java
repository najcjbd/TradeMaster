package dev.trademaster.compat;

import net.minecraft.resources.ResourceLocation;

/**
 * Version-gated helpers for the resource-location class.
 *
 * <p>The class NAME (ResourceLocation vs Identifier from 26.1) is handled globally by a
 * Stonecutter replacement, so the source just writes {@code ResourceLocation}. Its
 * FACTORY method, however, differs: 1.21+ has {@code withDefaultNamespace(path)}, while
 * &lt;=1.20.1 needs the {@code (namespace, path)} constructor.
 *
 * <p>Note the Stonecutter branch form: the branch that is ACTIVE on the default target
 * (>=1.21) is plain code; the other branch is stored inside a block comment, which
 * Stonecutter uncomments for the targets where it applies.
 */
public final class McIds {

	//? if >=1.21 {
	public static ResourceLocation mc(String path) {
		return ResourceLocation.withDefaultNamespace(path);
	}
	//?} else {
	/*public static ResourceLocation mc(String path) {
		return new ResourceLocation("minecraft", path);
	}*/
	//?}

	//? if >=1.21 {
	public static ResourceLocation of(String namespace, String path) {
		return ResourceLocation.fromNamespaceAndPath(namespace, path);
	}
	//?} else {
	/*public static ResourceLocation of(String namespace, String path) {
		return new ResourceLocation(namespace, path);
	}*/
	//?}

	/** Parse a full "namespace:path" id, or null. */
	public static ResourceLocation tryParse(String text) {
		return ResourceLocation.tryParse(text);
	}

	private McIds() {}
}
