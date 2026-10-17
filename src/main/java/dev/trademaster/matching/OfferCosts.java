package dev.trademaster.matching;

import net.minecraft.world.item.ItemStack;
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
		//? if >=1.21 {
		// ItemCost distinguishes the two COST fields from the ItemStack RESULT, so the
		// reflective scan below is safe there (and supports mod-added cost slots).
		List<Field> fields = SLOT_FIELDS.computeIfAbsent(offer.getClass(), OfferCosts::findSlotFields);
		if (fields.size() < 2) {
			// no usable field shape (unexpected): fall back to the vanilla accessors
			return List.of(offer.getBaseCostA(), offer.getCostB());
		}
		List<ItemStack> slots = new ArrayList<>(fields.size());
		for (Field field : fields) {
			ItemStack stack = readAsStack(offer, field);
			// an empty/unreadable slot stays in place as EMPTY: dropping it would shift
			// every later slot, and the positional fingerprint would then read a stable
			// offer as drifted
			slots.add(stack == null ? ItemStack.EMPTY : stack);
		}
		return slots;
		//?} else {
		/*// Before 1.21 both cost fields and the RESULT are ItemStacks: scanning by type
		// would count the result as a third cost slot and reject every offer. Use the
		// two known cost accessors (mod-added extra cost slots are not supported here).
		return List.of(offer.getBaseCostA(), offer.getCostB());*/
		//?}
	}

	/** The cost slots used for PRICE / AFFORDABILITY decisions: identical to
	 * {@link #slots} except that the price slot (slot 0) is replaced by the actually
	 * charged stack when {@code basePriceAsSalePrice} is off. Identity checks (LOCK
	 * fingerprints, channel comparison) must keep using {@link #slots}: the charged
	 * count can legitimately change with demand while the offer stays the same. */
	public static List<ItemStack> pricingSlots(MerchantOffer offer) {
		List<ItemStack> slots = new ArrayList<>(slots(offer));
		if (!dev.trademaster.config.TradeMasterConfig.INSTANCE.basePriceAsSalePrice && !slots.isEmpty()) {
			ItemStack charged = offer.getCostA();
			if (!charged.isEmpty()) slots.set(0, charged);
		}
		return slots;
	}

	/** Declared ItemCost / Optional&lt;ItemCost&gt; fields, in declaration order, walking
	 * the whole class hierarchy: a mod that extends the trade object may add slots, and
	 * the parent's slots must come FIRST or the extra ones would be read as slot 1. */
	private static List<Field> findSlotFields(Class<?> type) {
		List<Class<?>> chain = new ArrayList<>(2);
		for (Class<?> current = type; current != null && current != Object.class;
				current = current.getSuperclass()) {
			chain.add(current);
		}
		List<Field> found = new ArrayList<>(2);
		for (int i = chain.size() - 1; i >= 0; i--) {
			for (Field field : chain.get(i).getDeclaredFields()) {
				Class<?> raw = field.getType();
				if (dev.trademaster.compat.McTrade.isCostType(raw)) {
					field.setAccessible(true);
					found.add(field);
					continue;
				}
				// an unrelated Optional field must not count as a cost slot, or the
				// slots after it would be read one position off
				if (raw == Optional.class && optionalHoldsItemCost(field)) {
					field.setAccessible(true);
					found.add(field);
				}
			}
		}
		return found;
	}

	/** Whether an {@code Optional} field's element type is an ItemCost. */
	private static boolean optionalHoldsItemCost(Field field) {
		if (!(field.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized)) {
			return false;
		}
		java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
		return arguments.length == 1 && dev.trademaster.compat.McTrade.isCostType(arguments[0]);
	}

	/** The field as an ItemStack, or null when it cannot be read: a field that throws,
	 * is a non-ItemCost Optional, or is an unexpected shape is skipped rather than
	 * breaking the scan. */
	private static ItemStack readAsStack(Object owner, Field field) {
		try {
			Object value = field.get(owner);
			if (value instanceof Optional<?> optional) {
				return dev.trademaster.compat.McTrade.costAsStack(optional.orElse(null));
			}
			return dev.trademaster.compat.McTrade.costAsStack(value);
		} catch (Throwable ignored) {
			return null;
		}
	}
}
