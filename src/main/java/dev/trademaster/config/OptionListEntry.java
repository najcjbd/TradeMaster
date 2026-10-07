package dev.trademaster.config;

import net.minecraft.client.resources.language.I18n;

/**
 * A config option-list value, decoupled from any mod loader.
 *
 * <p>maLiLib's {@code IConfigOptionListEntry} is Fabric-only; it is adapted to this
 * interface on the Fabric side, so the shared enums never implement a loader interface.
 */
public interface OptionListEntry {

	/** The persisted value (the enum constant name). */
	String id();

	/** The localised label. */
	String displayName();

	/** The next value in the list. */
	OptionListEntry cycle(boolean forward);

	/** The value for a persisted id, or this on an unknown id. */
	OptionListEntry fromString(String value);

	/** Localised label for a translation key (shared by the list enums). */
	static String label(String key) {
		return I18n.get(key);
	}
}
