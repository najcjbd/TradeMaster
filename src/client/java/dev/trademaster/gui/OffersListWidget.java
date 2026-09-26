package dev.trademaster.gui;

import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.exec.EnchantmentNamer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The offers tab: how each enchantment's trade is expected to look.
 *
 * <p>One row per stored enchantment, with the two vanilla cost slots — the item of
 * slot 1 (the price slot: its amount is that enchantment's own price, set on the
 * enchantments tab) and the item + amount of slot 2. An empty item means "this slot
 * is not required"; the amount is {@code -1} for "no limit", {@code 0} for "not
 * required" and {@code n} for a cap. Extra slots a modded trade object may carry
 * (slot 3 and beyond) are config-file only, and are preserved verbatim when a row is
 * saved, so a hand-written slot is never lost or shifted.
 *
 * <p>The fixed header holds the shared "air item" list: an item named there is not a
 * cost on either side, so a datapack that spells a free slot as e.g. minecraft:air
 * simply works.
 *
 * <p>Drafts commit on focus loss / screen close, like every other tab — typed text
 * never reaches the config while it is being typed.
 */
public class OffersListWidget extends AbstractSelectionList<OffersListWidget.Row> {

	private static final int FIELD_GAP = 4;
	private static final int COST_WIDTH = 92;
	private static final int COUNT_WIDTH = 30;
	/** Header band: the air list lives here, above the column labels. */
	private static final int AIR_Y = 28;

	/** The air-item list, edited as one comma separated field. Rendered and handled by
	 * this list itself (it sits in the fixed header, outside the rows). */
	private final EditBox airField;

	public OffersListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		super(client, width, height, top, itemHeight);

		this.airField = new EditBox(client.font, 8, AIR_Y,
				Math.max(60, this.width - 140), 14, Component.literal("air items"));
		this.airField.setMaxLength(256);
		this.airField.setValue(String.join(", ", TradeMasterConfig.INSTANCE.airItems));
		for (var entry : TradeMasterConfig.INSTANCE.enchantments.entrySet()) {
			this.addEntry(new OfferRow(entry.getKey()));
		}
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		Font font = Minecraft.getInstance().font;
		context.fill(5, AIR_Y - 1, this.width - 5, AIR_Y + 15, 0x1AC7C0C0);
		context.text(font, Component.translatable("trademaster.column.air_items"), 8, AIR_Y + 4, 0xFF202020);
		context.text(font, Component.translatable("trademaster.air.hint"),
				this.airField.getX() + this.airField.getWidth() + 6, AIR_Y + 4, 0xFF606060);
		this.airField.extractRenderState(context, 0, 0, 0.0f);

		int right = this.getRowLeft() + this.getRowWidth() - 2;
		context.text(font, Component.translatable("trademaster.column.cost_a"),
				right - COUNT_WIDTH - FIELD_GAP - COST_WIDTH * 2 - FIELD_GAP + 2, 48, 0xFF202020);
		context.text(font, Component.translatable("trademaster.column.cost_b"),
				right - COUNT_WIDTH - FIELD_GAP - COST_WIDTH + 2, 48, 0xFF202020);
		context.text(font, Component.translatable("trademaster.column.cost_count"),
				right - COUNT_WIDTH + 2, 48, 0xFF202020);
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
		// the scrollbar sits on the widget's right edge, outside the bounds the Screen
		// uses to route mouse events: claim it explicitly
		return super.isMouseOver(x, y) || this.isOverScrollbar(x, y);
	}

	@Override
	public int getRowTop(int index) {
		return this.getY() + 2 - (int) this.scrollAmount() + index * this.defaultEntryHeight;
	}

	@Override
	public int getRowLeft() {
		return this.getX() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	/** Commits the air list plus every row's in-flight offer draft. */
	public void flushEdits() {
		applyAirField();
		for (Row row : this.children()) row.flushEdits();
	}

	/** Writes the comma separated air list back, dropping tokens that are not item ids
	 * (the field is re-synced so an unusable token cannot linger). */
	private void applyAirField() {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		if (this.airField.getValue().equals(String.join(", ", config.airItems))) return;
		List<String> items = new ArrayList<>();
		for (String token : this.airField.getValue().split("[,\\s]+")) {
			if (token.isBlank()) continue;
			Identifier id = Identifier.tryParse(token.trim());
			if (id != null && !items.contains(id.toString())) items.add(id.toString());
		}
		config.airItems.clear();
		config.airItems.addAll(items);
		this.airField.setValue(String.join(", ", config.airItems));
		config.save();
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (this.airField.isFocused()) return this.airField.keyPressed(input) || super.keyPressed(input);
		for (OfferRow row : rows()) {
			EditBox focused = row.focusedField();
			if (focused != null) return focused.keyPressed(input);
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		if (this.airField.isFocused()) this.airField.keyReleased(input);
		for (OfferRow row : rows()) {
			for (EditBox field : row.fields()) {
				if (field.isFocused()) field.keyReleased(input);
			}
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (this.airField.isFocused()) return this.airField.charTyped(input) || super.charTyped(input);
		if (!input.isAllowedChatCharacter()) return false;
		boolean consumed = false;
		for (OfferRow row : rows()) {
			for (EditBox field : row.fields()) {
				consumed |= field.charTyped(input);
			}
		}
		// consuming matters: the screen falls back to the list and would deliver the
		// same character a second time
		return consumed || super.charTyped(input);
	}

	/** IME preedit for the field being edited (see EnchantmentsListWidget). */
	@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		if (this.airField.isFocused()) return this.airField.preeditUpdated(input);
		for (OfferRow row : rows()) {
			EditBox focused = row.focusedField();
			if (focused != null) return focused.preeditUpdated(input);
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		// the air field lives in the fixed header, outside the rows, so this list owns
		// its clicks (an EditBox does not take focus on its own)
		if (this.airField.isMouseOver(click.x(), click.y())) {
			this.airField.setFocused(true);
			return this.airField.mouseClicked(click, doubled);
		}
		this.airField.setFocused(false);
		// commit + unfocus every field the click did NOT land in, or two fields could
		// hold focus at once and the keystrokes would reach the wrong row
		for (OfferRow row : rows()) {
			for (EditBox field : row.fields()) {
				if (!field.isMouseOver(click.x(), click.y())) {
					field.setFocused(false);
				}
			}
			row.commit();
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = this.scrollAmount();
		super.setScrollAmount(amount);
		if (this.scrollAmount() == before) return;
		// a focused field scrolled off-screen keeps swallowing keystrokes
		for (OfferRow row : rows()) {
			EditBox focused = row.focusedField();
			if (focused != null) {
				row.commit();
				focused.setFocused(false);
			}
		}
	}

	private List<OfferRow> rows() {
		List<OfferRow> result = new ArrayList<>();
		for (Row row : this.children()) {
			if (row instanceof OfferRow offer) result.add(offer);
		}
		return result;
	}

	/** Base type so the list can hold the offer rows. */
	public abstract static class Row extends AbstractSelectionList.Entry<Row> {
		protected int x, y, entryWidth, entryHeight;

		void flushEdits() {}
	}

	public static class OfferRow extends Row {

		private final Identifier id;
		private final String translationKey;
		private final EditBox costAField;
		private final EditBox costBField;
		private final EditBox countField;
		/** Unusable input, shown in red until the text changes again. */
		private @Nullable String errorKey = null;

		public OfferRow(Identifier id) {
			this.id = id;
			this.translationKey = "enchantment." + id.getNamespace() + "." + id.getPath();
			TradeOfferSpec spec = TradeMasterConfig.INSTANCE.specFor(id);
			TradeOfferSpec.TradeCost a = spec.costAt(0);
			TradeOfferSpec.TradeCost b = spec.costAt(1);
			this.costAField = field(COST_WIDTH, "costA", a == null ? "" : a.item().toString());
			this.costBField = field(COST_WIDTH, "costB", b == null ? "" : b.item().toString());
			this.countField = field(COUNT_WIDTH, "count", b == null ? "-1" : String.valueOf(b.count()));
		}

		private static EditBox field(int width, String name, String value) {
			EditBox box = new EditBox(Minecraft.getInstance().font, 0, 0, width, 14, Component.literal(name));
			box.setMaxLength(128);
			box.setValue(value);
			box.setResponder(text -> {});
			return box;
		}

		public EditBox[] fields() {
			return new EditBox[]{this.costAField, this.costBField, this.countField};
		}

		private @Nullable EditBox focusedField() {
			for (EditBox field : fields()) {
				if (field.isFocused()) return field;
			}
			return null;
		}

		/** Writes the row's draft, refusing (and flagging) unusable input. */
		void commit() {
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			Identifier itemA = parseItem(this.costAField.getValue());
			Identifier itemB = parseItem(this.costBField.getValue());
			Integer count = parseCount(this.countField.getValue());
			boolean badItem = (!this.costAField.getValue().isBlank() && itemA == null)
					|| (!this.costBField.getValue().isBlank() && itemB == null);
			if (badItem || count == null) {
				this.errorKey = badItem ? "trademaster.offer.invalid_item" : "trademaster.offer.invalid_count";
				return;
			}
			this.errorKey = null;
			TradeOfferSpec existing = config.tradeSpecs.get(this.id);
			// slot 1's stored amount is ignored (it is the enchantment's price); slot 2
			// carries the entered cap. Anything the file declares beyond slot 2 is kept
			// as it is, so a hand-written slot 3+ is never lost or shifted
			List<TradeOfferSpec.TradeCost> costs = new ArrayList<>();
			costs.add(new TradeOfferSpec.TradeCost(itemA == null ? TradeOfferSpec.AIR : itemA, 0));
			costs.add(new TradeOfferSpec.TradeCost(itemB == null ? TradeOfferSpec.AIR : itemB, count));
			if (existing != null) {
				List<TradeOfferSpec.TradeCost> declared = existing.costs();
				for (int slot = 2; slot < declared.size(); slot++) {
					costs.add(declared.get(slot));
				}
			}
			TradeOfferSpec updated = new TradeOfferSpec(List.copyOf(costs),
					existing == null ? TradeOfferSpec.ENCHANTED_BOOK : existing.result());
			if (updated.equals(existing)) return;
			config.tradeSpecs.put(this.id, updated);
			config.save();
		}

		@Override
		void flushEdits() {
			this.commit();
		}

		private static @Nullable Identifier parseItem(String text) {
			if (text == null || text.isBlank()) return null;
			String value = text.trim();
			// a namespaceless "book" means minecraft:book, like everywhere else
			Identifier id = value.indexOf(':') >= 0
					? Identifier.tryParse(value)
					: Identifier.tryParse("minecraft:" + value);
			return id;
		}

		private static @Nullable Integer parseCount(String text) {
			if (text == null || text.isBlank()) return TradeOfferSpec.UNLIMITED;
			try {
				int value = Integer.parseInt(text.trim());
				if (value < TradeOfferSpec.UNLIMITED || value > 64) return null;
				return value;
			} catch (NumberFormatException e) {
				return null;
			}
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			Font font = Minecraft.getInstance().font;
			this.x = this.getX();
			this.y = this.getY();
			this.entryWidth = this.getWidth();
			this.entryHeight = this.getHeight();
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, 0x1AC7C0C0);

			int right = this.x + this.entryWidth - 2;
			int countX = right - COUNT_WIDTH;
			int costBX = countX - FIELD_GAP - COST_WIDTH;
			int costAX = costBX - FIELD_GAP - COST_WIDTH;
			String name = EnchantmentNamer.displayName(this.id, this.translationKey);
			int nameWidth = Math.max(20, costAX - FIELD_GAP - (this.x + 4));
			context.text(font, Component.literal(fit(font, name, nameWidth)), this.x + 4, this.y + 6, 0xFFFFFFFF);
			if (this.errorKey != null) {
				context.text(font, Component.translatable(this.errorKey), this.x + 4, this.y + 16, 0xFFFF5555);
			}
			place(context, this.costAField, costAX, mouseX, mouseY, deltaTicks);
			place(context, this.costBField, costBX, mouseX, mouseY, deltaTicks);
			place(context, this.countField, countX, mouseX, mouseY, deltaTicks);
		}

		private static String fit(Font font, String text, int width) {
			if (font.width(text) <= width) return text;
			String ellipsis = "…";
			String result = text;
			while (!result.isEmpty() && font.width(result + ellipsis) > width) {
				result = result.substring(0, result.length() - 1);
			}
			return result + ellipsis;
		}

		private void place(GuiGraphicsExtractor context, EditBox field, int x,
				int mouseX, int mouseY, float deltaTicks) {
			field.setX(x);
			field.setY(this.y + 3);
			context.pose().pushMatrix();
			field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			for (EditBox field : fields()) {
				if (field.isMouseOver(click.x(), click.y()) && field.mouseClicked(click, doubled)) {
					field.setFocused(true);
					return true;
				}
			}
			return false;
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			for (EditBox field : fields()) {
				if (field.isFocused() && field.mouseDragged(drag, mouseX, mouseY)) return true;
			}
			return false;
		}
	}
}
