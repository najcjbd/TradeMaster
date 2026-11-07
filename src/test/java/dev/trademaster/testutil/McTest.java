package dev.trademaster.testutil;

import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.VillagerProfession;

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

	/** A Holder for a vanilla villager profession (the version-agnostic shape the
	 * shared RigTarget code consumes). */
	public static Holder<VillagerProfession> profession(ResourceKey<VillagerProfession> key) {
		return McRegistry.wrap(McBuiltIn.villagerProfessions(),
				McRegistry.value(McBuiltIn.villagerProfessions(), key));
	}
}
