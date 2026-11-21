package dev.trademaster.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version-gated raw item-data helpers for the custom-goods filter.
 *
 * <p>The filter is a subset: every configured field must be present on the live
 * item (with the same value), while extra fields are allowed. Before 1.20.5 the
 * filter is the item's NBT {@code tag}; from 1.20.5 it is the item's component map
 * (keys are component ids). The structured name/enchantment filters do not depend
 * on this raw form.
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

	/** Parses a SNBT compound, or null when the text is not a compound. */
	public static @Nullable CompoundTag parse(String text) {
		if (text == null || text.isBlank()) return null;
		String key = text.trim();
		synchronized (PARSE_CACHE) {
			if (PARSE_CACHE.containsKey(key)) return PARSE_CACHE.get(key);
			CompoundTag parsed;
			try {
				//? if >=1.21.5 {
				/*parsed = net.minecraft.nbt.TagParser.parseCompoundFully(key);*/
				//?} else {
				parsed = net.minecraft.nbt.TagParser.parseTag(key);
				//?}
			} catch (Exception e) {
				parsed = null;
			}
			PARSE_CACHE.put(key, parsed);
			return parsed;
		}
	}

	/** Whether {@code text} is a valid SNBT compound (an empty/blank field is valid). */
	public static boolean valid(String text) {
		return text == null || text.isBlank() || parse(text) != null;
	}

	/** The live item's full filterable data: the NBT tag before 1.20.5, the component
	 * map from 1.20.5. Empty when the client level/registries are unavailable. */
	public static CompoundTag itemData(ItemStack stack) {
		//? if >=1.20.5 {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) return new CompoundTag();
		var ops = net.minecraft.resources.RegistryOps.create(
				net.minecraft.nbt.NbtOps.INSTANCE, mc.level.registryAccess());
		var encoded = net.minecraft.core.component.DataComponentMap.CODEC
				.encodeStart(ops, stack.getComponents());
		Tag tag = encoded.result().orElse(null);
		return tag instanceof CompoundTag compound ? compound : new CompoundTag();
		//?} else {
		/*CompoundTag tag = stack.getTag();
		return tag == null ? new CompoundTag() : tag;*/
		//?}
	}

	/** Subset match of a parsed filter against the live item's data. The native form is
	 * checked first; a legacy-style filter is also matched against a synthesized
	 * legacy view on 1.20.5+, and a component-style filter against a component view on
	 * older nodes. Known common fields (enchantments, damage, unbreakable) therefore
	 * work across versions; arbitrary mod-specific data remains native-only. */
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
		if (contains(legacyView(stack), filter)) return true;
		return contains(componentView(stack), filter);
	}

	/** The component map with {@code minecraft:enchantments} /
	 * {@code minecraft:stored_enchantments} rewritten into the other era's shape.
	 * Empty when neither key is present or both already differ. */
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

	/** A legacy-tag view of a 1.20.5+ item for cross-era filters. */
	private static CompoundTag legacyView(ItemStack stack) {
		//? if >=1.20.5 {
		CompoundTag legacy = new CompoundTag();
		ListTag enchantments = new ListTag();
		for (var entry : McEnchantments.of(stack).entrySet()) {
			ResourceLocation id = entry.getKey().unwrapKey().map(McRegistry::keyId).orElse(null);
			if (id == null) continue;
			CompoundTag enchantment = new CompoundTag();
			enchantment.putString("id", id.toString());
			enchantment.putInt("lvl", entry.getValue());
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
		//?} else {
		/*return new CompoundTag();*/
		//?}
	}

	/** A component-map view of a pre-1.20.5 item for cross-era filters. */
	private static CompoundTag componentView(ItemStack stack) {
		//? if <1.20.5 {
		/*CompoundTag view = new CompoundTag();
		CompoundTag enchantments = new CompoundTag();
		for (var entry : McEnchantments.of(stack).entrySet()) {
			ResourceLocation id = entry.getKey().unwrapKey().map(McRegistry::keyId).orElse(null);
			if (id != null) enchantments.putInt(id.toString(), entry.getValue());
		}
		if (!enchantments.isEmpty()) view.put("minecraft:enchantments", enchantments);
		if (stack.isDamageableItem() && stack.getDamageValue() > 0) {
			view.putInt("minecraft:damage", stack.getDamageValue());
		}
		CompoundTag tag = stack.getTag();
		if (tag != null && tag.contains("Unbreakable")) {
			view.put("minecraft:unbreakable", new CompoundTag());
		}
		return view;*/
		//?} else {
		return new CompoundTag();
		//?}
	}

	/** Recursive subset match: compounds need each configured key; lists need each
	 * configured element to have a matching element (extra elements allowed); other
	 * tags compare by value (numeric widths are equivalent). */
	public static boolean contains(Tag live, Tag filter) {
		if (filter == null) return true;
		if (filter instanceof CompoundTag filterCompound) {
			if (!(live instanceof CompoundTag liveCompound)) return false;
			//? if >=1.21.5 {
			/*for (String key : filterCompound.keySet()) {*/
			//?} else {
			for (String key : filterCompound.getAllKeys()) {
			//?}
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
			//? if >=1.21.5 {
			/*return Double.compare(a.doubleValue(), b.doubleValue()) == 0;*/
			//?} else {
			return Double.compare(a.getAsDouble(), b.getAsDouble()) == 0;
			//?}
		}
		return filter.equals(live);
	}

	private McNbt() {}
}
