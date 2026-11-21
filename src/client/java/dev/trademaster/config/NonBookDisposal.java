package dev.trademaster.config;

/** What to do with a bought good (a non-book result the grindstone cannot disenchant)
 * once the bound chests all refuse it. */
public enum NonBookDisposal {
	CHEST_THEN_STOP("trademaster.settings.nonBookDisposal.stop"),
	CHEST_THEN_DROP("trademaster.settings.nonBookDisposal.drop");

	private final String translationKey;

	NonBookDisposal(String translationKey) {
		this.translationKey = translationKey;
	}

	/** Lang key for the maLiLib option list display (the enum itself is maLiLib-free). */
	public String translationKey() {
		return this.translationKey;
	}
}
