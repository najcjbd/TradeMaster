package dev.trademaster.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Headless Minecraft scaffolding for the pure-logic test suite.
 *
 * <p>Only the version-agnostic bootstrap lives here for now. The item-component helpers
 * (tool/plain) and registry helpers are added with the tests that use them, gated per
 * version (item components exist only from 1.20.5; several classes changed packages).
 */
public final class McTest {

	private static boolean booted;

	private McTest() {}

	public static synchronized void boot() {
		if (booted) return;
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		booted = true;
	}
}
