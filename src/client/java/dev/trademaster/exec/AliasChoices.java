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
		for (Map.Entry<String, Identifier> entry : raw.entrySet()) {
			String key = entry.getKey();
			Identifier id = entry.getValue();
			if (key == null || key.isBlank() || id == null) continue;
			out.putIfAbsent(EnchantmentNamer.canonical(key), id);
			out.putIfAbsent(EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(key)), id);
		}
		return out;
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
