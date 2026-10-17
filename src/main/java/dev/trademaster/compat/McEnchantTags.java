package dev.trademaster.compat;


import dev.trademaster.compat.McBuiltIn;import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Version-gated enchantment tag keys.
 *
 * <p>{@code net.minecraft.tags.EnchantmentTags} exists from 1.21. Before that the keys are
 * built by hand against the enchantment registry key: {@code McBuiltIn.enchantmentKey()} from
 * 1.19.3, {@code Registry.ENCHANTMENT_REGISTRY} before.
 */
public final class McEnchantTags {

	public static TagKey<Enchantment> tradeable() {
		//? if >=1.21 {
		return net.minecraft.tags.EnchantmentTags.TRADEABLE;
		//?} else {
		/*return TagKey.create(enchantments(), McIds.mc("tradeable"));*/
		//?}
	}

	public static TagKey<Enchantment> doubleTradePrice() {
		//? if >=1.21 {
		return net.minecraft.tags.EnchantmentTags.DOUBLE_TRADE_PRICE;
		//?} else {
		/*return TagKey.create(enchantments(), McIds.mc("double_trade_price"));*/
		//?}
	}

	private static ResourceKey<Registry<Enchantment>> enchantments() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.Registries.ENCHANTMENT;
		//?} else {
		/*return net.minecraft.core.Registry.ENCHANTMENT_REGISTRY;*/
		//?}
	}

	private McEnchantTags() {}
}
