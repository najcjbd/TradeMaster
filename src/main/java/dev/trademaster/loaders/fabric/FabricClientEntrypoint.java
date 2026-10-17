//? if fabric {
package dev.trademaster.loaders.fabric;

import dev.trademaster.TradeMasterClient;
import dev.trademaster.platform.Platforms;
import net.fabricmc.api.ClientModInitializer;

public class FabricClientEntrypoint implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		Platforms.install(new FabricPlatform());
		Platforms.get().registerKeybinds();
		// the client command API only exists from 1.19.2; 1.18.2 runs keys-only
		//? if >=1.19.2 {
		TradeMasterCommand.register();
		//?}
		TradeMasterClient.initialize();
	}
}
//?}
