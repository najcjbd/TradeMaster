package dev.trademaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic client entry point. Everything client-only lives in this shared
 * source set; each loader's entrypoint just calls {@link #initialize()}.
 */
public final class TradeMasterClient {

	public static final String MOD_ID = "trade-master";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static void initialize() {
		// real initialisation is migrated here subsystem by subsystem
	}

	private TradeMasterClient() {}
}
