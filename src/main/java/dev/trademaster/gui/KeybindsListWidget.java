package dev.trademaster.gui;

import dev.trademaster.compat.McList;
import dev.trademaster.compat.McGraphics;
import dev.trademaster.compat.McText;
import dev.trademaster.platform.Platforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Read-only keybind display; editing happens in the mod's own settings screen (or, on
 * modern Fabric with maLiLib present, in the maLiLib config screen). */
public class KeybindsListWidget extends AbstractSelectionList<KeybindsListWidget.KeyRow> {

	/** The list's left edge: 1.20.1 exposes protected x0/y0; 1.20.2+ inherits
	 * AbstractContainerWidget's getX/getY. */
	private int listLeft() {
		//? if >=1.20.2 {
		return this.getX();
		//?} else {
		/*return this.x0;*/
		//?}
	}

	private int listTop() {
		//? if >=1.20.2 {
		return this.getY();
		//?} else {
		/*return this.y0;*/
		//?}
	}

	/** Row height: 26.1 renamed AbstractSelectionList.itemHeight to defaultEntryHeight. */
	private int entryRowHeight() {
		//? if >=26.1 {
		/*return this.defaultEntryHeight;*/
		//?} else {
		return this.itemHeight;
		//?}
	}

	/** Claims the scrollbar area for mouse routing. 26.1 exposes a hit helper; earlier
	 * versions draw the bar inside the list bounds, so the base isMouseOver covers it. */
	private boolean overScrollbar(double x, double y) {
		//? if >=26.1 {
		/*return this.isOverScrollbar(x, y);*/
		//?} else {
		// the bar is drawn at the widget's right edge (scrollBarX), outside the
		// bounds the Screen uses to route clicks: claim that 6px strip explicitly,
		// or a scrollbar click never reaches this widget (the wheel still works)
		if (!McList.scrollable(this)) return false;
		int barX = this.scrollBarPosition();
		return x >= barX && x <= barX + 6
				&& y >= this.listTop() && y < this.listTop() + this.height;
		//?}
	}

	/** The scrollbar's x position under this version's API name. */
	private int scrollBarPosition() {
		//? if >=1.21.2 {
		return this.scrollBarX();
		//?} else {
		/*return this.getScrollbarPosition();*/
		//?}
	}

	public KeybindsListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		//? if >=1.20.2 {
		super(client, width, height, top, itemHeight);
		//?} else {
		/*super(client, width, height, top, top + height, itemHeight);*/
		//?}
		addEntry(new KeyRow("key.trade-master.open_menu",
				() -> Platforms.get().keybindLabel("open_menu")));
		addEntry(new KeyRow("trademaster.hotkey.startStop",
				() -> Platforms.get().keybindLabel("start_stop")));
		addEntry(new KeyRow("trademaster.hotkey.pickPosition",
				() -> Platforms.get().keybindLabel("pick_position")));
	}

		//? if >=1.20.2 {
	@Override
	protected void renderListBackground(GuiGraphics context) { drawListBackground(new McGraphics(context)); }
	//?} elif >=1.20 {
	/*@Override
	protected void renderBackground(GuiGraphics context) { drawListBackground(new McGraphics(context)); }*/
	//?} else {
	/*@Override
	protected void renderBackground(com.mojang.blaze3d.vertex.PoseStack context) { drawListBackground(new McGraphics(context)); }*/
	//?}

	private void drawListBackground(McGraphics context) {
		this.setSelected(null);
		context.fill(5, 27, this.width + 5, 44, 0xAFC7C0C0);
		context.drawString(Minecraft.getInstance().font,
				McText.translatable("trademaster.keybinds.hint"), 9, 32, 0xFF202020);
		}


	//? if >=1.20.2 {
	@Override
	protected void renderListSeparators(GuiGraphics context) {}
	//?}

	//? if >=1.20.2 {
	@Override
	protected void updateWidgetNarration(NarrationElementOutput builder) {}
	//?} else {
	/*@Override
	public void updateNarration(NarrationElementOutput builder) {}*/
	//?}

	@Override
	public int getRowWidth() { return this.width - 12; }

	//? if >=1.21.2 {
	@Override
	protected int scrollBarX() { return this.width; }
	//?} else {
	/*@Override
	protected int getScrollbarPosition() { return this.width; }*/
	//?}

	@Override
	public boolean isMouseOver(double x, double y) {
		// The scrollbar sits on the widget's right edge, outside the bounds the
		// Screen uses to route mouse events; claim the scrollbar area explicitly
		// so scrollbar clicks/drags reach this widget.
		return super.isMouseOver(x, y) || this.overScrollbar(x, y);
	}

	/** First row's offset from the widget top: 26.1 places entries at getY()+2, while
	 * earlier lists' own getRowTop adds a 4px padding (header height is 0 here). */
	private int firstEntryOffset() {
		//? if >=26.1 {
		/*return 2;*/
		//?} else {
		return 4;
		//?}
	}

	@Override
	public int getRowTop(int index) {
		// +2 matches the list's getFirstEntryY(); without it each row is placed
		// 2px too high and entryAt misattributes the bottom 2px of every row
		return this.listTop() + this.firstEntryOffset() - (int) McList.scrollAmount(this) + index * this.entryRowHeight();
	}

	@Override
	public int getRowLeft() {
		return this.listLeft() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	public static class KeyRow extends AbstractSelectionList.Entry<KeyRow> {
		private final String labelKey;
		private final java.util.function.Supplier<String> binding;

		KeyRow(String labelKey, java.util.function.Supplier<String> binding) {
			this.labelKey = labelKey;
			this.binding = binding;
		}

		// 26.1 replaced Entry.render(GuiGraphics,...) with Entry.extractContent(GuiGraphics,...);
		// the row geometry is passed as render() arguments on <=1.21.11 but read from the entry
		// on 26.1+. Both delegate to drawRow with explicit geometry.
		//? if >=26.1 {
		/*@Override
		public void extractContent(GuiGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawRow(new McGraphics(context), this.getX(), this.getY(), this.getWidth(), this.getHeight());
		}*/
		//?} elif >=1.20 {
		@Override
		public void render(GuiGraphics context, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawRow(new McGraphics(context), left, top, width, height);
		}
		//?} else {
		/*@Override
		public void render(com.mojang.blaze3d.vertex.PoseStack context, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawRow(new McGraphics(context), left, top, width, height);
				}*/
		//?}

		private void drawRow(McGraphics context, int x, int y, int width, int height) {
			Font font = Minecraft.getInstance().font;
			context.fill(x, y, x + width, y + height - 4, 0x1AC7C0C0);
			context.drawString(font, McText.translatable(this.labelKey), x + 6, y + 6, 0xFFFFFFFF);
			String keys = this.binding.get();
			if (keys == null || keys.isEmpty()) keys = "-";
			context.drawString(font, McText.literal("[" + keys + "]"),
					x + width - font.width("[" + keys + "]") - 10, y + 6, 0xFFE0E000);
		}
	}
}
