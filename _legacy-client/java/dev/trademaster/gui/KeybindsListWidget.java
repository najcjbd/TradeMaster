package dev.trademaster.gui;

import dev.trademaster.config.MaliLibAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Read-only keybind display; editing happens in the maLiLib config screen. */
public class KeybindsListWidget extends AbstractSelectionList<KeybindsListWidget.KeyRow> {

	public KeybindsListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		super(client, width, height, top, itemHeight);
		addEntry(new KeyRow("key.trade-master.open_menu",
				() -> dev.trademaster.TradeMaster.getOpenMenuKey().getTranslatedKeyMessage().getString()));
		addEntry(new KeyRow("trademaster.hotkey.startStop",
				() -> MaliLibAdapter.HOTKEY_START_STOP.getKeybind().getKeysDisplayString()));
		addEntry(new KeyRow("trademaster.hotkey.pickPosition",
				() -> MaliLibAdapter.HOTKEY_PICK_POSITION.getKeybind().getKeysDisplayString()));
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		context.fill(5, 27, this.width + 5, 44, 0xAFC7C0C0);
		context.text(Minecraft.getInstance().font,
				Component.translatable("trademaster.keybinds.hint"), 9, 32, 0xFF202020);
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor context) {}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput builder) {}

	@Override
	public int getRowWidth() { return this.width - 12; }

	@Override
	protected int scrollBarX() { return this.width; }

	@Override
	public boolean isMouseOver(double x, double y) {
		// The scrollbar sits on the widget's right edge, outside the bounds the
		// Screen uses to route mouse events; claim the scrollbar area explicitly
		// so scrollbar clicks/drags reach this widget.
		return super.isMouseOver(x, y) || this.isOverScrollbar(x, y);
	}

	@Override
	public int getRowTop(int index) {
		// +2 matches the list's getFirstEntryY(); without it each row is placed
		// 2px too high and entryAt misattributes the bottom 2px of every row
		return this.getY() + 2 - (int) this.scrollAmount() + index * this.defaultEntryHeight;
	}

	@Override
	public int getRowLeft() {
		return this.getX() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	public static class KeyRow extends AbstractSelectionList.Entry<KeyRow> {
		private final String labelKey;
		private final java.util.function.Supplier<String> binding;

		KeyRow(String labelKey, java.util.function.Supplier<String> binding) {
			this.labelKey = labelKey;
			this.binding = binding;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			int x = this.getX();
			int y = this.getY();
			Font font = Minecraft.getInstance().font;
			context.fill(x, y, x + this.getWidth(), y + this.getHeight() - 4, 0x1AC7C0C0);
			context.text(font, Component.translatable(this.labelKey), x + 6, y + 6, 0xFFFFFFFF);
			String keys = this.binding.get();
			if (keys == null || keys.isEmpty()) keys = "-";
			context.text(font, Component.literal("[" + keys + "]"),
					x + this.getWidth() - font.width("[" + keys + "]") - 10, y + 6, 0xFFE0E000);
		}
	}
}
