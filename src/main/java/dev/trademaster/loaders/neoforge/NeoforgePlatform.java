//? if neoforge {
/*package dev.trademaster.loaders.neoforge;

import dev.trademaster.platform.TradeMasterPlatform;

// NeoForge platform services. No maLiLib here (it is Fabric-only).
public class NeoforgePlatform implements TradeMasterPlatform {

	@Override
	public void registerKeybinds() {
		// NeoForge key-mapping registration (added during the port)
	}

	@Override
	public void openConfigScreen() {
		// opens the mod's own TradeMasterScreen
	}

	@Override
	public java.nio.file.Path configDir() {
		return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
	}
}
*///?}
