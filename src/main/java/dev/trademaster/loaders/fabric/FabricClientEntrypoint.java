//? if fabric {
package dev.trademaster.loaders.fabric;

import dev.trademaster.TradeMasterClient;
import net.fabricmc.api.ClientModInitializer;

public class FabricClientEntrypoint implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		TradeMasterClient.initialize();
	}
}
//?}
