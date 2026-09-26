package dev.trademaster.exec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.trademaster.TradeMaster;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves display names for enchantments. Resolution order:
 * 1. player-defined custom names (config)
 * 2. the client language file (zh_cn clients already get Chinese)
 * 3. bundled fallback table assets/trademaster/enchant_names_zh.json
 * 4. the short enchantment id
 */
public final class EnchantmentNamer {

	private static final Map<String, String> FALLBACK_ZH = new LinkedHashMap<>();
	private static boolean fallbackLoaded = false;
	/** Load attempts made so far: the table is retried on failure, but only a few
	 * times (see loadFallback) so a missing resource cannot spam the log. */
	private static int fallbackAttempts = 0;
	/** Level the counters above belong to: a world change resets them. Held weakly so
	 * leaving the world does not keep the whole ClientLevel (chunks, entities) alive. */
	private static java.lang.ref.WeakReference<Object> lastLevel = new java.lang.ref.WeakReference<>(null);

	private EnchantmentNamer() {}

	/** The effective display name of any demand id: a custom good's item name (which
	 * honours the alias map) or an enchantment's name. */
	public static String demandDisplayName(Identifier id) {
		GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(id);
		if (good != null) return good.displayName();
		return displayName(id, "enchantment." + id.getNamespace() + "." + id.getPath());
	}

	public static String displayName(Identifier id, String translationKey) {
		return sanitize(resolve(id, translationKey));
	}

	/** The name sanitizer, exposed for the alias editor: a candidate name must be
	 * compared exactly as the naming path generates it ('+' -> '*', digits ->
	 * superscripts), or two aliases differing only in the raw text would still
	 * produce the same villager name and make both sides undeductable. */
	public static String sanitizeAlias(String name) {
		return sanitize(name);
	}

	/** Collapses runs of whitespace. The deduction parser (TradeMatcher) rebuilds
	 * villager names from tokens joined by single spaces, so EVERY name compared
	 * against it must be canonicalised the same way — the search index keys and the
	 * alias-collision check alike. Without this, a name carrying two consecutive
	 * spaces never matches, and a collision that differs only in whitespace is
	 * silently missed at alias-entry time but then drops BOTH sides from the index. */
	public static String canonical(String name) {
		return name.trim().replaceAll("\\s+", " ");
	}

	private static String resolve(Identifier id, String translationKey) {
		String custom = TradeMasterConfig.INSTANCE.customEnchantNames.get(id);
		if (custom != null) {
			// villager names are parsed with trim(): an untrimmed custom name
			// would never match its own generated villager name back
			custom = custom.trim();
			if (!custom.isEmpty()) return custom;
		}

		// Defensive, unreachable today (audited 2026-09-19): both call sites build a
		// non-null key before calling in.
		if (translationKey != null && Language.getInstance().has(translationKey)) {
			String localized = Language.getInstance().getOrDefault(translationKey);
			if (!localized.equals(translationKey)) return localized;
		}

		loadFallback();
		String zh = FALLBACK_ZH.get(id.toString());
		if (zh != null) return zh;

		return id.getPath();
	}

	private static final char[] SUPERSCRIPT_DIGITS = {'⁰', '¹', '²', '³', '⁴', '⁵', '⁶', '⁷', '⁸', '⁹'};

	/** Two cleanups keep the villager naming format parseable:
	 * 1. '+' is the part separator ("A I+B II 12") — a display name carrying a
	 *    literal '+' would be split apart by the deduction parser and never
	 *    resolve back to this enchantment. Replace it with '*'.
	 * 2. The parser keys books off bare integer tokens (the prices) — a name
	 *    like "Power 2" would make the "2" look like a price and destroy the
	 *    whole villager name. Digits become unicode superscripts instead:
	 *    never a price token, never a level character, and both the naming and
	 *    the deduction sides resolve through this same method, so they stay
	 *    consistent. */
	private static String sanitize(String name) {
		boolean dirty = false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '+' || (c >= '0' && c <= '9')) {
				dirty = true;
				break;
			}
		}
		if (!dirty) return name;
		StringBuilder sb = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '+') sb.append('*');
			else if (c >= '0' && c <= '9') sb.append(SUPERSCRIPT_DIGITS[c - '0']);
			else sb.append(c);
		}
		return sb.toString();
	}

	public static String romanNumerals(int level) {
		String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
		String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
		String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
		if (level <= 0) return String.valueOf(level);
		// beyond MMM the roman form would silently truncate and no longer
		// round-trip through romanToInt — fall back to decimal
		if (level > 3999) return String.valueOf(level);
		StringBuilder sb = new StringBuilder();
		// up to MMM (3999), matching what romanToInt can parse back
		sb.append("M".repeat(Math.min(level / 1000, 3)));
		sb.append(hundreds[(level % 1000) / 100]);
		sb.append(tens[(level % 100) / 10]);
		sb.append(ones[level % 10]);
		return sb.toString();
	}

	private static void loadFallback() {
		// A world change / resource reload gets a fresh chance: the attempts counter is
		// process-wide, so without this a few failures at startup disabled the Chinese
		// fallback table for the rest of the session.
		Object level = Minecraft.getInstance().level;
		if (level != lastLevel.get()) {
			lastLevel = new java.lang.ref.WeakReference<>(level);
			fallbackAttempts = 0;
			fallbackLoaded = false;
			FALLBACK_ZH.clear();
		}
		// retry a few times instead of never: setting the flag before the load meant a
		// single failure (resource packing, reload timing) silently disabled the
		// Chinese fallback table forever. Bounded so a permanently missing resource
		// cannot spam the log from the per-frame displayName path.
		if (fallbackLoaded || fallbackAttempts >= 3) return;
		fallbackAttempts++;
		try (InputStream in = Minecraft.getInstance().getResourceManager()
				// the file lives under assets/trademaster/, not the "trade-master"
				// mod-id namespace — the two must not be conflated
				.open(Identifier.fromNamespaceAndPath("trademaster", "enchant_names_zh.json"))) {
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
				FALLBACK_ZH.put(entry.getKey(), entry.getValue().getAsString());
			}
			fallbackLoaded = true;
		} catch (Exception e) {
			TradeMaster.LOGGER.warn("Could not load enchant_names_zh.json (attempt {}): {}",
					fallbackAttempts, e.getMessage());
		}
	}
}
