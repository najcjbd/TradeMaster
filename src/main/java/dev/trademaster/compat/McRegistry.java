package dev.trademaster.compat;


import dev.trademaster.compat.McBuiltIn;import net.minecraft.core.Registry;
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

	/** The registry value for {@code key}, or null. 1.21.1's {@code get(ResourceKey)}
	 * returns the value directly; 1.21.2+ returns an Optional&lt;Holder&gt;. */
	public static <T> T value(Registry<T> registry, ResourceKey<T> key) {
		//? if >=1.21.2 {
		return registry.get(key).map(net.minecraft.core.Holder::value).orElse(null);
		//?} else {
		/*return registry.get(key);*/
		//?}
	}

	/** Wraps a registry value as a Holder. {@code Registry.wrapAsHolder} exists from 1.19.3;
	 * before that {@code Holder.direct} is used (losing tag membership, which the pre-1.21
	 * callers do not rely on). */
	public static <T> net.minecraft.core.Holder<T> wrap(Registry<T> registry, T value) {
		//? if >=1.19.3 {
		return registry.wrapAsHolder(value);
		//?} else {
		/*return net.minecraft.core.Holder.direct(value);*/
		//?}
	}

	/** The Holder for {@code id}, or null. 1.21.1's {@code get(ResourceLocation)} returns
	 * the value directly (wrapped here); 1.21.2+ returns an Optional&lt;Holder&gt;. */
	public static <T> net.minecraft.core.Holder<T> holder(Registry<T> registry, ResourceLocation id) {
		//? if >=1.21.2 {
		return registry.get(id).orElse(null);
		//?} else {
		/*T value = registry.get(id);
		return value == null ? null : wrap(registry, value);*/
		//?}
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
	 * 26.1+ (the unobfuscation rename). Public so shared code never names the accessor. */
	public static ResourceLocation keyId(ResourceKey<?> key) {
		//? if >=26.1 {
		/*return key.identifier();*/
		//?} else {
		return key.location();
		//?}
	}

	/** The live enchantment registry. It is a datapack registry from 1.19.3 (looked up
	 * from the level); before that it was the static {@code Registry.ENCHANTMENT}. The
	 * lookup method is renamed registryOrThrow -> lookupOrThrow by 1.21.8. */
	public static Registry<net.minecraft.world.item.enchantment.Enchantment> enchantments(
			net.minecraft.world.level.Level level) {
		//? if >=1.21.2 {
		return level.registryAccess()
				.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
		//?} elif >=1.19.3 {
		/*return level.registryAccess()
				.registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);*/
		//?} else {
		/*return net.minecraft.core.Registry.ENCHANTMENT;*/
		//?}
	}

	private McRegistry() {}
}
