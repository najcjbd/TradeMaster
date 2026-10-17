package dev.trademaster.compat;


import dev.trademaster.compat.McBuiltIn;import net.minecraft.core.Registry;
import net.minecraft.world.item.Item;

/**
 * Version-gated access to the built-in (static) registries.
 *
 * <p>{@code net.minecraft.core.registries.BuiltInRegistries} is absent before 1.19.3,
 * where the built-in registries were static fields on {@code net.minecraft.core.Registry};
 * the registry ResourceKeys came from {@code Registries} only from 1.19.3 too.
 */
public final class McBuiltIn {

	/** The built-in item registry. */
	public static Registry<Item> items() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.BuiltInRegistries.ITEM;
		//?} else {
		/*return net.minecraft.core.Registry.ITEM;*/
		//?}
	}

	/** The built-in block registry. */
	public static Registry<net.minecraft.world.level.block.Block> blocks() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK;
		//?} else {
		/*return net.minecraft.core.Registry.BLOCK;*/
		//?}
	}

	/** The built-in villager-profession registry. */
	public static Registry<net.minecraft.world.entity.npc.VillagerProfession> villagerProfessions() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION;
		//?} else {
		/*return net.minecraft.core.Registry.VILLAGER_PROFESSION;*/
		//?}
	}

	/** The built-in point-of-interest registry. */
	public static Registry<net.minecraft.world.entity.ai.village.poi.PoiType> poiTypes() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.BuiltInRegistries.POINT_OF_INTEREST_TYPE;
		//?} else {
		/*return net.minecraft.core.Registry.POINT_OF_INTEREST_TYPE;*/
		//?}
	}

	/** The built-in enchantment registry. Only valid before 1.21: from 1.21 enchantments are
	 * a datapack registry reached through the level's RegistryAccess instead. */
	public static Registry<net.minecraft.world.item.enchantment.Enchantment> enchantments() {
		//? if >=1.21 {
		throw new UnsupportedOperationException("enchantments are a datapack registry from 1.21");
		//?} elif >=1.19.3 {
		/*return net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT;*/
		//?} else {
		/*return net.minecraft.core.Registry.ENCHANTMENT;*/
		//?}
	}

	/** The ResourceKey of the enchantment registry. */
	public static net.minecraft.resources.ResourceKey<Registry<net.minecraft.world.item.enchantment.Enchantment>> enchantmentKey() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.Registries.ENCHANTMENT;
		//?} else {
		/*return net.minecraft.core.Registry.ENCHANTMENT_REGISTRY;*/
		//?}
	}

	/** The ResourceKey of the villager-profession registry. */
	public static net.minecraft.resources.ResourceKey<Registry<net.minecraft.world.entity.npc.VillagerProfession>> villagerProfessionKey() {
		//? if >=1.19.3 {
		return net.minecraft.core.registries.Registries.VILLAGER_PROFESSION;
		//?} else {
		/*return net.minecraft.core.Registry.VILLAGER_PROFESSION_REGISTRY;*/
		//?}
	}

	private McBuiltIn() {}
}
