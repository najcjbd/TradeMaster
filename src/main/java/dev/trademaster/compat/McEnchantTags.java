package dev.trademaster.compat;


import dev.trademaster.compat.McBuiltIn;import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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

	/** True when {@code id} can appear on a librarian's enchanted-book trade. From 1.21
	 * this is the {@code minecraft:tradeable} tag; before the tags existed the same
	 * information lived on the enchantment itself ({@code isTradeable()}), which is what
	 * vanilla's tag data was generated from. */
	public static boolean isTradeable(Registry<Enchantment> registry, ResourceLocation id) {
		//? if >=1.21 {
		return McRegistry.isInTag(registry, tradeable(), id);
		//?} else {
		/*Enchantment enchantment = McRegistry.value(registry, id);
		return enchantment != null && enchantment.isTradeable();*/
		//?}
	}

	/** True when {@code id} doubles its trade price. From 1.21 this is the
	 * {@code minecraft:double_trade_price} tag (all treasure enchantments); before the
	 * tags existed vanilla used {@code isTreasureOnly()} for the same doubling. */
	public static boolean isDoubleTradePrice(Registry<Enchantment> registry, ResourceLocation id) {
		//? if >=1.21 {
		return McRegistry.isInTag(registry, doubleTradePrice(), id);
		//?} else {
		/*Enchantment enchantment = McRegistry.value(registry, id);
		return enchantment != null && enchantment.isTreasureOnly();*/
		//?}
	}

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
