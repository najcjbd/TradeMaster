package dev.trademaster.config;

import java.util.Locale;

/** Parses and formats durations in ticks, e.g. "1h30min", "90s", "3600". */
public final class DurationParser {

	private DurationParser() {}

	/**
	 * Accepts plain tick counts and unit suffixes: t/tick(s), s/sec(s)/second(s),
	 * m/min(s)/minute(s), h/hr(s)/hour(s); compound forms like "1h30m" work too.
	 * @return ticks, or -1 when the text cannot be parsed.
	 */
	public static int parse(String text) {
		if (text == null) return -1;
		String s = text.trim().toLowerCase(Locale.ROOT).replace(" ", "");
		if (s.isEmpty()) return -1;
		long total = 0;
		int i = 0;
		while (i < s.length()) {
			int j = i;
			while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
			if (j == i) return -1;
			long value;
			try {
				value = Long.parseLong(s.substring(i, j));
			} catch (NumberFormatException e) {
				return -1;
			}
			int k = j;
			while (k < s.length() && !Character.isDigit(s.charAt(k))) k++;
			long multiplier = switch (s.substring(j, k)) {
				case "", "t", "tick", "ticks" -> 1L;
				case "s", "sec", "secs", "second", "seconds" -> 20L;
				case "m", "min", "mins", "minute", "minutes" -> 1200L;
				case "h", "hr", "hrs", "hour", "hours" -> 72000L;
				default -> -1L;
			};
			if (multiplier < 0) return -1;
			if (value > Long.MAX_VALUE / multiplier) return -1;
			long part = value * multiplier;
			if (total > Long.MAX_VALUE - part) return -1;
			total += part;
			i = k;
		}
		return total > 0 && total <= Integer.MAX_VALUE ? (int) total : -1;
	}

	/** Shortest readable form of a tick count: 72000 -> "1h", 1800 -> "1min30s", 7 -> "7t". */
	public static String format(int ticks) {
		if (ticks <= 0) return "0";
		int remaining = ticks;
		StringBuilder sb = new StringBuilder();
		int hours = remaining / 72000;
		remaining %= 72000;
		int minutes = remaining / 1200;
		remaining %= 1200;
		int seconds = remaining / 20;
		remaining %= 20;
		if (hours > 0) sb.append(hours).append('h');
		if (minutes > 0) sb.append(minutes).append("min");
		if (seconds > 0) sb.append(seconds).append('s');
		if (remaining > 0) sb.append(remaining).append('t');
		// The "0" branch is unreachable for ticks > 0 (audited 2026-09-19): at least
		// one unit is always appended; kept for a zero-tick input.
		return sb.length() == 0 ? "0" : sb.toString();
	}
}
