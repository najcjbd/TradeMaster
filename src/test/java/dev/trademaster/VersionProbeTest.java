package dev.trademaster;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Per-target smoke test. Runs on EVERY Stonecutter node's {@code test} task, so a
 * version that loses a core class/registration fails at build time instead of only
 * in-game. Version-specific probes (components vs NBT, the enchantment registry,
 * trading) are added here with {@code //? if <version>} gating as subsystems migrate.
 */
class VersionProbeTest {

	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void coreMinecraftClassesAreOnTheTestClasspath() {
		assertNotNull(SharedConstants.class);
		assertNotNull(net.minecraft.world.item.ItemStack.class);
	}
}
