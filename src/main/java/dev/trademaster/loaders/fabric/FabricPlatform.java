//? if fabric {
package dev.trademaster.loaders.fabric;

import dev.trademaster.platform.TradeMasterPlatform;

/**
 * Fabric platform services. This is the ONLY place maLiLib is referenced: maLiLib is an
 * OPTIONAL, Fabric-only extra carrying the config screen + hotkeys. The shared code never
 * touches it. The maLiLib wiring and Fabric keybinds are added during the port.
 */
public class FabricPlatform implements TradeMasterPlatform {

	@Override
	public void registerKeybinds() {
		// Fabric KeyBindingHelper here; the optional maLiLib hotkeys live here too
	}

	@Override
	public void openConfigScreen() {
		// opens the mod's own TradeMasterScreen (loader-agnostic GUI)
	}

	@Override
	public boolean hasMalilib() {
		return true;
	}
}
//?}
