package dev.trademaster.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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

	/** Subset match of a parsed filter against the live item's data. */
	public static boolean contains(ItemStack stack, CompoundTag filter) {
		if (filter == null || filter.isEmpty()) return true;
		return contains(itemData(stack), filter);
	}

	/** Recursive subset match: compounds need each configured key; lists need each
	 * configured element to have a matching element (extra elements allowed); other
	 * tags compare by value. */
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
		return filter.equals(live);
	}

	private McNbt() {}
}
