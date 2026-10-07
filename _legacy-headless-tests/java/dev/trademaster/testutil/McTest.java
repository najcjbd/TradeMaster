package dev.trademaster.testutil;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.List;

/**
 * Headless Minecraft scaffolding for the pure-logic test suite.
 *
 * <p>Since MC 26.2 item components are bound by a datapack-driven initializer pass, which
 * a unit test has no datapack for. Rather than boot a whole resource stack, tests bind a
 * minimal, fully controlled component set to the handful of items they use. That also makes
 * the tool numbers explicit instead of depending on vanilla balance changes.
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

	/**
	 * Binds a {@code MAX_DAMAGE}/{@code DAMAGE}/{@code TOOL} component set and returns a
	 * stack. The Tool has no block rules, so {@code getDestroySpeed} returns {@code speed}
	 * for every block — the algorithm only compares speeds, so this is faithful.
	 *
	 * <p>NOTE: this mutates the shared {@code Item} holder for the whole JVM. That is safe
	 * only because JUnit runs these tests sequentially and each one rebinds before it builds
	 * a stack; it would break under parallel execution.
	 */
	public static ItemStack tool(Item item, float speed, int maxDamage, int damage, int unbreaking) {
		DataComponentMap.Builder builder = DataComponentMap.builder()
				.set(DataComponents.MAX_STACK_SIZE, 1)
				.set(DataComponents.MAX_DAMAGE, maxDamage)
				.set(DataComponents.DAMAGE, damage)
				.set(DataComponents.TOOL, new Tool(List.of(), speed, 1, true));
		if (unbreaking > 0) {
			ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			mutable.set(unbreakingHolder(), unbreaking);
			builder.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
		}
		item.builtInRegistryHolder().bindComponents(builder.build());
		return new ItemStack(item);
	}

	public static ItemStack tool(Item item, float speed, int maxDamage, int damage) {
		return tool(item, speed, maxDamage, damage, 0);
	}

	/** A non-tool item (no TOOL component), so its destroy speed is the bare 1.0. */
	public static ItemStack plain(Item item) {
		item.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
				.set(DataComponents.MAX_STACK_SIZE, 64)
				.build());
		return new ItemStack(item);
	}

	/** A standalone Holder whose key is the vanilla Unbreaking enchantment, so
	 * {@code unwrapKey()} matches without a datapack registry. */
	public static Holder<Enchantment> unbreakingHolder() {
		return Holder.Reference.createStandAlone(new HolderOwner<Enchantment>() {}, Enchantments.UNBREAKING);
	}

	public static Holder<VillagerProfession> profession(ResourceKey<VillagerProfession> key) {
		return BuiltInRegistries.VILLAGER_PROFESSION.get(key).orElseThrow();
	}
}
