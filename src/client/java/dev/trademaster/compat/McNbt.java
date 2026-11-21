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
		return contains(itemData(stack), filter);
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
		return filter.equals(live);
	}

	private McNbt() {}
}
