package dev.trademaster.matching;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cost slots an offer charges, in declaration order.
 *
 * <p>Vanilla's trade object carries exactly two ({@code baseCostA} and the optional
 * {@code costB}), but a mod or datapack that changes the trade shape can carry more —
 * which is why the config models costs as an ordered list. To support those without a
 * code change, the extra slots are read generically: every declared field of type
 * {@code ItemCost} or {@code Optional<ItemCost>} counts as one slot, in declaration
 * order (vanilla: baseCostA, costB).
 *
 * <p>Anything this cannot see is simply not part of the comparison — the client only
 * knows what the trade object it was given exposes.
 */
public final class OfferCosts {

	private OfferCosts() {}

	/** Field list per offer class, so the reflection runs once per class instead of
	 * per offer per tick. */
	private static final Map<Class<?>, List<Field>> SLOT_FIELDS = new ConcurrentHashMap<>();

	public static List<ItemStack> slots(MerchantOffer offer) {
		List<Field> fields = SLOT_FIELDS.computeIfAbsent(offer.getClass(), OfferCosts::findSlotFields);
		if (fields.size() < 2) {
			// no usable field shape (unexpected): fall back to the vanilla accessors
			return List.of(offer.getBaseCostA(), offer.getCostB());
		}
		List<ItemStack> slots = new ArrayList<>(fields.size());
		for (Field field : fields) {
			ItemStack stack = readAsStack(offer, field);
			if (stack != null) slots.add(stack);
		}
		return slots;
	}

	/** Declared ItemCost / Optional&lt;ItemCost&gt; fields, in declaration order. */
	private static List<Field> findSlotFields(Class<?> type) {
		List<Field> found = new ArrayList<>(2);
		for (Field field : type.getDeclaredFields()) {
			Class<?> raw = field.getType();
			if (raw == ItemCost.class || raw == Optional.class) {
				found.add(field);
			}
		}
		return found;
	}

	/** The field as an ItemStack, or null when it cannot be read: a field that throws,
	 * is a non-ItemCost Optional, or is an unexpected shape is skipped rather than
	 * breaking the scan. */
	private static ItemStack readAsStack(Object owner, Field field) {
		try {
			field.setAccessible(true);
			Object value = field.get(owner);
			if (value instanceof ItemCost cost) return cost.itemStack();
			if (value instanceof Optional<?> optional) {
				return optional.orElse(null) instanceof ItemCost cost ? cost.itemStack() : null;
			}
			return null;
		} catch (Throwable ignored) {
			return null;
		}
	}
}
