package dev.trademaster.compat;

import net.minecraft.client.gui.components.AbstractWidget;

/** Version-gated widget mutators. {@code AbstractWidget.setY} was added after 1.19.2, where
 * the {@code y} field is public instead. */
public final class McWidget {

	public static void setY(AbstractWidget widget, int y) {
		//? if >=1.20 {
		widget.setY(y);
		//?} else {
		/*widget.y = y;*/
		//?}
	}

	public static int getX(AbstractWidget widget) {
		//? if >=1.20 {
		return widget.getX();
		//?} else {
		/*return widget.x;*/
		//?}
	}

	private McWidget() {}
}
