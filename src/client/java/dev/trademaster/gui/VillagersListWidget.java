package dev.trademaster.gui;

import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.strategy.RigTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The "Villagers" tab: the rig's job-site block, the profession it should take, and the
 * professions that can never trade. Each row edits text; drafts commit on a deliberate focus
 * change (click elsewhere) or when the screen closes; an involuntary scroll only blurs.
 *
 * <p>Ids are stored lowercase, and a namespaceless value means {@code minecraft:}. A row whose
 * text names nothing (unknown or empty) shows a red hint instead of the config being silently
 * broken, and the chosen profession may not be one that can never trade.
 */
public class VillagersListWidget extends AbstractSelectionList<VillagersListWidget.Row> {

	private static final int FIELD_WIDTH = 150;
	private static final int FIELD_GAP = 6;
	private static final int ID_MAX_LENGTH = 128;
	private static final int LIST_MAX_LENGTH = 1024;

	/** Set while the list dispatches a click: vanilla focuses the clicked entry AFTER the
	 * entry handled the click, and that focus move can scroll the viewport and thus run the
	 * scroll-blur — which would undo the focus the click just granted. Mirrors the other tabs. */
	private boolean suppressFocusClear = false;

	public VillagersListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		super(client, width, height, top, itemHeight);
		this.addEntry(new IdRow("trademaster.column.workBlock", TradeMasterConfig.INSTANCE.workBlockId,
				ID_MAX_LENGTH, "trademaster.villagers.unknown_block", VillagersListWidget::workBlockError,
				() -> TradeMasterConfig.INSTANCE.workBlockId, VillagersListWidget::commitWorkBlock));
		this.addEntry(new IdRow("trademaster.column.profession", TradeMasterConfig.INSTANCE.professionId,
				ID_MAX_LENGTH, "trademaster.villagers.unknown_profession", VillagersListWidget::professionError,
				() -> TradeMasterConfig.INSTANCE.professionId, VillagersListWidget::commitProfession));
		this.addEntry(new ListRow("trademaster.column.nonTradeable", LIST_MAX_LENGTH,
				VillagersListWidget::commitNonTradeable));
	}

	/** Writes the work block, clearing the tool lock when it actually changed (the lock
	 * was chosen for the previous block). */
	private static void commitWorkBlock(String value) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		if (config.workBlockId.equals(value)) return;
		config.workBlockId = value;
		RigTarget.clearToolLock();
		config.save();
	}

	private static void commitProfession(String value) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		if (config.professionId.equals(value)) return;
		config.professionId = value;
		config.save();
	}

	/** Replaces the non-tradeable list with the parsed ids (an empty text clears it). */
	private static void commitNonTradeable(String value) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		List<String> ids = parseList(value);
		if (ids == null) return;
		if (ids.equals(config.nonTradeableProfessions)) return;
		config.nonTradeableProfessions.clear();
		config.nonTradeableProfessions.addAll(ids);
		config.save();
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		// the tab's one constraint, shown once under the rows: only the profession id is
		// configurable, the trade shape is not
		Font font = Minecraft.getInstance().font;
		int rows = this.children().size();
		if (rows == 0) return;
		// just under the last row: the vanilla background pass runs BEFORE the rows, so a
		// hint at the widget's bottom could be painted over by a row on a short screen
		int y = this.getRowTop(rows - 1) + this.defaultEntryHeight + 2;
		if (y + 9 > this.getY() + this.getHeight()) return;
		String hint = Component.translatable("trademaster.villagers.hint").getString();
		context.text(font, Component.literal(fit(font, hint, this.getWidth() - 6)),
				this.getX() + 3, y, 0xFF9A9A9A);
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

	/** Commits every row's in-flight draft (screen close / tab switch / save / start). */
	public void flushEdits() {
		for (Row row : this.children()) row.flushEdits();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		// right-click is not an edit gesture: do not reconcile/blur/commit drafts
		if (click.button() != 0) return super.mouseClicked(click, doubled);
		// a field the pointer is NOT on commits its draft and loses focus; the field under
		// the pointer is left alone (the pointer's own click focuses it)
		for (Row row : this.children()) {
			if (!row.field.isMouseOver(click.x(), click.y())) {
				row.reconcileOnFocusLoss();
				row.field.setFocused(false);
			}
		}
		// vanilla moves focus to the clicked entry AFTER this, which can scroll and blur
		// the field the click just focused: suppress the blur for the duration
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
		// scroll only drops focus: an involuntary blur must not COMMIT a half-typed id
		for (Row row : this.children()) row.clearFieldFocus();
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		for (Row row : this.children()) {
			if (row.field.isFocused()) return row.field.keyPressed(input);
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		for (Row row : this.children()) {
			if (row.field.isFocused()) row.field.keyReleased(input);
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (!input.isAllowedChatCharacter()) return false;
		for (Row row : this.children()) {
			if (row.field.isFocused() && row.field.charTyped(input)) return true;
		}
		return super.charTyped(input);
	}

	@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		for (Row row : this.children()) {
			if (row.field.isFocused()) return row.field.preeditUpdated(input);
		}
		return false;
	}

	/** Base type so the list can hold rows. */
	public abstract static class Row extends AbstractSelectionList.Entry<Row> {
		final EditBox field;
		protected int x, y, entryWidth, entryHeight;

		private final Consumer<String> setter;
		private boolean dirty = false;

		Row(String value, int maxLength, Consumer<String> setter) {
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, FIELD_WIDTH, 14,
					Component.literal("villager id"));
			this.field.setMaxLength(maxLength);
			this.field.setValue(value);
			this.field.setResponder(text -> this.dirty = true);
			this.setter = setter;
		}

		/** The canonical value this row would write, or null when the text is unusable. */
		abstract @Nullable String normalized();

		/** The row's red hint for the CURRENT field text, or null when it is usable. */
		abstract @Nullable String hintFor(String raw);

		abstract String storedValue();

		abstract String labelKey();

		void flushEdits() {
			commit();
		}

		private void commit() {
			if (!this.dirty) return;
			String value = normalized();
			if (value == null || hintFor(this.field.getValue()) != null) {
				// keep the draft (dirty) so a later fix still writes it; show the hint
				return;
			}
			// setValue always fires the responder (which would set dirty again): re-sync
			// the text first and clear the flag last
			if (!value.equals(this.field.getValue())) this.field.setValue(value);
			this.dirty = false;
			this.setter.accept(value);
		}

		/** Losing focus commits the draft; unusable text is restored from the config.
		 * Deliberately NOT gated on isFocused(), like the other tabs: the scroll path blurs
		 * WITHOUT committing, so this is the click path's "commit what the pointer left
		 * behind" (gating on focus made a scroll-blurred draft commit only at close). */
		void reconcileOnFocusLoss() {
			this.commit();
			String value = normalized();
			if (value == null || hintFor(this.field.getValue()) != null) {
				this.field.setValue(storedValue());
				this.dirty = false;
			}
		}

		/** Scroll path ONLY: blur without committing a half-typed id. */
		void clearFieldFocus() {
			this.field.setFocused(false);
		}

		/** A click that lands on the field focuses it (and is swallowed so the list does not
		 * also treat it as a row click). Without this the field could never take focus at
		 * all — the list's own handler only BLURS fields — so the tab would be uneditable.
		 * Every other row in this mod (Settings / Alias / Offers / Enchantments) does the
		 * same. */
		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return super.mouseClicked(click, doubled);
		}

		/** The drag lands on the row (the list's focused child), not the nested field:
		 * forward it so EditBox's onDrag text selection works, as the sibling rows do. */
		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

		@Override
		public boolean mouseReleased(MouseButtonEvent click) {
			return this.field.mouseReleased(click);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			this.x = this.getX();
			this.y = this.getY();
			this.entryWidth = this.getWidth();
			this.entryHeight = this.getHeight();
			Font font = Minecraft.getInstance().font;
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, 0x1AC7C0C0);
			context.text(font, Component.translatable(this.labelKey()), this.x + 4, this.y + 6, 0xFFE0E000);
			// clamp the field to what the row can actually hold: a fixed 150px wide field
			// would overflow a narrow (Android/FCL) screen and collide with the label
			int labelWidth = font.width(Component.translatable(this.labelKey()));
			int available = this.entryWidth - 4 - labelWidth - FIELD_GAP - 10;
			int fieldWidth = Math.max(40, Math.min(FIELD_WIDTH, available));
			this.field.setWidth(fieldWidth);
			int fieldX = this.x + this.entryWidth - fieldWidth - 10;
			this.field.setX(Math.max(this.x + 4, fieldX));
			this.field.setY(this.y + 2);
			context.pose().pushMatrix();
			this.field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
			String error = hintFor(this.field.getValue());
			if (error != null) {
				int errorX = this.field.getX() - 4;
				String text = Component.translatable(error).getString();
				context.text(font, Component.literal(fit(font, text, errorX - this.boxLeft())),
						this.boxLeft(), this.y + 6, 0xFFFF5555);
			}
		}

		private int boxLeft() {
			return this.x + 4 + Minecraft.getInstance().font.width(Component.translatable(this.labelKey()).getString()) + 6;
		}
	}

	/** One single-id row. */
	static final class IdRow extends Row {
		private final String labelKey;
		private final String unknownKey;
		private final java.util.function.Function<String, @Nullable String> check;
		private final java.util.function.Supplier<String> stored;

		IdRow(String labelKey, String value, int maxLength, String unknownKey,
				java.util.function.Function<String, @Nullable String> check,
				java.util.function.Supplier<String> stored, Consumer<String> setter) {
			super(value, maxLength, setter);
			this.labelKey = labelKey;
			this.unknownKey = unknownKey;
			this.check = check;
			this.stored = stored;
		}

		@Override
		String labelKey() {
			return this.labelKey;
		}

		@Override
		String storedValue() {
			return this.stored.get();
		}

		@Override
		@Nullable String normalized() {
			Identifier id = TradeMasterConfig.parseId(this.field.getValue());
			return id == null ? null : id.toString();
		}

		@Override
		@Nullable String hintFor(String raw) {
			if (raw == null || raw.isBlank()) return "trademaster.villagers.id_empty";
			Identifier id = TradeMasterConfig.parseId(raw);
			if (id == null) return this.unknownKey;
			return this.check.apply(id.toString());
		}
	}

	/** One row holding a comma/space separated id list (an empty text is a valid empty list). */
	static final class ListRow extends Row {
		private final String labelKey;

		ListRow(String labelKey, int maxLength, Consumer<String> setter) {
			super(String.join(", ", TradeMasterConfig.INSTANCE.nonTradeableProfessions), maxLength, setter);
			this.labelKey = labelKey;
		}

		@Override
		String labelKey() {
			return this.labelKey;
		}

		@Override
		String storedValue() {
			return String.join(", ", TradeMasterConfig.INSTANCE.nonTradeableProfessions);
		}

		@Override
		@Nullable String normalized() {
			List<String> ids = parseList(this.field.getValue());
			return ids == null ? null : String.join(", ", ids);
		}

		@Override
		@Nullable String hintFor(String raw) {
			List<String> ids = parseList(raw);
			if (ids == null) {
				// parseList refuses both an unparsable id and an over-long list: tell them apart
				return isOverlong(raw) ? "trademaster.villagers.list_too_many"
						: "trademaster.villagers.unknown_profession";
			}
			for (String id : ids) {
				String error = nonTradeableEntryError(id);
				if (error != null) return error;
			}
			return null;
		}
	}

	/** Splits on commas/whitespace and normalises every id, or null when one is unusable. */
	static @Nullable List<String> parseList(@Nullable String text) {
		List<String> ids = new ArrayList<>();
		if (text == null) return ids;
		for (String part : text.trim().split("[,\\s]+")) {
			if (part.isEmpty()) continue;
			Identifier id = TradeMasterConfig.parseId(part);
			if (id == null) return null;
			if (!ids.contains(id.toString())) ids.add(id.toString());
			// the same bound the reader enforces: without it the GUI could write a list the
			// next load silently truncates
			if (ids.size() > TradeMasterConfig.MAX_NON_TRADEABLE) return null;
		}
		return ids;
	}

	/** Whether the text holds more entries than the config list may hold. */
	static boolean isOverlong(String text) {
		if (text == null) return false;
		int count = 0;
		for (String part : text.trim().split("[,\\s]+")) {
			if (!part.isEmpty()) count++;
		}
		return count > TradeMasterConfig.MAX_NON_TRADEABLE;
	}

	/** The red hint key for one entry of the non-tradeable list, or null when it is fine.
	 * Package-private so a test can exercise the decision without building a widget. */
	static @Nullable String nonTradeableEntryError(String id) {
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null || BuiltInRegistries.VILLAGER_PROFESSION.getOptional(parsed).isEmpty()) {
			return "trademaster.villagers.unknown_profession";
		}
		// unemployment is the state that lets a villager TAKE the job: listing it would leave
		// the rig with no candidate it can employ
		if (parsed.equals(Identifier.withDefaultNamespace("none"))) {
			return "trademaster.villagers.list_none";
		}
		// the selected target may not be listed as a profession that cannot trade. BOTH ids are
		// checked: the RAW configured one (what the user picked) and the EFFECTIVE key (which
		// professionKey() can degrade to when the raw id itself is non-tradeable) — either one
		// appearing here would break the rig.
		Identifier raw = TradeMasterConfig.INSTANCE.professionIdentifier();
		if (raw != null && parsed.equals(raw)) return "trademaster.villagers.list_target";
		if (parsed.equals(RigTarget.professionKey().identifier())) {
			return "trademaster.villagers.list_target";
		}
		return null;
	}

	/** The red hint key for a work block id, or null when it is usable. Package-private so a
	 * test can exercise the decision without driving the whole widget. */
	static @Nullable String workBlockError(String id) {
		Identifier parsed = Identifier.tryParse(id);
		// BLOCK is a DefaultedRegistry: getValue() returns AIR for an unknown id and would
		// never report one. getOptional does not default; air is unusable either way.
		if (parsed == null
				|| BuiltInRegistries.BLOCK.getOptional(parsed).filter(b -> b != net.minecraft.world.level.block.Blocks.AIR).isEmpty()) {
			return "trademaster.villagers.unknown_block";
		}
		return null;
	}

	/** The red hint key for a profession id, or null when it is usable. A profession that can
	 * never trade is rejected too: the target could never be satisfied. */
	static @Nullable String professionError(String id) {
		Identifier parsed = Identifier.tryParse(id);
		// see workBlockError: VILLAGER_PROFESSION defaults to none, so getValue() would
		// report every typo as known
		if (parsed == null || BuiltInRegistries.VILLAGER_PROFESSION.getOptional(parsed).isEmpty()) {
			return "trademaster.villagers.unknown_profession";
		}
		// unemployed sells no trade: as a TARGET it could never be satisfied (the villager
		// has to take a job, and then it is no longer unemployed)
		if (parsed.equals(Identifier.withDefaultNamespace("none"))) {
			return "trademaster.villagers.profession_unemployed";
		}
		if (RigTarget.isNonTradeable(ResourceKey.create(Registries.VILLAGER_PROFESSION, parsed))) {
			return "trademaster.villagers.not_tradeable";
		}
		return null;
	}

	/** Truncates {@code text} to {@code maxWidth} pixels with an ellipsis. */
	private static String fit(Font font, String text, int maxWidth) {
		if (maxWidth <= 0) return "";
		if (font.width(text) <= maxWidth) return text;
		String result = text;
		while (!result.isEmpty() && font.width(result + "…") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}
		return result + "…";
	}
}
