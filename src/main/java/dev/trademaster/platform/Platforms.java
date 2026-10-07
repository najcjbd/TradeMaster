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

	private Platforms() {}
}
