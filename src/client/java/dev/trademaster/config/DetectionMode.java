package dev.trademaster.config;

public enum DetectionMode {
	PACKET("trademaster.settings.detection.packet"),
	EVENT("trademaster.settings.detection.event"),
	BOTH("trademaster.settings.detection.both");

	private final String translationKey;

	DetectionMode(String translationKey) {
		this.translationKey = translationKey;
	}

	/** Lang key for the maLiLib option list display (the enum itself is maLiLib-free). */
	public String translationKey() {
		return this.translationKey;
	}
}
