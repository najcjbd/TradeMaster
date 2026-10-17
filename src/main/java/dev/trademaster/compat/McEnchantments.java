package dev.trademaster.compat;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version-gated enchantment views.
 *
 * <p>1.20.5 replaced the NBT {@code Map<Enchantment,Integer>} with the
 * {@code ItemEnchantments} component; 1.21 turned the enchantment registry into a
 * datapack registry and added {@code EnchantmentTags}. This normalises the stack's
 * enchantments to a {@code Map<Holder<Enchantment>,Integer>} and hides the tag checks.
 */
public final class McEnchantments {

	/** The stack's (crafting) enchantments, keyed by Holder on every version. */
	public static Map<Holder<Enchantment>, Integer> of(ItemStack stack) {
		//? if >=1.21 {
		Map<Holder<Enchantment>, Integer> map = new LinkedHashMap<>();
		for (var e : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
			map.put(e.getKey(), e.getIntValue());
		}
		return map;
		//?} else {
		/*Map<Holder<Enchantment>, Integer> map = new LinkedHashMap<>();
		for (var e : EnchantmentHelper.getEnchantments(stack).entrySet()) {
			map.put(McRegistry.wrap(McBuiltIn.enchantments(), e.getKey()), e.getValue());
		}
		return map;*/
		//?}
	}

	/** Whether the enchantment is a curse. Before 1.21 the tag does not exist, so the
	 * enchantment's own flag is used. */
	public static boolean isCurse(Holder<Enchantment> holder) {
		//? if >=1.21 {
		return holder.is(net.minecraft.tags.EnchantmentTags.CURSE);
		//?} else {
		/*return holder.value().isCurse();*/
		//?}
	}

	/** Whether the enchantment can appear on a villager trade. Before 1.21 the tradeable
	 * tag does not exist; every registered enchantment is treated as tradeable (the
	 * pre-1.21 behaviour, where the tag was not part of the offer pool). */
	public static boolean isTradeable(Holder<Enchantment> holder) {
		//? if >=1.21 {
		return holder.is(net.minecraft.tags.EnchantmentTags.TRADEABLE);
		//?} else {
		/*return true;*/
		//?}
	}

	/** The enchantment's full display name at {@code level}. 1.21+ takes the Holder. */
	public static net.minecraft.network.chat.Component fullname(Holder<Enchantment> holder, int level) {
		//? if >=1.21 {
		return Enchantment.getFullname(holder, level);
		//?} else {
		/*return holder.value().getFullname(level);*/
		//?}
	}

	private McEnchantments() {}
}
