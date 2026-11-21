package dev.trademaster.compat;

import dev.trademaster.config.TradeMasterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibleTradersCompatTest {

	private boolean previous;

	@BeforeEach
	void save() {
		previous = TradeMasterConfig.INSTANCE.compatibleWithVisibleTraders;
		VisibleTradersCompat.reset();
	}

	@AfterEach
	void restore() {
		TradeMasterConfig.INSTANCE.compatibleWithVisibleTraders = previous;
		VisibleTradersCompat.reset();
	}

	@Test
	void onlyTheVisibleTradersChannelMarksTheServer() {
		TradeMasterConfig.INSTANCE.compatibleWithVisibleTraders = true;
		VisibleTradersCompat.onServerPayload("minecraft:brand");
		assertFalse(VisibleTradersCompat.serverHasVisibleTraders());
		VisibleTradersCompat.onServerPayload(VisibleTradersCompat.LOCKED_TRADES_CHANNEL);
		assertTrue(VisibleTradersCompat.serverHasVisibleTraders());
		// a new connection forgets the discovery
		VisibleTradersCompat.reset();
		assertFalse(VisibleTradersCompat.serverHasVisibleTraders());
	}

	@Test
	void theSwitchOffIgnoresThePayload() {
		TradeMasterConfig.INSTANCE.compatibleWithVisibleTraders = false;
		VisibleTradersCompat.onServerPayload(VisibleTradersCompat.LOCKED_TRADES_CHANNEL);
		assertFalse(VisibleTradersCompat.serverHasVisibleTraders());
	}
}
