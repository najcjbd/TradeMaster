package dev.trademaster.config;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The structured enchantment filter syntax shared by the editor and the config.
 *
 * <p>Levels and ranges (0 means "not present"; an open upper bound means no limit):
 * <ul>
 *   <li>{@code id} / {@code id:1..} / {@code id:..} / {@code id:0..} — present at any level</li>
 *   <li>{@code id:0} — absent</li>
 *   <li>{@code id:N} — exactly N</li>
 *   <li>{@code id:N..} — N or above</li>
 *   <li>{@code id:..M} / {@code id:0..M} — present and at most M</li>
 *   <li>{@code id:N..M} — N through M inclusive</li>
 *   <li>{@code !id...} — the same range in the exclude list</li>
 * </ul>
 * The lower 0 in a range is the "present" sentinel (0.. → 1..); a bare {@code id:0}
 * is absence. Duplicate ids and include/exclude overlaps are invalid.
 */
public final class GoodEnchantFilter {

	public record Parsed(List<GoodSpec.GoodEnchant> include, List<GoodSpec.GoodEnchant> exclude) {}

	/** Parses the editor's comma/space separated text. Returns null on any bad token,
	 * duplicate id, or include/exclude overlap. */
	public static @Nullable Parsed parse(String raw) {
		LinkedHashMap<ResourceLocation, GoodSpec.GoodEnchant> include = new LinkedHashMap<>();
		LinkedHashMap<ResourceLocation, GoodSpec.GoodEnchant> exclude = new LinkedHashMap<>();
		if (raw != null) {
			for (String token : raw.split("[,;\\s]+")) {
				if (token.isBlank()) continue;
				boolean negated = token.startsWith("!");
				GoodSpec.GoodEnchant enchantment = parseToken(token, negated);
				if (enchantment == null) return null;
				if (negated) {
					if (include.containsKey(enchantment.id())
							|| exclude.putIfAbsent(enchantment.id(), enchantment) != null) return null;
				} else {
					if (exclude.containsKey(enchantment.id())
							|| include.putIfAbsent(enchantment.id(), enchantment) != null) return null;
				}
			}
		}
		return new Parsed(List.copyOf(include.values()), List.copyOf(exclude.values()));
	}

	/** Parses one token. {@code excludeField} allows an optional leading {@code !}
	 * (the config's exclude array); in an include array a leading {@code !} is invalid. */
	public static @Nullable GoodSpec.GoodEnchant parseToken(String raw, boolean excludeField) {
		String text = raw == null ? "" : raw.trim();
		if (text.isEmpty()) return null;
		boolean negated = text.startsWith("!");
		if (negated) {
			if (!excludeField) return null;
			text = text.substring(1);
		}
		String idText = text;
		String spec = null;
		int split = idText.lastIndexOf(':');
		if (split > 0) {
			String suffix = idText.substring(split + 1);
			// a suffix starting with a digit or dots is a level/range; a word suffix is
			// part of the id (e.g. "minecraft:sharpness")
			if (!suffix.isEmpty() && (Character.isDigit(suffix.charAt(0))
					|| suffix.startsWith("..") || suffix.startsWith("-") || suffix.startsWith("+"))) {
				spec = suffix;
				idText = idText.substring(0, split);
			}
		}
		ResourceLocation id = TradeMasterConfig.parseId(idText);
		if (id == null) return null;
		if (spec == null) return GoodSpec.GoodEnchant.present(id);
		return parseRange(id, spec);
	}

	private static @Nullable GoodSpec.GoodEnchant parseRange(ResourceLocation id, String spec) {
		if (spec == null || spec.isEmpty()) return null;
		int dots = spec.indexOf("..");
		if (dots < 0) {
			Integer level = parseLevel(spec);
			if (level == null) return null;
			return level == 0 ? GoodSpec.GoodEnchant.absent(id)
					: GoodSpec.GoodEnchant.exact(id, level);
		}
		if (spec.indexOf("..", dots + 2) >= 0) return null;
		String lowText = spec.substring(0, dots);
		String highText = spec.substring(dots + 2);
		Integer low = lowText.isEmpty() ? 1 : parseLevel(lowText);
		Integer high = highText.isEmpty() ? 0 : parseLevel(highText);
		if (low == null || high == null) return null;
		// a lower 0 in a range is the "present" sentinel (0.. → 1..)
		int min = low == 0 ? 1 : low;
		if (min < 1) return null;
		if (high > 0 && high < min) return null;
		return GoodSpec.GoodEnchant.range(id, min, high);
	}

	private static @Nullable Integer parseLevel(String text) {
		if (text == null || text.isEmpty() || text.length() > 3) return null;
		try {
			int value = Integer.parseInt(text);
			return value >= 0 && value <= 99 ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Formats one token (used by the config's array writer). */
	public static String token(GoodSpec.GoodEnchant enchantment, boolean exclude) {
		StringBuilder text = new StringBuilder();
		append(text, enchantment, exclude);
		return text.toString();
	}

	/** Formats include/exclude lists back into the editor text. */
	public static String format(List<GoodSpec.GoodEnchant> include,
			List<GoodSpec.GoodEnchant> exclude) {
		StringBuilder text = new StringBuilder();
		for (GoodSpec.GoodEnchant enchantment : include) append(text, enchantment, false);
		for (GoodSpec.GoodEnchant enchantment : exclude) append(text, enchantment, true);
		return text.toString();
	}

	private static void append(StringBuilder text, GoodSpec.GoodEnchant enchantment, boolean exclude) {
		if (!text.isEmpty()) text.append(", ");
		if (exclude) text.append('!');
		text.append(enchantment.id());
		if (enchantment.absent()) {
			text.append(":0");
		} else if (enchantment.minLevel() == enchantment.maxLevel() && enchantment.maxLevel() > 0) {
			text.append(':').append(enchantment.minLevel());
		} else if (enchantment.minLevel() > 1 || enchantment.maxLevel() > 0) {
			text.append(':');
			if (enchantment.minLevel() > 1 || enchantment.maxLevel() == 0) {
				text.append(enchantment.minLevel());
			}
			if (enchantment.maxLevel() == 0) {
				text.append("..");
			} else {
				text.append("..").append(enchantment.maxLevel());
			}
		}
	}

	private GoodEnchantFilter() {}
}
