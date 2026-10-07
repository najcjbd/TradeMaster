package dev.trademaster.compat;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * Version-gated Registry accessors.
 *
 * <p>{@code getOptional(ResourceLocation)} exists on every supported version and is the
 * universal base for reading a value. Tag membership is tested through the tag's own
 * holder set, which avoids building a Holder by hand (that differs per era, and
 * {@code Holder.direct} loses tag membership anyway).
 *
 * <p>Era notes (verified against each version's mapped jar):
 * <ul>
 *   <li>{@code Registry.get(ResourceLocation)} returns the value on &lt;=1.21.1 and is
 *       renamed to {@code getValue} by 1.21.8.</li>
 *   <li>{@code Registry.getTag} (Optional&lt;HolderSet&gt;) is renamed to
 *       {@code getTagOrEmpty} (a direct Iterable&lt;Holder&gt;) by 1.21.4.</li>
 *   <li>{@code ResourceKey.location} is renamed to {@code identifier} at 26.1.</li>
 * </ul>
 */
public final class McRegistry {

	/** The registry value for {@code id}, or null. Universal across versions. */
	public static <T> T value(Registry<T> registry, ResourceLocation id) {
		return registry.getOptional(id).orElse(null);
	}

	/** True when {@code id} is a member of {@code tag}. */
	public static <T> boolean isInTag(Registry<T> registry, TagKey<T> tag, ResourceLocation id) {
		//? if >=1.21.2 {
		for (net.minecraft.core.Holder<T> holder : registry.getTagOrEmpty(tag)) {
			if (holder.unwrapKey().map(key -> keyId(key).equals(id)).orElse(false)) return true;
		}
		return false;
		//?} else {
		/*return registry.getTag(tag)
				.map(set -> set.stream().anyMatch(holder ->
						holder.unwrapKey().map(key -> keyId(key).equals(id)).orElse(false)))
				.orElse(false);*/
		//?}
	}

	/** A key's id: {@code ResourceKey.location()} on &lt;=1.21.8, {@code identifier()} on
	 * 26.1+ (the unobfuscation rename). */
	private static ResourceLocation keyId(ResourceKey<?> key) {
		//? if >=26.1 {
		/*return key.identifier();*/
		//?} else {
		return key.location();
		//?}
	}

	private McRegistry() {}
}
