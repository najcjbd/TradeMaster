package dev.trademaster.config;

public enum RerollMechanism {
	AXE("trademaster.settings.mechanism.axe"),
	TASK_CHAIN("trademaster.settings.mechanism.task_chain");

	private final String translationKey;

	RerollMechanism(String translationKey) {
		this.translationKey = translationKey;
	}

	/** Lang key for the maLiLib option list display (the enum itself is maLiLib-free). */
	public String translationKey() {
		return this.translationKey;
	}
}
