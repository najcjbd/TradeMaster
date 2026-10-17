package dev.trademaster.gui;

//? if >=26.1 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;*/
//?}
import dev.trademaster.compat.McList;
import dev.trademaster.compat.McInput;
import dev.trademaster.compat.McRender;
import dev.trademaster.compat.McIds;
import dev.trademaster.compat.McRegistry;
import dev.trademaster.compat.McGraphics;
import dev.trademaster.compat.McBuiltIn;
import dev.trademaster.compat.McWidget;
import dev.trademaster.compat.McText;

import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.strategy.RigTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

	private int listWidth() {
		//? if >=1.20.2 {
		return this.getWidth();
		//?} else {
		/*return this.width;*/
		//?}
	}

	private int listHeight() {
		//? if >=1.20.2 {
		return this.getHeight();
		//?} else {
		/*return this.height;*/
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

	private static final int FIELD_WIDTH = 150;
	private static final int FIELD_GAP = 6;
	private static final int ID_MAX_LENGTH = 128;
	private static final int LIST_MAX_LENGTH = 1024;

	/** Set while the list dispatches a click: vanilla focuses the clicked entry AFTER the
	 * entry handled the click, and that focus move can scroll the viewport and thus run the
	 * scroll-blur — which would undo the focus the click just granted. Mirrors the other tabs. */
	private boolean suppressFocusClear = false;

	public VillagersListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		//? if >=1.20.2 {
		super(client, width, height, top, itemHeight);
		//?} else {
		/*super(client, width, height, top, top + height, itemHeight);*/
		//?}
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
		// the tab's one constraint, shown once under the rows: only the profession id is
		// configurable, the trade shape is not
		Font font = Minecraft.getInstance().font;
		int rows = this.children().size();
		if (rows == 0) return;
		// just under the last row: the vanilla background pass runs BEFORE the rows, so a
		// hint at the widget's bottom could be painted over by a row on a short screen
		int y = this.getRowTop(rows - 1) + this.entryRowHeight() + 2;
		if (y + 9 > this.listTop() + this.listHeight()) return;
		String hint = McText.translatable("trademaster.villagers.hint").getString();
		context.drawString(font, McText.literal(fit(font, hint, this.listWidth() - 6)),
				this.listLeft() + 3, y, 0xFF9A9A9A);
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
		return super.isMouseOver(x, y) || this.overScrollbar(x, y);
	}

	@Override
	public int getRowTop(int index) {
		return this.listTop() + 2 - (int) McList.scrollAmount(this) + index * this.entryRowHeight();
	}

	@Override
	public int getRowLeft() {
		return this.listLeft() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	/** Commits every row's in-flight draft (screen close / tab switch / save / start). */
	public void flushEdits() {
		for (Row row : this.children()) row.flushEdits();
	}

	//? if >=26.1 {
	/*@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		beginMouseClick(click.x(), click.y());
		try {
			return super.mouseClicked(click, doubled);
		} finally {
			this.suppressFocusClear = false;
		}
	}*/
	//?} else {
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		beginMouseClick(mouseX, mouseY);
		try {
			return super.mouseClicked(mouseX, mouseY, button);
		} finally {
			this.suppressFocusClear = false;
		}
	}
	//?}

	private void beginMouseClick(double mouseX, double mouseY) {
		// a field the pointer is NOT on commits its draft and loses focus; the field under
		// the pointer is left alone (the pointer's own click focuses it)
		for (Row row : this.children()) {
			if (!row.field.isMouseOver(mouseX, mouseY)) {
				row.reconcileOnFocusLoss();
				row.field.setFocused(false);
			}
		}
		// vanilla moves focus to the clicked entry AFTER this, which can scroll and blur
		// the field the click just focused: suppress the blur for the duration
		this.suppressFocusClear = true;
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = McList.scrollAmount(this);
		super.setScrollAmount(amount);
		if (McList.scrollAmount(this) == before || this.suppressFocusClear) return;
		// scroll only drops focus: an involuntary blur must not COMMIT a half-typed id
		for (Row row : this.children()) row.clearFieldFocus();
	}

	//? if >=26.1 {
	/*@Override
	public boolean keyPressed(KeyEvent input) {
		return handleKeyPressed(input.key(), McInput.scancode(input), input.modifiers()) || super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		return handleKeyReleased(input.key(), McInput.scancode(input), input.modifiers()) || super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (!input.isAllowedChatCharacter()) return false;
		return handleCharTyped((char) input.codepoint()) || super.charTyped(input);
	}

	@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		for (Row row : this.children()) {
			if (row.field.isFocused()) return row.field.preeditUpdated(input);
		}
		return false;
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
		for (Row row : this.children()) {
			if (row.field.isFocused()) return McInput.keyPressed(row.field, keyCode, scanCode, modifiers);
		}
		return false;
	}

	private boolean handleKeyReleased(int keyCode, int scanCode, int modifiers) {
		for (Row row : this.children()) {
			if (row.field.isFocused()) McInput.keyReleased(row.field, keyCode, scanCode, modifiers);
		}
		return false;
	}

	private boolean handleCharTyped(char c) {
		for (Row row : this.children()) {
			if (row.field.isFocused() && McInput.charTyped(row.field, c, 0)) return true;
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
					McText.literal("villager id"));
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
		//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return super.mouseClicked(click, doubled);
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

		@Override
		public boolean mouseReleased(MouseButtonEvent click) {
			return this.field.mouseReleased(click);
		}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			return super.mouseClicked(mouseX, mouseY, button);
		}

		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			return this.field.isFocused() && McInput.mouseDragged(this.field, mouseX, mouseY, button, dragX, dragY);
		}

		@Override
		public boolean mouseReleased(double mouseX, double mouseY, int button) {
			return McInput.mouseReleased(this.field, mouseX, mouseY, button);
		}
		//?}

		// 26.1 replaced Entry.render(GuiGraphics,int,int,int,int,int,int,int,boolean,float) with
		// Entry.extractContent(...): the row geometry is passed as render() arguments on
		// <=1.21.11 but read from the entry on 26.1+. Both stash it and delegate to drawRow.
		//? if >=26.1 {
		/*@Override
		public void extractContent(GuiGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			this.x = this.getX();
			this.y = this.getY();
			this.entryWidth = this.getWidth();
			this.entryHeight = this.getHeight();
			drawRow(new McGraphics(context), mouseX, mouseY, hovered, deltaTicks);
		}*/
		//?} elif >=1.20 {
		@Override
		public void render(GuiGraphics context, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			this.x = left;
			this.y = top;
			this.entryWidth = width;
			this.entryHeight = height;
			drawRow(new McGraphics(context), mouseX, mouseY, hovered, deltaTicks);
		}
		//?} else {
		/*@Override
		public void render(com.mojang.blaze3d.vertex.PoseStack context, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			this.x = left;
			this.y = top;
			this.entryWidth = width;
			this.entryHeight = height;
			drawRow(new McGraphics(context), mouseX, mouseY, hovered, deltaTicks);
				}*/
		//?}

		private void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			Font font = Minecraft.getInstance().font;
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, 0x1AC7C0C0);
			context.drawString(font, McText.translatable(this.labelKey()), this.x + 4, this.y + 6, 0xFFE0E000);
			// clamp the field to what the row can actually hold: a fixed 150px wide field
			// would overflow a narrow (Android/FCL) screen and collide with the label
			int labelWidth = font.width(McText.translatable(this.labelKey()));
			int available = this.entryWidth - 4 - labelWidth - FIELD_GAP - 10;
			int fieldWidth = Math.max(40, Math.min(FIELD_WIDTH, available));
			this.field.setWidth(fieldWidth);
			int fieldX = this.x + this.entryWidth - fieldWidth - 10;
			this.field.setX(Math.max(this.x + 4, fieldX));
			McWidget.setY(this.field, this.y + 2);
			McRender.pushPose(context);
			context.renderWidget(this.field, mouseX, mouseY, deltaTicks);
			McRender.popPose(context);
			String error = hintFor(this.field.getValue());
			if (error != null) {
				int errorX = McWidget.getX(this.field) - 4;
				String text = McText.translatable(error).getString();
				context.drawString(font, McText.literal(fit(font, text, errorX - this.boxLeft())),
						this.boxLeft(), this.y + 6, 0xFFFF5555);
			}
		}

		private int boxLeft() {
			return this.x + 4 + Minecraft.getInstance().font.width(McText.translatable(this.labelKey()).getString()) + 6;
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
			ResourceLocation id = TradeMasterConfig.parseId(this.field.getValue());
			return id == null ? null : id.toString();
		}

		@Override
		@Nullable String hintFor(String raw) {
			if (raw == null || raw.isBlank()) return "trademaster.villagers.id_empty";
			ResourceLocation id = TradeMasterConfig.parseId(raw);
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
			ResourceLocation id = TradeMasterConfig.parseId(part);
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
		ResourceLocation parsed = McIds.tryParse(id);
		if (parsed == null || McBuiltIn.villagerProfessions().getOptional(parsed).isEmpty()) {
			return "trademaster.villagers.unknown_profession";
		}
		// unemployment is the state that lets a villager TAKE the job: listing it would leave
		// the rig with no candidate it can employ
		if (parsed.equals(McIds.mc("none"))) {
			return "trademaster.villagers.list_none";
		}
		// the selected target may not be listed as a profession that cannot trade. BOTH ids are
		// checked: the RAW configured one (what the user picked) and the EFFECTIVE key (which
		// professionKey() can degrade to when the raw id itself is non-tradeable) — either one
		// appearing here would break the rig.
		ResourceLocation raw = TradeMasterConfig.INSTANCE.professionIdentifier();
		if (raw != null && parsed.equals(raw)) return "trademaster.villagers.list_target";
		if (parsed.equals(McRegistry.keyId(RigTarget.professionKey()))) {
			return "trademaster.villagers.list_target";
		}
		return null;
	}

	/** The red hint key for a work block id, or null when it is usable. Package-private so a
	 * test can exercise the decision without driving the whole widget. */
	static @Nullable String workBlockError(String id) {
		ResourceLocation parsed = McIds.tryParse(id);
		// BLOCK is a DefaultedRegistry: getValue() returns AIR for an unknown id and would
		// never report one. getOptional does not default; air is unusable either way.
		if (parsed == null
				|| McBuiltIn.blocks().getOptional(parsed).filter(b -> b != net.minecraft.world.level.block.Blocks.AIR).isEmpty()) {
			return "trademaster.villagers.unknown_block";
		}
		return null;
	}

	/** The red hint key for a profession id, or null when it is usable. A profession that can
	 * never trade is rejected too: the target could never be satisfied. */
	static @Nullable String professionError(String id) {
		ResourceLocation parsed = McIds.tryParse(id);
		// see workBlockError: VILLAGER_PROFESSION defaults to none, so getValue() would
		// report every typo as known
		if (parsed == null || McBuiltIn.villagerProfessions().getOptional(parsed).isEmpty()) {
			return "trademaster.villagers.unknown_profession";
		}
		// unemployed sells no trade: as a TARGET it could never be satisfied (the villager
		// has to take a job, and then it is no longer unemployed)
		if (parsed.equals(McIds.mc("none"))) {
			return "trademaster.villagers.profession_unemployed";
		}
		if (RigTarget.isNonTradeable(ResourceKey.create(McBuiltIn.villagerProfessionKey(), parsed))) {
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
