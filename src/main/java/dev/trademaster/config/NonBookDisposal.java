package dev.trademaster.config;

/** What to do with a bought good (a non-book result the grindstone cannot disenchant)
 * once the bound chests all refuse it. */
public enum NonBookDisposal implements OptionListEntry {
	CHEST_THEN_STOP("trademaster.settings.nonBookDisposal.stop"),
	CHEST_THEN_DROP("trademaster.settings.nonBookDisposal.drop");

	private final String translationKey;

	NonBookDisposal(String translationKey) { this.translationKey = translationKey; }

	@Override public String id() { return this.name(); }
	@Override public String displayName() { return OptionListEntry.label(this.translationKey); }
	@Override public NonBookDisposal cycle(boolean forward) {
		return values()[(this.ordinal() + (forward ? 1 : values().length - 1)) % values().length];
	}
	@Override public NonBookDisposal fromString(String value) {
		try {
			return valueOf(value);
		} catch (IllegalArgumentException e) {
			return this;
		}
	}
}
