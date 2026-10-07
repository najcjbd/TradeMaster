package dev.trademaster.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import net.minecraft.client.resources.language.I18n;

public enum DetectionMode implements IConfigOptionListEntry {
	PACKET("trademaster.settings.detection.packet"),
	EVENT("trademaster.settings.detection.event"),
	BOTH("trademaster.settings.detection.both");

	private final String translationKey;

	DetectionMode(String translationKey) { this.translationKey = translationKey; }

	@Override public String getStringValue() { return this.name(); }
	@Override public String getDisplayName() { return I18n.get(this.translationKey); }
	@Override public IConfigOptionListEntry cycle(boolean forward) {
		return values()[(this.ordinal() + (forward ? 1 : values().length - 1)) % values().length];
	}
	@Override public IConfigOptionListEntry fromString(String value) {
		try {
			return valueOf(value);
		} catch (IllegalArgumentException e) {
			return this;
		}
	}
}
