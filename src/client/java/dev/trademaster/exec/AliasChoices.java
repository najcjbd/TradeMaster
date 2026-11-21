package dev.trademaster.exec;

import net.minecraft.resources.Identifier;
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

	public static Map<String, Identifier> canonical(Map<String, Identifier> raw) {
		Map<String, Identifier> out = new HashMap<>();
		if (raw == null) return out;
		java.util.Set<String> ambiguous = new java.util.HashSet<>();
		for (Map.Entry<String, Identifier> entry : raw.entrySet()) {
			String key = entry.getKey();
			Identifier id = entry.getValue();
			if (key == null || key.isBlank() || id == null) continue;
			put(out, ambiguous, EnchantmentNamer.canonical(key), id);
			put(out, ambiguous, EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(key)), id);
		}
		return out;
	}

	private static void put(Map<String, Identifier> out, java.util.Set<String> ambiguous,
			String key, Identifier id) {
		if (ambiguous.contains(key)) return;
		Identifier existing = out.get(key);
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
	public static @Nullable Identifier resolve(String name, Map<String, Identifier> raw) {
		if (name == null || raw == null || raw.isEmpty()) return null;
		Map<String, Identifier> canonical = canonical(raw);
		Identifier id = canonical.get(EnchantmentNamer.canonical(name));
		return id != null ? id : canonical.get(
				EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(name)));
	}

	private AliasChoices() {}
}
