package dev.trademaster.exec;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the manual "who owns this ambiguous deduction name" choices from
 * {@code naming.aliasChoices}. The deduction index keys aliases/display names after
 * {@link EnchantmentNamer#sanitizeAlias} + {@link EnchantmentNamer#canonical}, while
 * registry paths and recorded history names use the canonical raw text; both forms
 * are accepted here so a hand-written choice matches either source.
 */
public final class AliasChoices {

	public static Map<String, ResourceLocation> canonical(Map<String, ResourceLocation> raw) {
		Map<String, ResourceLocation> out = new HashMap<>();
		if (raw == null) return out;
		java.util.Set<String> ambiguous = new java.util.HashSet<>();
		for (Map.Entry<String, ResourceLocation> entry : raw.entrySet()) {
			String key = entry.getKey();
			ResourceLocation id = entry.getValue();
			if (key == null || key.isBlank() || id == null) continue;
			put(out, ambiguous, EnchantmentNamer.canonical(key), id);
			put(out, ambiguous, EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(key)), id);
		}
		return out;
	}

	private static void put(Map<String, ResourceLocation> out, java.util.Set<String> ambiguous,
			String key, ResourceLocation id) {
		if (ambiguous.contains(key)) return;
		ResourceLocation existing = out.get(key);
		if (existing != null && !existing.equals(id)) {
			// two hand-written keys normalize to the same name with different owners:
			// the choice is ambiguous, so leave it unset (the index then warns) rather
			// than silently letting the first entry win. Remember it so a later key
			// with one of the two owners cannot silently re-add it.
			ambiguous.add(key);
			out.remove(key);
			return;
		}
		out.put(key, id);
	}

	/** The chosen owner for a raw display/history name, or null when unset. */
	public static @Nullable ResourceLocation resolve(String name, Map<String, ResourceLocation> raw) {
		if (name == null || raw == null || raw.isEmpty()) return null;
		Map<String, ResourceLocation> canonical = canonical(raw);
		ResourceLocation id = canonical.get(EnchantmentNamer.canonical(name));
		return id != null ? id : canonical.get(
				EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(name)));
	}

	private AliasChoices() {}
}
