package dev.trademaster.config;

import com.google.gson.JsonObject;

/**
 * Config test scaffolding (same package, so it can reach the package-private
 * {@code writeFrom}/{@code readInto}). No maLiLib hotkey call exists on this branch, so
 * failures propagate instead of being swallowed.
 */
public final class ConfigTest {

	private ConfigTest() {}

	public static JsonObject write(TradeMasterConfig config) {
		JsonObject root = new JsonObject();
		writeInto(config, root);
		return root;
	}

	/** Writes into an EXISTING root, exactly like {@code save()} seeds from the file on
	 * disk — the way a hand-written unknown key survives a save. */
	public static void writeInto(TradeMasterConfig config, JsonObject root) {
		ConfigPersistence.writeFrom(config, root);
	}

	public static void read(TradeMasterConfig config, JsonObject root) {
		ConfigPersistence.readInto(config, root);
	}

	/** The model as JSON, for restoring the shared singleton after a mutating test. */
	public static JsonObject snapshot() {
		return write(TradeMasterConfig.INSTANCE);
	}

	/** Restores the shared singleton from a {@link #snapshot()}. {@code enchantments} is
	 * cleared explicitly because a read only replaces it when the file yielded entries. */
	public static void restore(JsonObject snapshot) {
		TradeMasterConfig.INSTANCE.enchantments.clear();
		read(TradeMasterConfig.INSTANCE, snapshot);
	}
}
