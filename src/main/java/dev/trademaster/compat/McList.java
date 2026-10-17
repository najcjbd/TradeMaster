package dev.trademaster.compat;

import net.minecraft.client.gui.components.AbstractSelectionList;

/**
 * Version-gated list helpers.
 *
 * <p>1.21.2 moved scrolling into {@code AbstractScrollArea}, renaming
 * {@code getScrollAmount()} to {@code scrollAmount()}. Shared code reads the scroll
 * offset through here.
 */
public final class McList {

	/** The current scroll offset of {@code list}. */
	public static double scrollAmount(AbstractSelectionList<?> list) {
		//? if >=1.21.2 {
		return list.scrollAmount();
		//?} else {
		/*return list.getScrollAmount();*/
		//?}
	}

	/** Whether the list has off-screen content (a non-zero maximum scroll). */
	public static boolean scrollable(AbstractSelectionList<?> list) {
		//? if >=1.21.2 {
		return list.maxScrollAmount() > 0;
		//?} else {
		/*return list.getMaxScroll() > 0;*/
		//?}
	}

	private McList() {}
}
