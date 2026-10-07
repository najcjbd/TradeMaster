package dev.trademaster.config;

import net.minecraft.resources.ResourceLocation;

public record EnchantmentTarget(ResourceLocation id, boolean enabled, int level, int maxPrice) {
	public EnchantmentTarget withEnabled(boolean enabled) {
		return new EnchantmentTarget(id, enabled, level, maxPrice);
	}

	public EnchantmentTarget withLevel(int level) {
		return new EnchantmentTarget(id, enabled, level, maxPrice);
	}

	public EnchantmentTarget withMaxPrice(int maxPrice) {
		return new EnchantmentTarget(id, enabled, level, maxPrice);
	}
}
