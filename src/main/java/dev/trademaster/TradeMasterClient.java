package dev.trademaster;

import dev.trademaster.compat.McRegistry;
import dev.trademaster.config.ConfigPersistence;
import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.Minecraft;
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
		ConfigLoad.initialLoad();
		LOGGER.info("Trade Master initialized");
	}

	private TradeMasterClient() {}

	/** Loads config from disk and merges the enchantment table with the live registry. */
	public static final class ConfigLoad {

		private static boolean loadedOnce = false;

		public static void initialLoad() {
			if (loadedOnce) return;
			loadedOnce = true;
			ConfigPersistence.load(TradeMasterConfig.INSTANCE);
		}

		public static void reloadEnchantments() {
			initialLoad();
			Minecraft client = Minecraft.getInstance();
			if (client.level != null) {
				TradeMasterConfig.INSTANCE.syncEnchantmentsFromRegistry(McRegistry.enchantments(client.level));
			}
		}

		private ConfigLoad() {}
	}
}
