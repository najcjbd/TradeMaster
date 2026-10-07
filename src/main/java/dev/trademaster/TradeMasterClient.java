package dev.trademaster;

/**
 * Loader-agnostic client entry point. Everything client-only lives in this shared
 * source set; each loader's entrypoint just calls {@link #initialize()}.
 *
 * <p>No logger yet on purpose: {@code com.mojang.logging.LogUtils} only exists from
 * MC 1.17, so a version-agnostic logger is wired during the porting pass.
 */
public final class TradeMasterClient {

	public static void initialize() {
		// real initialisation is migrated here subsystem by subsystem
	}

	private TradeMasterClient() {}
}
