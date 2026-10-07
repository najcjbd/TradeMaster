package dev.trademaster;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Loader-agnostic client entry point. Everything client-only lives in this shared
 * source set; each loader's entrypoint just calls {@link #initialize()}.
 */
public final class TradeMasterClient {

	public static final Logger LOGGER = LogUtils.getLogger();

	public static void initialize() {
		LOGGER.info("Trade Master client initialising");
	}

	private TradeMasterClient() {}
}
