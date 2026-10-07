package dev.trademaster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Per-target smoke test. Runs on EVERY Stonecutter node's {@code test} task, so a
 * version that loses a core class fails at build time instead of only in-game.
 * Version-specific probes (components vs NBT, the enchantment registry, trading) are
 * added here with {@code //? if <version>} gating as subsystems migrate.
 */
class VersionProbeTest {

	@Test
	void coreMinecraftClassesAreOnTheTestClasspath() {
		// classes present in every supported version (1.16.5 -> 26.3)
		assertNotNull(net.minecraft.SharedConstants.class);
		assertNotNull(net.minecraft.world.item.ItemStack.class);
	}
}
