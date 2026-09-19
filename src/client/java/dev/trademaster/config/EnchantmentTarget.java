package dev.trademaster.config;

import net.minecraft.resources.Identifier;

public record EnchantmentTarget(Identifier id, boolean enabled, int level, int maxPrice) {
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
