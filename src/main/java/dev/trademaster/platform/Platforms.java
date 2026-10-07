package dev.trademaster.platform;

/** Installs and looks up the active {@link TradeMasterPlatform}. */
public final class Platforms {

	private static TradeMasterPlatform platform;

	public static void install(TradeMasterPlatform value) {
		platform = value;
	}

	public static TradeMasterPlatform get() {
		if (platform == null) {
			throw new IllegalStateException("TradeMasterPlatform not installed (no loader entrypoint ran)");
		}
		return platform;
	}

	/** Hotkeys to persist, or empty when no platform is installed (headless tests). */
	public static java.util.Map<String, com.google.gson.JsonElement> writeHotkeys() {
		return platform == null ? java.util.Map.of() : platform.writeHotkeys();
	}

	/** Load persisted hotkeys (no-op without a platform). */
	public static void readHotkeys(com.google.gson.JsonObject section) {
		if (platform != null) platform.readHotkeys(section);
	}

	private Platforms() {}
}
