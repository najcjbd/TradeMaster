package dev.trademaster.gui;

import dev.trademaster.compat.McList;
import dev.trademaster.compat.McInput;
import dev.trademaster.compat.McGraphics;
import dev.trademaster.compat.McButton;
import dev.trademaster.compat.McText;
import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.Minecraft;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class EnchantmentsListWidget extends AbstractSelectionList<EnchantmentEntry> {

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
		return false;
		//?}
	}

	public Button resetButton;
	public Button selectAllButton;

	public EnchantmentsListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		//? if >=1.20.2 {
		super(client, width, height, top, itemHeight);
		//?} else {
		/*super(client, width, height, top, top + height, itemHeight);*/
		//?}

		for (var entry : TradeMasterConfig.INSTANCE.enchantments.entrySet()) {
			this.addEntry(new EnchantmentEntry(entry.getKey()));
		}

		// Both top-row buttons must stay on the list even on a narrow (Android/FCL)
		// screen: the fixed pair at width-150 / width-78 pushed the reset button off
		// the left edge once the list was narrower than ~150. Shrinking both keeps the
		// wide-screen layout byte-identical (70px buttons at width-150 / width-78).
		int buttonWidth = Math.max(20, Math.min(70, (this.width - 16) / 2));
		int selectAllX = Math.max(2, this.width - 8 - buttonWidth);
		int resetX = Math.max(2, selectAllX - buttonWidth - 2);

		this.resetButton = McButton.builder(McText.translatable("trademaster.button.reset"), button -> {
					// one save for the whole batch instead of ~4 per entry. try/finally:
					// a throw mid-loop would otherwise leave batchSave true for the rest
					// of the session, turning every save() into a silent no-op
					TradeMasterConfig.INSTANCE.beginBatch();
					try {
						for (EnchantmentEntry e : this.children()) {
							e.resetToDefault();
						}
					} finally {
						TradeMasterConfig.INSTANCE.endBatch();
					}
				})
				// second row: y5 is occupied by the centered tabs, which overlap
				// these buttons on narrow (Android) screens and swallow the clicks
				.bounds(resetX, 27, buttonWidth, 16)
				.tooltip(McText.translatable("trademaster.button.reset"))
				.build();

		this.selectAllButton = McButton.builder(McText.translatable("trademaster.button.select_all"), button -> {
					TradeMasterConfig.INSTANCE.beginBatch();
					try {
						for (EnchantmentEntry e : this.children()) {
							e.setEnabled(true);
						}
					} finally {
						TradeMasterConfig.INSTANCE.endBatch();
					}
				})
				.bounds(selectAllX, 27, buttonWidth, 16)
				.build();
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
		// clamp: on a narrow list width-155 can fall left of the 5px margin and
		// invert the fill
		context.fill(5, 27, Math.max(8, this.width - 155), 44, 0xAFC7C0C0);
		context.drawString(Minecraft.getInstance().font,
				McText.translatable("trademaster.column.enchantment"), 9, 32, 0xFF202020);
		}


	//? if >=1.20.2 {
	@Override
	protected void renderListSeparators(GuiGraphics context) {
	}
	//?}

	//? if >=1.20.2 {
	@Override
	protected void updateWidgetNarration(NarrationElementOutput builder) {
	}
	//?} else {
	/*@Override
	public void updateNarration(NarrationElementOutput builder) {
	}*/
	//?}

	@Override
	public int getRowWidth() {
		return this.width - 12;
	}

	//? if >=1.21.2 {
	@Override
	protected int scrollBarX() {
		return this.width;
	}
	//?} else {
	/*@Override
	protected int getScrollbarPosition() {
		return this.width;
	}*/
	//?}

	@Override
	public boolean isMouseOver(double x, double y) {
		// The scrollbar sits on the widget's right edge, outside the bounds the
		// Screen uses to route mouse events; claim the scrollbar area explicitly
		// so scrollbar clicks/drags reach this widget.
		return super.isMouseOver(x, y) || this.overScrollbar(x, y);
	}

	@Override
	public int getRowTop(int index) {
		// +2 matches the list's getFirstEntryY(); without it each row is placed
		// 2px too high and entryAt misattributes the bottom 2px of every row
		return this.listTop() + 2 - (int) McList.scrollAmount(this) + index * this.entryRowHeight();
	}

	@Override
	public int getRowLeft() {
		return this.listLeft() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	// 26.1 replaced the primitive input signatures with event objects; the override
	// signatures are gated and the bodies work on primitives, forwarding through McInput.
	// preeditUpdated (IME) exists only on 26.1+.
	//? if >=26.1 {
	/*@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		for (EnchantmentEntry e : this.children()) {
			if (e.maxPriceField.isFocused()) return e.maxPriceField.preeditUpdated(input);
			if (e.levelField.isFocused()) return e.levelField.preeditUpdated(input);
		}
		return false;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
		return handleKeyPressed(input.key(), McInput.scancode(input), input.modifiers()) || super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyEvent input) {
		return handleKeyReleased(input.key(), McInput.scancode(input), input.modifiers()) || super.keyReleased(input);
	}

	@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
		if (!input.isAllowedChatCharacter()) return false;
		return handleCharTyped((char) input.codepoint()) || super.charTyped(input);
	}*/
	//?} else {
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		return handleKeyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		return handleKeyReleased(keyCode, scanCode, modifiers) || super.keyReleased(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		if (!McInput.isAllowedChatCharacter(c)) return false;
		return handleCharTyped(c) || super.charTyped(c, modifiers);
	}
	//?}

	private boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
		for (EnchantmentEntry e : this.children()) {
			if (e.maxPriceField.isFocused()) return McInput.keyPressed(e.maxPriceField, keyCode, scanCode, modifiers);
			if (e.levelField.isFocused()) return McInput.keyPressed(e.levelField, keyCode, scanCode, modifiers);
		}
		return false;
	}

	private boolean handleKeyReleased(int keyCode, int scanCode, int modifiers) {
		for (EnchantmentEntry e : this.children()) {
			McInput.keyReleased(e.maxPriceField, keyCode, scanCode, modifiers);
			McInput.keyReleased(e.levelField, keyCode, scanCode, modifiers);
		}
		return false;
	}

	private boolean handleCharTyped(char c) {
		boolean consumed = false;
		for (EnchantmentEntry e : this.children()) {
			consumed |= McInput.charTyped(e.maxPriceField, c, 0);
			consumed |= McInput.charTyped(e.levelField, c, 0);
		}
		// consuming here matters: the screen falls back to the screen-level focused
		// widget (this list) and would deliver the same character a second time
		return consumed;
	}

	/** Commits every entry's in-flight field edits (R20-2): screen close and tab
	 * switch discard the widgets without a focus-loss render pass. */
	public void flushEdits() {
		for (EnchantmentEntry e : this.children()) e.flushEdits();
	}

	/** Clicking a partially clipped entry makes the list auto-scroll it fully
	 * into view (setFocused -> scrollToEntry): that alignment scroll must not
	 * trigger the focus-clear below, or the click's freshly granted field focus
	 * dies instantly. Only user-initiated scrolling clears focus. */
	private boolean suppressFocusClear = false;

	//? if >=26.1 {
	/*@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean doubled) {
		beginMouseClick();
		try {
			return super.mouseClicked(click, doubled);
		} finally {
			this.suppressFocusClear = false;
		}
	}*/
	//?} else {
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		beginMouseClick();
		try {
			return super.mouseClicked(mouseX, mouseY, button);
		} finally {
			this.suppressFocusClear = false;
		}
	}
	//?}

	private void beginMouseClick() {
		this.suppressFocusClear = true;
		// a deliberate click ends the scroll-deferral: the render commit may now
		// apply the drafts a wheel scroll only blurred
		for (EnchantmentEntry e : this.children()) e.clearDeferCommit();
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = McList.scrollAmount(this);
		super.setScrollAmount(amount);
		if (McList.scrollAmount(this) == before || this.suppressFocusClear) return;
		// a focused field scrolled off-screen keeps swallowing keyboard input
		// invisibly: drop focus whenever the viewport actually moves (wheel
		// noise at the scroll boundaries must not kill focus mid-typing)
		for (EnchantmentEntry e : this.children()) e.clearFieldFocus();
	}

	/** Resolves the entry under the pointer from live row geometry (scroll-aware),
	 * so stale off-screen entries can never receive clicks or scroll events. */
	public @Nullable EnchantmentEntry entryAt(double x, double y) {
		if (!this.isMouseOver(x, y)) return null;
		int left = this.getRowLeft();
		if (x < left || x > left + this.getRowWidth()) return null;
		for (int i = 0; i < this.children().size(); i++) {
			int top = this.getRowTop(i);
			if (y >= top && y < top + this.entryRowHeight()) {
				return this.children().get(i);
			}
		}
		return null;
	}
}
