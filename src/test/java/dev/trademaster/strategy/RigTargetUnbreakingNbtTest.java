package dev.trademaster.strategy;

import dev.trademaster.testutil.McTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code RigTarget.unbreakingLevel} on the NBT-era versions: before 1.21 the
 * {@code Enchantments.UNBREAKING} constant is an Enchantment value, not a ResourceKey, so
 * comparing the holder's key to it was always false. The synthetic component-stack tests
 * cannot run here (no component model), hence the raw NBT tag.
 */
//? if <1.20.5 {
/*class RigTargetUnbreakingNbtTest {

	@BeforeAll
	static void boot() {
		McTest.boot();
	}

	@Test
	void unbreakingLevelReadsTheNbtTag() {
		ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
		ListTag enchantments = new ListTag();
		CompoundTag unbreaking = new CompoundTag();
		unbreaking.putString("id", "minecraft:unbreaking");
		unbreaking.putShort("lvl", (short) 3);
		enchantments.add(unbreaking);
		stack.getOrCreateTag().put("Enchantments", enchantments);

		assertEquals(3, RigTarget.unbreakingLevel(stack));
	}

	@Test
	void aStackWithoutUnbreakingIsZero() {
		assertEquals(0, RigTarget.unbreakingLevel(new ItemStack(Items.DIAMOND_SWORD)));
	}
}*/
//?}
