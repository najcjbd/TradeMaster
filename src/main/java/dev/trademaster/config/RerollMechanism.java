package dev.trademaster.config;

public enum RerollMechanism implements OptionListEntry {
	AXE("trademaster.settings.mechanism.axe"),
	TASK_CHAIN("trademaster.settings.mechanism.task_chain");

	private final String translationKey;

	RerollMechanism(String translationKey) { this.translationKey = translationKey; }

	@Override public String id() { return this.name(); }
	@Override public String displayName() { return OptionListEntry.label(this.translationKey); }
	@Override public RerollMechanism cycle(boolean forward) {
		return values()[(this.ordinal() + (forward ? 1 : values().length - 1)) % values().length];
	}
	@Override public RerollMechanism fromString(String value) {
		try {
			return valueOf(value);
		} catch (IllegalArgumentException e) {
			return this;
		}
	}
}
