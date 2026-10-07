//? if fabric {
package dev.trademaster.compat;

import dev.trademaster.TradeMasterClient;

import dev.trademaster.TradeMaster;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional clientcommands integration. clientcommands is only a suggested
 * dependency (not on the compile classpath), so this is a presence probe:
 * when it is installed, its RenderQueue/GuiBlocker/LongTask frameworks can
 * coexist with Trade Master; the pipeline itself does not require them.
 */
public final class ClientCommandsIntegration {

	private static boolean probed = false;

	private ClientCommandsIntegration() {}

	public static void probe() {
		// Defensive, unreachable today (audited 2026-09-19): probe() has exactly one
		// call site (TradeMaster:37), so this guard never fires.
		if (probed) return;
		probed = true;
		if (FabricLoader.getInstance().isModLoaded("clientcommands")) {
			TradeMasterClient.LOGGER.info("clientcommands detected; coexistence mode (no API coupling required)");
		}
	}
}
//?}
