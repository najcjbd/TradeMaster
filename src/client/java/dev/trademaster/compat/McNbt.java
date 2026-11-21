package dev.trademaster.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raw item-data helpers for the custom-goods filter.
 *
 * <p>The filter is a subset: every configured field must be present on the live
 * item (with the same value), while extra fields are allowed. The raw form is the
 * item's component map (keys are component ids). The structured name/enchantment
 * filters do not depend on this raw form.
 */
public final class McNbt {

	private static final int CACHE_LIMIT = 256;
	private static final Map<String, CompoundTag> PARSE_CACHE =
			new LinkedHashMap<>(CACHE_LIMIT, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, CompoundTag> eldest) {
					return size() > CACHE_LIMIT;
				}
			};

	public static @Nullable CompoundTag parse(String text) {
		if (text == null || text.isBlank()) return null;
		String key = text.trim();
		synchronized (PARSE_CACHE) {
			if (PARSE_CACHE.containsKey(key)) return PARSE_CACHE.get(key);
			CompoundTag parsed;
			try {
				parsed = net.minecraft.nbt.TagParser.parseCompoundFully(key);
			} catch (Exception e) {
				parsed = null;
			}
			PARSE_CACHE.put(key, parsed);
			return parsed;
		}
	}

	public static boolean valid(String text) {
		return text == null || text.isBlank() || parse(text) != null;
	}

	public static CompoundTag itemData(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return new CompoundTag();
		var ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
		var encoded = DataComponentMap.CODEC.encodeStart(ops, stack.getComponents());
		Tag tag = encoded.result().orElse(null);
		return tag instanceof CompoundTag compound ? compound : new CompoundTag();
	}

	public static boolean contains(ItemStack stack, CompoundTag filter) {
		if (filter == null || filter.isEmpty()) return true;
		CompoundTag data = itemData(stack);
		if (contains(data, filter)) return true;
		// 1.20.5-1.21.4 wrap enchantments as {levels:{id:level}}; 1.21.5+ store the
		// flat map. Expose both shapes so a raw filter written on one node still
		// matches on the other.
		for (CompoundTag shape : enchantmentShapes(data)) {
			if (contains(shape, filter)) return true;
		}
		return contains(legacyView(stack), filter);
	}

	/** The component map with {@code minecraft:enchantments} /
	 * {@code minecraft:stored_enchantments} rewritten into the other era's shape.
	 * Empty when neither key is present. */
	static java.util.List<CompoundTag> enchantmentShapes(CompoundTag data) {
		CompoundTag flat = null;
		CompoundTag wrapped = null;
		for (String key : ENCHANTMENT_COMPONENTS) {
			Tag value = data.get(key);
			if (!(value instanceof CompoundTag compound)) continue;
			Tag levels = compound.get("levels");
			if (levels instanceof CompoundTag levelMap) {
				if (flat == null) flat = data.copy();
				flat.put(key, levelMap.copy());
			} else {
				if (wrapped == null) wrapped = data.copy();
				CompoundTag holder = new CompoundTag();
				holder.put("levels", compound.copy());
				wrapped.put(key, holder);
			}
		}
		java.util.List<CompoundTag> shapes = new java.util.ArrayList<>(2);
		if (flat != null) shapes.add(flat);
		if (wrapped != null) shapes.add(wrapped);
		return shapes;
	}

	private static final String[] ENCHANTMENT_COMPONENTS = {
			"minecraft:enchantments", "minecraft:stored_enchantments"};

	/** A legacy-tag view for filters written for pre-1.20.5 items. Common fields
	 * (enchantments/damage/unbreakable) are exposed; arbitrary components stay native. */
	private static CompoundTag legacyView(ItemStack stack) {
		CompoundTag legacy = new CompoundTag();
		ListTag enchantments = new ListTag();
		var live = net.minecraft.world.item.enchantment.EnchantmentHelper
				.getEnchantmentsForCrafting(stack);
		for (var entry : live.entrySet()) {
			var id = entry.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
			if (id == null) continue;
			CompoundTag enchantment = new CompoundTag();
			enchantment.putString("id", id.toString());
			enchantment.putInt("lvl", entry.getIntValue());
			enchantments.add(enchantment);
		}
		if (!enchantments.isEmpty()) legacy.put("Enchantments", enchantments);
		if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
			legacy.putInt("Damage", stack.getDamageValue());
		}
		if (stack.get(net.minecraft.core.component.DataComponents.UNBREAKABLE) != null) {
			legacy.putByte("Unbreakable", (byte) 1);
		}
		return legacy;
	}

	public static boolean contains(Tag live, Tag filter) {
		if (filter == null) return true;
		if (filter instanceof CompoundTag filterCompound) {
			if (!(live instanceof CompoundTag liveCompound)) return false;
			for (String key : filterCompound.keySet()) {
				if (!liveCompound.contains(key)) return false;
				if (!contains(liveCompound.get(key), filterCompound.get(key))) return false;
			}
			return true;
		}
		if (filter instanceof ListTag filterList) {
			if (!(live instanceof ListTag liveList)) return false;
			for (int i = 0; i < filterList.size(); i++) {
				Tag wanted = filterList.get(i);
				boolean found = false;
				for (int j = 0; j < liveList.size(); j++) {
					if (contains(liveList.get(j), wanted)) {
						found = true;
						break;
					}
				}
				if (!found) return false;
			}
			return true;
		}
		return sameValue(live, filter);
	}

	private static boolean sameValue(Tag live, Tag filter) {
		if (live instanceof net.minecraft.nbt.NumericTag a
				&& filter instanceof net.minecraft.nbt.NumericTag b) {
			return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
		}
		return filter.equals(live);
	}

	private McNbt() {}
}
