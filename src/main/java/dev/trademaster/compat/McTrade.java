package dev.trademaster.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
//? if >=1.21.2 {
import net.minecraft.world.item.trading.ItemCost;
//?}

/** Version-gated trade-list helpers. */
public final class McTrade {

	/** A deep-enough copy of the offer list: {@code MerchantOffers.copy()} exists from 1.21;
	 * before that the list is copied element by element. */
	public static MerchantOffers copyOffers(MerchantOffers offers) {
		//? if >=1.21 {
		return offers.copy();
		//?} else {
		/*MerchantOffers copy = new MerchantOffers();
		copy.addAll(offers);
		return copy;*/
		//?}
	}

	/** Whether {@code raw} is the type of an offer's cost-slot field. 1.21.2 changed the
	 * cost fields from {@code ItemStack} to {@code ItemCost}. */
	public static boolean isCostType(java.lang.reflect.Type raw) {
		//? if >=1.21.2 {
		return raw == ItemCost.class;
		//?} else {
		/*return raw == ItemStack.class;*/
		//?}
	}

	/** The stack a cost-slot value represents (an {@code ItemCost} or an {@code ItemStack}),
	 * or null when it is neither. */
	public static ItemStack costAsStack(Object value) {
		//? if >=1.21.2 {
		return value instanceof ItemCost cost ? cost.itemStack() : null;
		//?} else {
		/*return value instanceof ItemStack stack ? stack : null;*/
		//?}
	}

	private McTrade() {}
}
