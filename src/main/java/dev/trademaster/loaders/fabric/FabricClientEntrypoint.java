//? if fabric {
package dev.trademaster.loaders.fabric;

import dev.trademaster.TradeMasterClient;
import dev.trademaster.platform.Platforms;
import net.fabricmc.api.ClientModInitializer;

public class FabricClientEntrypoint implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		Platforms.install(new FabricPlatform());
		TradeMasterClient.initialize();
	}
}
//?}
