package dev.trademaster.gui;

import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

public class EnchantmentsListWidget extends AbstractSelectionList<EnchantmentEntry> {

	public Button resetButton;
	public Button selectAllButton;

	public EnchantmentsListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		super(client, width, height, top, itemHeight);

		for (var entry : TradeMasterConfig.INSTANCE.enchantments.entrySet()) {
			this.addEntry(new EnchantmentEntry(entry.getKey()));
		}

		this.resetButton = Button.builder(Component.translatable("trademaster.button.reset"), button -> {
					// one save for the whole batch instead of ~4 per entry
					TradeMasterConfig.INSTANCE.beginBatch();
					for (EnchantmentEntry e : this.children()) {
						e.resetToDefault();
					}
					TradeMasterConfig.INSTANCE.endBatch();
				})
				// second row: y5 is occupied by the centered tabs, which overlap
				// these buttons on narrow (Android) screens and swallow the clicks
				.bounds(this.width - 150, 27, 70, 16)
				.tooltip(Tooltip.create(Component.translatable("trademaster.button.reset")))
				.build();

		this.selectAllButton = Button.builder(Component.translatable("trademaster.button.select_all"), button -> {
					TradeMasterConfig.INSTANCE.beginBatch();
					for (EnchantmentEntry e : this.children()) {
						e.setEnabled(true);
					}
					TradeMasterConfig.INSTANCE.endBatch();
				})
				.bounds(this.width - 78, 27, 70, 16)
				.build();
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		context.fill(5, 27, this.width - 155, 44, 0xAFC7C0C0);
		context.text(Minecraft.getInstance().font,
				Component.translatable("trademaster.column.enchantment"), 9, 32, 0xFF202020);
	}

	@Override
	protected void extractListSeparators(GuiGraphicsExtractor context) {
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput builder) {
	}

	@Override
	public int getRowWidth() {
		return this.width - 12;
	}

	@Override
	protected int scrollBarX() {
		return this.width;
	}

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

	@Override
	public boolean keyPressed(KeyEvent input) {
		for (EnchantmentEntry e : this.children()) {
			if (e.maxPriceField.isFocused()) return e.maxPriceField.keyPressed(input);
			if (e.levelField.isFocused()) return e.levelField.keyPressed(input);
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		for (EnchantmentEntry e : this.children()) {
			e.maxPriceField.keyReleased(input);
			e.levelField.keyReleased(input);
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (!input.isAllowedChatCharacter()) return false;
		boolean consumed = false;
		for (EnchantmentEntry e : this.children()) {
			consumed |= e.maxPriceField.charTyped(input);
			consumed |= e.levelField.charTyped(input);
		}
		// consuming here matters: the screen falls back to the screen-level focused
		// widget (this list) and would deliver the same character a second time
		return consumed || super.charTyped(input);
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

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		this.suppressFocusClear = true;
		try {
			return super.mouseClicked(click, doubled);
		} finally {
			this.suppressFocusClear = false;
		}
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = this.scrollAmount();
		super.setScrollAmount(amount);
		if (this.scrollAmount() == before || this.suppressFocusClear) return;
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
			if (y >= top && y < top + this.defaultEntryHeight) {
				return this.children().get(i);
			}
		}
		return null;
	}
}
