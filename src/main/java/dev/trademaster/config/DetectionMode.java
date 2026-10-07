package dev.trademaster.config;

public enum DetectionMode implements OptionListEntry {
	PACKET("trademaster.settings.detection.packet"),
	EVENT("trademaster.settings.detection.event"),
	BOTH("trademaster.settings.detection.both");

	private final String translationKey;

	DetectionMode(String translationKey) { this.translationKey = translationKey; }

	@Override public String id() { return this.name(); }
	@Override public String displayName() { return OptionListEntry.label(this.translationKey); }
	@Override public DetectionMode cycle(boolean forward) {
		return values()[(this.ordinal() + (forward ? 1 : values().length - 1)) % values().length];
	}
	@Override public DetectionMode fromString(String value) {
		try {
			return valueOf(value);
		} catch (IllegalArgumentException e) {
			return this;
		}
	}
}
