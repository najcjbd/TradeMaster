package dev.trademaster.config;

import com.google.gson.JsonObject;

/**
 * Config test scaffolding (same package, so it can reach the package-private
 * {@code writeFrom}/{@code readInto}).
 *
 * <p>Both end with a maLiLib hotkey call; maLiLib refuses to initialise before Minecraft
 * does ("invoked too early"), so headless that last statement throws. Everything before it
 * has already run, so the helpers swallow that trailing failure and keep the populated
 * JSON / the applied fields. A real bug earlier in the method still surfaces as a failed
 * assertion.
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
		try {
			ConfigPersistence.writeFrom(config, root);
		} catch (Throwable ignored) {
			// trailing maLiLib hotkey write; the rest of the root is complete
		}
	}

	public static void read(TradeMasterConfig config, JsonObject root) {
		try {
			ConfigPersistence.readInto(config, root);
		} catch (Throwable ignored) {
			// trailing maLiLib hotkey read; every earlier section was applied
		}
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
