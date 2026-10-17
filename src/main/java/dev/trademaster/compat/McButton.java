package dev.trademaster.compat;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Version-gated Button builder preserving the {@code builder(...).bounds(...).tooltip(...)
 * .build()} call shape. 1.19.2 has no {@code Button.builder} (only constructors) and no
 * {@code Tooltip} class, so the tooltip is dropped there.
 */
public final class McButton {

	public static Builder builder(Component label, Button.OnPress onPress) {
		return new Builder(label, onPress);
	}

	public static final class Builder {
		private final Component label;
		private final Button.OnPress onPress;
		private int x, y, width, height;
		//? if >=1.20 {
		private net.minecraft.client.gui.components.Tooltip tooltip;
		//?}

		Builder(Component label, Button.OnPress onPress) {
			this.label = label;
			this.onPress = onPress;
		}

		public Builder bounds(int x, int y, int width, int height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			return this;
		}

		/** Takes the tooltip TEXT (not a Tooltip object) so the call site compiles before
		 * 1.19.4, where the Tooltip class does not exist. */
		public Builder tooltip(Component tooltip) {
			//? if >=1.20 {
			this.tooltip = net.minecraft.client.gui.components.Tooltip.create(tooltip);
			//?}
			return this;
		}

		public Button build() {
			//? if >=1.20 {
			Button.Builder builder = Button.builder(this.label, this.onPress)
					.bounds(this.x, this.y, this.width, this.height);
			if (this.tooltip != null) builder.tooltip(this.tooltip);
			return builder.build();
			//?} else {
			/*return new Button(this.x, this.y, this.width, this.height, this.label, this.onPress);*/
			//?}
		}
	}

	private McButton() {}
}
