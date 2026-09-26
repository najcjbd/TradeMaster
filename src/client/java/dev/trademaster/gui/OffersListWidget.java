package dev.trademaster.gui;

import dev.trademaster.config.GoodSpec;
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
 * The offers tab: how each target's trade is expected to look, plus the custom goods
 * the player wants to reroll for.
 *
 * <p><b>Enchantment rows</b> carry the two vanilla cost slots — the item of slot 1
 * (the price slot: its amount is that enchantment's own price, set on the enchantments
 * tab) and the item + amount of slot 2. <b>Good rows</b> add the result amount, the
 * good's price cap, an enable toggle and a remove button. A good is identified by its
 * result item, so that cannot be edited in place: remove the row and add it again.
 *
 * <p>An empty item means "this slot is not required"; an amount is {@code -1} for "no
 * limit", {@code 0} for "not required" and {@code n} for a cap. Extra cost slots a
 * modded trade object may carry (slot 3 and beyond) are config-file only, and are
 * preserved verbatim when a row is saved.
 *
 * <p>The fixed header holds the shared "air item" list: an item named there is not a
 * cost on either side. Drafts commit on focus loss / screen close, like every other
 * tab; every field width shrinks with the row, so a narrow screen cannot overflow.
 */
public class OffersListWidget extends AbstractSelectionList<OffersListWidget.Row> {

	static final int FIELD_GAP = 4;
	static final int MIN_FIELD = 22;
	static final int NUM_WIDTH = 26;
	/** Header band: the air list lives here, above the column labels. */
	private static final int AIR_Y = 28;

	private final EditBox airField;
	/** Asked for a rebuild after a good is added/removed/toggled (the row list changed). */
	private final Runnable rebuild;

	public OffersListWidget(Minecraft client, int width, int height, int top, int itemHeight, Runnable rebuild) {
		super(client, width, height, top, itemHeight);
		this.rebuild = rebuild;

		this.airField = new EditBox(client.font, 8, AIR_Y,
				Math.max(60, this.width - 140), 14, Component.literal("air items"));
		this.airField.setMaxLength(256);
		this.airField.setValue(String.join(", ", TradeMasterConfig.INSTANCE.airItems));
		for (var entry : TradeMasterConfig.INSTANCE.enchantments.entrySet()) {
			this.addEntry(new OfferRow(entry.getKey()));
		}
		this.addEntry(new SectionHeaderRow("trademaster.offers.goods_header"));
		for (GoodSpec good : TradeMasterConfig.INSTANCE.goods) {
			this.addEntry(new GoodRow(good, this.rebuild));
		}
		this.addEntry(new AddGoodRow(this.rebuild));
	}

	/** Item field width for the enchantment rows at this row width. */
	static int costWidthFor(int entryWidth) {
		return Math.max(MIN_FIELD, (entryWidth - NUM_WIDTH - FIELD_GAP * 3 - 70) / 2);
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		Font font = Minecraft.getInstance().font;
		context.fill(5, AIR_Y - 1, this.width - 5, AIR_Y + 15, 0x1AC7C0C0);
		context.text(font, Component.translatable("trademaster.column.air_items"), 8, AIR_Y + 4, 0xFF202020);
		int hintX = this.airField.getX() + this.airField.getWidth() + 6;
		int hintWidth = this.width - hintX - 4;
		if (hintWidth >= 8) {
			String hint = Component.translatable("trademaster.air.hint").getString();
			context.text(font, Component.literal(Row.fit(font, hint, hintWidth)), hintX, AIR_Y + 4, 0xFF606060);
		}
		this.airField.extractRenderState(context, 0, 0, 0.0f);

		// the labels line up with the enchantment rows' three fields
		int right = this.getRowLeft() + this.getRowWidth() - 2;
		int costWidth = costWidthFor(this.getRowWidth());
		context.text(font, Component.translatable("trademaster.column.cost_a"),
				right - NUM_WIDTH - FIELD_GAP - costWidth * 2 - FIELD_GAP * 2 + 2, 48, 0xFF202020);
		context.text(font, Component.translatable("trademaster.column.cost_b"),
				right - NUM_WIDTH - FIELD_GAP - costWidth + 2, 48, 0xFF202020);
		context.text(font, Component.translatable("trademaster.column.cost_count"),
				right - NUM_WIDTH + 2, 48, 0xFF202020);
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

	/** Commits the air list plus every row's in-flight draft. */
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
			// ids are lowercase: "AIR" must become minecraft:air instead of being dropped
			Identifier id = Identifier.tryParse(token.trim().toLowerCase(java.util.Locale.ROOT));
			if (id != null && !items.contains(id.toString())) items.add(id.toString());
		}
		config.airItems.clear();
		config.airItems.addAll(items);
		this.airField.setValue(String.join(", ", config.airItems));
		config.save();
	}

	private List<EditBox> allFields() {
		List<EditBox> fields = new ArrayList<>();
		for (Row row : this.children()) {
			for (EditBox field : row.fields()) fields.add(field);
		}
		return fields;
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (this.airField.isFocused()) return this.airField.keyPressed(input) || super.keyPressed(input);
		for (EditBox field : allFields()) {
			if (field.isFocused()) return field.keyPressed(input);
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		if (this.airField.isFocused()) this.airField.keyReleased(input);
		for (EditBox field : allFields()) {
			if (field.isFocused()) field.keyReleased(input);
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (this.airField.isFocused()) return this.airField.charTyped(input) || super.charTyped(input);
		if (!input.isAllowedChatCharacter()) return false;
		boolean consumed = false;
		for (EditBox field : allFields()) {
			consumed |= field.charTyped(input);
		}
		// consuming matters: the screen falls back to the list and would deliver the
		// same character a second time
		return consumed || super.charTyped(input);
	}

	/** IME preedit for the field being edited (see EnchantmentsListWidget). */
	@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		if (this.airField.isFocused()) return this.airField.preeditUpdated(input);
		for (EditBox field : allFields()) {
			if (field.isFocused()) return field.preeditUpdated(input);
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		// the air field lives in the fixed header, outside the rows, so this list owns
		// its clicks (an EditBox does not take focus on its own)
		// blur + commit the rows first, whoever the click belongs to: returning early
		// for the air field used to leave a row field focused as well (two focuses)
		for (Row row : this.children()) {
			for (EditBox field : row.fields()) {
				if (!field.isMouseOver(click.x(), click.y())) field.setFocused(false);
			}
			if (!row.clickedIn(click)) row.commit();
		}
		if (this.airField.isMouseOver(click.x(), click.y())) {
			this.airField.setFocused(true);
			return this.airField.mouseClicked(click, doubled);
		}
		this.airField.setFocused(false);
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = this.scrollAmount();
		super.setScrollAmount(amount);
		if (this.scrollAmount() == before) return;
		// a focused field scrolled off-screen keeps swallowing keystrokes
		for (Row row : this.children()) {
			EditBox focused = row.focusedField();
			if (focused != null) {
				row.commit();
				focused.setFocused(false);
			}
		}
	}

	/** Base type so the list can hold rows of several kinds. */
	public abstract static class Row extends AbstractSelectionList.Entry<Row> {
		protected int x, y, entryWidth, entryHeight;

		public EditBox[] fields() {
			return new EditBox[0];
		}

		/** The screen's flush chains call this; the rows write through commit(). */
		void flushEdits() {
			commit();
		}

		/** Writes the row's draft; a no-op for rows without editable state. */
		void commit() {}

		/** Whether the click landed on this row (used to skip committing it). */
		boolean clickedIn(MouseButtonEvent click) {
			return click.x() > this.x && click.x() < this.x + this.entryWidth
					&& click.y() > this.y && click.y() < this.y + this.entryHeight - 4;
		}

		@Nullable
		EditBox focusedField() {
			for (EditBox field : fields()) {
				if (field.isFocused()) return field;
			}
			return null;
		}

		protected void prepare(GuiGraphicsExtractor context) {
			this.x = this.getX();
			this.y = this.getY();
			this.entryWidth = this.getWidth();
			this.entryHeight = this.getHeight();
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, 0x1AC7C0C0);
		}

		protected void place(GuiGraphicsExtractor context, EditBox field, int x, int width,
				int mouseX, int mouseY, float deltaTicks) {
			// the layout is the only authority on the width: re-raising it to MIN_FIELD
			// here undid the narrow-row clamp and made the fields overlap
			field.setWidth(Math.max(10, width));
			field.setX(x);
			field.setY(this.y + 3);
			context.pose().pushMatrix();
			field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
		}

		protected static String fit(Font font, String text, int width) {
			if (font.width(text) <= width) return text;
			String result = text;
			while (!result.isEmpty() && font.width(result + "…") > width) {
				result = result.substring(0, result.length() - 1);
			}
			return result + "…";
		}

		protected static @Nullable Identifier parseItem(String text) {
			if (text == null || text.isBlank()) return null;
			String value = text.trim();
			// a namespaceless "book" means minecraft:book, like everywhere else
			return value.indexOf(':') >= 0
					? Identifier.tryParse(value)
					: Identifier.tryParse("minecraft:" + value);
		}

		/** -1 (no limit), 0 (not required) or 1..64; null when the text is not usable. */
		protected static @Nullable Integer parseCount(String text) {
			if (text == null || text.isBlank()) return TradeOfferSpec.UNLIMITED;
			try {
				int value = Integer.parseInt(text.trim());
				if (value < TradeOfferSpec.UNLIMITED || value > 64) return null;
				return value;
			} catch (NumberFormatException e) {
				return null;
			}
		}

		protected static EditBox field(String name, String value) {
			EditBox box = new EditBox(Minecraft.getInstance().font, 0, 0, 40, 14, Component.literal(name));
			box.setMaxLength(128);
			box.setValue(value);
			box.setResponder(text -> {});
			return box;
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

	/** A plain band with a label: separates the enchantment rows from the goods. */
	private static class SectionHeaderRow extends Row {
		private final String labelKey;

		SectionHeaderRow(String labelKey) {
			this.labelKey = labelKey;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			prepare(context);
			Font font = Minecraft.getInstance().font;
			String label = Component.translatable(this.labelKey).getString();
			context.text(font, Component.literal(fit(font, label, Math.max(0, this.entryWidth - 8))),
					this.x + 4, this.y + 6, 0xFF404040);
		}
	}

	/** One enchantment: the Cost A item, the Cost B item and its amount. */
	public static class OfferRow extends Row {

		private final Identifier id;
		/** Per-frame cache: the name only changes when the row is rebuilt, and fit()
		 * cuts character by character (see SettingsListWidget's caches). */
		private @Nullable String nameCache = null;
		private int nameCacheWidth = -1;
		private Component nameText = Component.empty();
		private final EditBox costAField;
		private final EditBox costBField;
		private final EditBox countField;
		private @Nullable String errorKey = null;

		public OfferRow(Identifier id) {
			this.id = id;
			TradeOfferSpec spec = TradeMasterConfig.INSTANCE.specFor(id);
			TradeOfferSpec.TradeCost a = spec.costAt(0);
			TradeOfferSpec.TradeCost b = spec.costAt(1);
			this.costAField = field("costA", a == null ? "" : a.item().toString());
			this.costBField = field("costB", b == null ? "" : b.item().toString());
			this.countField = field("count", b == null ? "-1" : String.valueOf(b.count()));
		}

		@Override
		public EditBox[] fields() {
			return new EditBox[]{this.costAField, this.costBField, this.countField};
		}

		@Override
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
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			prepare(context);
			Font font = Minecraft.getInstance().font;
			int costWidth = costWidthFor(this.entryWidth);
			int countX = this.x + this.entryWidth - 2 - NUM_WIDTH;
			int costBX = countX - FIELD_GAP - costWidth;
			int costAX = costBX - FIELD_GAP - costWidth;
			int nameWidth = Math.max(20, costAX - FIELD_GAP - (this.x + 4));
			if (this.nameCache == null) this.nameCache = EnchantmentNamer.demandDisplayName(this.id);
			if (nameWidth != this.nameCacheWidth) {
				this.nameCacheWidth = nameWidth;
				this.nameText = Component.literal(fit(font, this.nameCache, nameWidth));
			}
			context.text(font, this.nameText, this.x + 4, this.y + 6, 0xFFFFFFFF);
			if (this.errorKey != null) {
				context.text(font, Component.translatable(this.errorKey), this.x + 4, this.y + 16, 0xFFFF5555);
			}
			place(context, this.costAField, costAX, costWidth, mouseX, mouseY, deltaTicks);
			place(context, this.costBField, costBX, costWidth, mouseX, mouseY, deltaTicks);
			place(context, this.countField, countX, NUM_WIDTH, mouseX, mouseY, deltaTicks);
		}
	}

	/** One custom good. The result item is the good's identity, so it is shown as a
	 * label; the row edits its amount, its price cap and its cost slots, and carries an
	 * enable toggle plus a remove button on the right. */
	public static class GoodRow extends Row {

		private final Identifier id;
		private final @Nullable Runnable rebuild;
		/** See OfferRow's note: the fitted name is cached per width. */
		private @Nullable String nameCache = null;
		private int nameCacheWidth = -1;
		private Component nameText = Component.empty();
		private final EditBox resultCountField;
		private final EditBox priceField;
		private final EditBox costAField;
		private final EditBox costBField;
		private final EditBox countField;
		private @Nullable String errorKey = null;

		public GoodRow(GoodSpec good, @Nullable Runnable rebuild) {
			this.id = good.id();
			this.rebuild = rebuild;
			TradeOfferSpec.TradeCost a = good.spec().costAt(0);
			TradeOfferSpec.TradeCost b = good.spec().costAt(1);
			this.resultCountField = field("amount", String.valueOf(good.resultCount()));
			this.priceField = field("price", String.valueOf(good.maxPrice()));
			this.costAField = field("costA", a == null ? "" : a.item().toString());
			this.costBField = field("costB", b == null ? "" : b.item().toString());
			this.countField = field("count", b == null ? "-1" : String.valueOf(b.count()));
		}

		@Override
		public EditBox[] fields() {
			return new EditBox[]{this.resultCountField, this.priceField,
					this.costAField, this.costBField, this.countField};
		}

		@Override
		void commit() {
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			Integer amount = parseCount(this.resultCountField.getValue());
			Integer price = parseCount(this.priceField.getValue());
			Identifier itemA = parseItem(this.costAField.getValue());
			Identifier itemB = parseItem(this.costBField.getValue());
			Integer count = parseCount(this.countField.getValue());
			boolean badItem = (!this.costAField.getValue().isBlank() && itemA == null)
					|| (!this.costBField.getValue().isBlank() && itemB == null);
			boolean badNumber = amount == null || amount < 1 || price == null || price < 0 || count == null;
			if (badItem || badNumber) {
				this.errorKey = badItem ? "trademaster.offer.invalid_item" : "trademaster.offer.invalid_count";
				return;
			}
			this.errorKey = null;
			GoodSpec existing = config.goodFor(this.id);
			if (existing == null) return;
			List<TradeOfferSpec.TradeCost> costs = new ArrayList<>();
			costs.add(new TradeOfferSpec.TradeCost(itemA == null ? TradeOfferSpec.AIR : itemA, 0));
			costs.add(new TradeOfferSpec.TradeCost(itemB == null ? TradeOfferSpec.AIR : itemB, count));
			// slots 3+ are config-file only and must survive a save
			List<TradeOfferSpec.TradeCost> declared = existing.costs();
			for (int slot = 2; slot < declared.size(); slot++) {
				costs.add(declared.get(slot));
			}
			GoodSpec updated = new GoodSpec(this.id, amount, existing.enabled(), price, List.copyOf(costs));
			if (updated.equals(existing)) return;
			config.goods.set(config.goods.indexOf(existing), updated);
			config.save();
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (super.mouseClicked(click, doubled)) return true;
			if (click.y() <= this.y || click.y() >= this.y + this.entryHeight - 4) return false;
			int removeX = this.x + this.entryWidth - 2 - 16;
			int toggleX = removeX - 2 - 14;
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			GoodSpec existing = config.goodFor(this.id);
			if (existing == null) return false;
			if (click.x() >= removeX && click.x() < removeX + 16) {
				config.goods.remove(existing);
				config.save();
				if (this.rebuild != null) this.rebuild.run();
				return true;
			}
			if (click.x() >= toggleX && click.x() < toggleX + 14) {
				config.goods.set(config.goods.indexOf(existing), existing.withEnabled(!existing.enabled()));
				config.save();
				return true;
			}
			return false;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			prepare(context);
			Font font = Minecraft.getInstance().font;
			GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(this.id);
			boolean enabled = good == null || good.enabled();
			int right = this.x + this.entryWidth - 2;
			int removeX = right - 16;
			int toggleX = removeX - 2 - 14;
			int countX = toggleX - FIELD_GAP - NUM_WIDTH;
			int priceX = countX - FIELD_GAP - NUM_WIDTH;
			int amountX = priceX - FIELD_GAP - NUM_WIDTH;
			int costWidth = Math.max(MIN_FIELD, (amountX - FIELD_GAP - (this.x + 4) - 56) / 3);
			int costBX = amountX - FIELD_GAP - costWidth;
			int costAX = costBX - FIELD_GAP - costWidth;
			if (costAX < this.x + 4) {
				// a narrow row: shrink the two item fields instead of pushing cost A out
				// of the row (it used to land at a negative x and overlap the label)
				costWidth = Math.max(10, (amountX - FIELD_GAP - (this.x + 4) - FIELD_GAP * 2) / 2);
				costBX = amountX - FIELD_GAP - costWidth;
				costAX = costBX - FIELD_GAP - costWidth;
			}
			int nameWidth = Math.max(0, costAX - FIELD_GAP - (this.x + 4));

			if (nameWidth >= 8) {
				if (this.nameCache == null) this.nameCache = EnchantmentNamer.demandDisplayName(this.id);
				if (nameWidth != this.nameCacheWidth) {
					this.nameCacheWidth = nameWidth;
					this.nameText = Component.literal(fit(font, this.nameCache, nameWidth));
				}
				context.text(font, this.nameText, this.x + 4, this.y + 6,
						enabled ? 0xFFFFFFFF : 0xFF808080);
			}
			if (this.errorKey != null) {
				context.text(font, Component.translatable(this.errorKey), this.x + 4, this.y + 16, 0xFFFF5555);
			}
			context.text(font, Component.literal(enabled ? "\u25A3" : "\u25A2"), toggleX + 2, this.y + 6,
					enabled ? 0xFF00C000 : 0xFF808080);
			context.text(font, Component.literal("\u2715"), removeX + 4, this.y + 6, 0xFFFF5555);
			place(context, this.costAField, costAX, costWidth, mouseX, mouseY, deltaTicks);
			place(context, this.costBField, costBX, costWidth, mouseX, mouseY, deltaTicks);
			place(context, this.resultCountField, amountX, NUM_WIDTH, mouseX, mouseY, deltaTicks);
			place(context, this.priceField, priceX, NUM_WIDTH, mouseX, mouseY, deltaTicks);
			place(context, this.countField, countX, NUM_WIDTH, mouseX, mouseY, deltaTicks);
		}
	}

	/** Adds a good: type the result item id and click the plus. */
	public static class AddGoodRow extends Row {
		private final EditBox resultField;
		private @Nullable String errorKey = null;

		private final @Nullable Runnable rebuild;

		public AddGoodRow(@Nullable Runnable rebuild) {
			this.rebuild = rebuild;
			this.resultField = field("result", "");
			this.resultField.setResponder(text -> this.errorKey = null);
		}

		@Override
		public EditBox[] fields() {
			return new EditBox[]{this.resultField};
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (super.mouseClicked(click, doubled)) return true;
			int plusX = this.x + this.entryWidth - 2 - 14;
			if (click.y() > this.y && click.y() < this.y + this.entryHeight - 4
					&& click.x() >= plusX && click.x() < plusX + 14) {
				return this.add();
			}
			return false;
		}

		/** Creates the good, refusing an unusable or already used result item. */
		private boolean add() {
			Identifier result = parseItem(this.resultField.getValue());
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			if (result == null || result.equals(TradeOfferSpec.AIR)) {
				this.errorKey = "trademaster.offer.invalid_item";
				return true;
			}
			if (result.equals(TradeOfferSpec.ENCHANTED_BOOK)) {
				// enchanted books are the enchantment tab's business: a good with that
				// result would never match anything
				this.errorKey = "trademaster.offer.invalid_item";
				return true;
			}
			if (config.goodFor(result) != null) {
				this.errorKey = "trademaster.offer.good_exists";
				return true;
			}
			if (resultItemMissing(result)) {
				this.errorKey = "trademaster.offer.unknown_item";
				return true;
			}
			// a good pays the price slot in emeralds by default and requires no second
			// cost until one is entered; the result item IS its identity
			List<TradeOfferSpec.TradeCost> costs = new ArrayList<>();
			costs.add(new TradeOfferSpec.TradeCost(TradeOfferSpec.EMERALD, 0));
			costs.add(new TradeOfferSpec.TradeCost(TradeOfferSpec.AIR, TradeOfferSpec.UNLIMITED));
			config.goods.add(new GoodSpec(result, 1, true, 64, List.copyOf(costs)));
			this.resultField.setValue("");
			this.errorKey = null;
			config.save();
			// the new row only appears after a rebuild
			if (this.rebuild != null) this.rebuild.run();
			return true;
		}

		/** Whether the id names no item at all (the registry is up once a screen is open). */
		private static boolean resultItemMissing(Identifier id) {
			return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id).isEmpty();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			prepare(context);
			Font font = Minecraft.getInstance().font;
			int plusX = this.x + this.entryWidth - 2 - 14;
			int fieldWidth = Math.max(40, Math.min(100, plusX - FIELD_GAP - (this.x + 4) - 60));
			int fieldX = plusX - FIELD_GAP - fieldWidth;
			String label = Component.translatable("trademaster.offers.add_good").getString();
			context.text(font, Component.literal(fit(font, label, Math.max(0, fieldX - FIELD_GAP - (this.x + 4)))),
					this.x + 4, this.y + 6, 0xFFE0E000);
			context.text(font, Component.literal("+"), plusX + 4, this.y + 6, 0xFF00C000);
			if (this.errorKey != null) {
				context.text(font, Component.translatable(this.errorKey), this.x + 4, this.y + 16, 0xFFFF5555);
			}
			place(context, this.resultField, fieldX, fieldWidth, mouseX, mouseY, deltaTicks);
		}
	}
}
