package dev.trademaster.config;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import net.minecraft.client.resources.language.I18n;

/** What to do with a bought good (a non-book result the grindstone cannot disenchant)
 * once the bound chests all refuse it. */
public enum NonBookDisposal implements IConfigOptionListEntry {
	CHEST_THEN_STOP("trademaster.settings.nonBookDisposal.stop"),
	CHEST_THEN_DROP("trademaster.settings.nonBookDisposal.drop");

	private final String translationKey;

	NonBookDisposal(String translationKey) { this.translationKey = translationKey; }

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
