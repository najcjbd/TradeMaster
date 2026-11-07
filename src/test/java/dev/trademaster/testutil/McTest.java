package dev.trademaster.testutil;

import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
//? if >=1.20.5 {
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
//?}
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.VillagerProfession;
//? if >=1.20.5 {
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import java.util.List;
//?}

/**
 * Headless Minecraft scaffolding for the pure-logic test suite: the version-agnostic
 * bootstrap plus a profession Holder helper for the RigTarget tests.
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

	/** A deterministic RNG in the shape {@code McRandom} expects on this version. */
	public static Object random(long seed) {
		//? if >=1.19 {
		return net.minecraft.util.RandomSource.create(seed);
		//?} else {
		/*return new java.util.Random(seed);*/
		//?}
	}

	/** A Holder for a vanilla villager profession (the version-agnostic shape the
	 * shared RigTarget code consumes). */
	public static Holder<VillagerProfession> profession(ResourceKey<VillagerProfession> key) {
		return McRegistry.wrap(McBuiltIn.villagerProfessions(),
				McRegistry.value(McBuiltIn.villagerProfessions(), key));
	}

	// The synthetic-stack helpers exist only from 1.20.5 (the component model). The
	// tests that use them are gated to the same range; older versions have no way to
	// give an arbitrary item an arbitrary mining speed.
	//? if >=1.20.5 {
	/** A stack carrying the given TOOL speed and durability numbers. */
	public static ItemStack tool(Item item, float speed, int maxDamage, int damage) {
		bindMinimal(item, 1);
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.MAX_STACK_SIZE, 1);
		stack.set(DataComponents.MAX_DAMAGE, maxDamage);
		stack.set(DataComponents.DAMAGE, damage);
		//? if >=1.21.5 {
		/*stack.set(DataComponents.TOOL, new Tool(List.of(), speed, 1, true));*/
		//?} else {
		stack.set(DataComponents.TOOL, new Tool(List.of(), speed, 1));
		//?}
		return stack;
	}

	/** A non-tool stack (no TOOL component). */
	public static ItemStack plain(Item item) {
		bindMinimal(item, 64);
		ItemStack stack = new ItemStack(item);
		stack.set(DataComponents.MAX_STACK_SIZE, 64);
		return stack;
	}

	//? if >=26.1 {
	/*// 26.1+ binds item components from a datapack initializer a headless test does
	// not run; bind a minimal map before constructing the stack (the per-stack set
	// below then overrides exactly the components the test needs).
	private static void bindMinimal(Item item, int maxStackSize) {
		item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
				.set(DataComponents.MAX_STACK_SIZE, maxStackSize).build());
	}*/
	//?} else {
	private static void bindMinimal(Item item, int maxStackSize) {}
	//?}
	//?}
}
