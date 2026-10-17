package dev.trademaster.gui;

//? if >=26.1 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;*/
//?}
import dev.trademaster.compat.McList;
import dev.trademaster.compat.McPlayer;
import dev.trademaster.compat.McRender;
import dev.trademaster.compat.McClient;
import dev.trademaster.compat.McInput;
import dev.trademaster.compat.McIds;
import dev.trademaster.compat.McSound;
import dev.trademaster.compat.McGraphics;
import dev.trademaster.compat.McWidget;
import dev.trademaster.compat.McText;

import dev.trademaster.config.DurationParser;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RerollController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class SettingsListWidget extends AbstractSelectionList<SettingsListWidget.Row> {

	/** A red chat notice for a refused setting change. */
	private static void chatRed(String key) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			McPlayer.sendSystemMessage(mc.player, McText.literal("[Trade Master] ")
					.append(McText.translatable(key).withStyle(net.minecraft.ChatFormatting.RED)));
		}
	}

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

	public SettingsListWidget(Minecraft client, int width, int height, int top, int itemHeight, Runnable rebuild) {
		//? if >=1.20.2 {
		super(client, width, height, top, itemHeight);
		//?} else {
		/*super(client, width, height, top, top + height, itemHeight);*/
		//?}
		// the label/value caches below hold TRANSLATED components: drop them whenever
		// the screen is (re)built, or a client language change would keep serving the
		// old strings (the list is recreated on every open, resize and tab switch)
		Row.invalidateTextCaches();
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;

		addEntry(new CycleRow("trademaster.settings.mechanism",
				() -> c.rerollMechanism.displayName(),
				cycle -> c.rerollMechanism = dev.trademaster.config.RerollMechanism.valueOf(nextEnum(c.rerollMechanism.name(), dev.trademaster.config.RerollMechanism.values(), cycle))));
		addEntry(new CycleRow("trademaster.settings.nonBookDisposal",
				() -> c.nonBookDisposal.displayName(),
				cycle -> c.nonBookDisposal = dev.trademaster.config.NonBookDisposal.valueOf(nextEnum(c.nonBookDisposal.name(), dev.trademaster.config.NonBookDisposal.values(), cycle))));
		addEntry(new CycleRow("trademaster.settings.detection",
				() -> c.detectionMode.displayName(),
				cycle -> c.detectionMode = dev.trademaster.config.DetectionMode.valueOf(nextEnum(c.detectionMode.name(), dev.trademaster.config.DetectionMode.values(), cycle))));

		addEntry(new BoolRow("trademaster.settings.reserveCheaperTraders", () -> c.reserveCheaperTraders, v -> c.reserveCheaperTraders = v));
		addEntry(new IntRow("trademaster.settings.minLockEmeralds", () -> c.minLockEmeralds, v -> c.minLockEmeralds = v, 1, 64));
		addEntry(new BoolRow("trademaster.settings.basePriceAsSalePrice", () -> c.basePriceAsSalePrice, v -> {
			// hunt marks store prices in the basis that was active at purchase time;
			// switching mid-run would reinterpret them (duplicate buys / stalled hunt)
			if (RerollController.isRunning()) {
				chatRed("trademaster.message.price_basis_locked");
				return;
			}
			c.basePriceAsSalePrice = v;
		}));
		addEntry(new BoolRow("trademaster.settings.multiEnchantProtection", () -> c.multiEnchantProtection, v -> c.multiEnchantProtection = v));
		addEntry(new BoolRow("trademaster.settings.timeoutEnabled", () -> c.timeoutEnabled, v -> c.timeoutEnabled = v));
		addEntry(new DurationRow("trademaster.settings.timeoutTicks", () -> c.timeoutTicks, v -> c.timeoutTicks = v, 1200));
		addEntry(new BoolRow("trademaster.settings.phaseTimeoutsEnabled", () -> c.phaseTimeoutsEnabled, v -> c.phaseTimeoutsEnabled = v));
		addEntry(new DurationRow("trademaster.settings.villagerCheckInterval", () -> c.villagerCheckInterval, v -> c.villagerCheckInterval = v, 1, 72000));
		addEntry(new DurationRow("trademaster.settings.lecternHealGrace", () -> c.lecternHealGrace, v -> c.lecternHealGrace = v, 20, 1200));
		addEntry(new BoolRow("trademaster.settings.professionHealCapEnabled", () -> c.professionHealCapEnabled, v -> c.professionHealCapEnabled = v));
		addEntry(new IntRow("trademaster.settings.professionHealCap", () -> c.professionHealCap, v -> c.professionHealCap = v, 1, 20));
		addEntry(new BoolRow("trademaster.settings.autoDisableAfterLock", () -> c.autoDisableAfterLock, v -> {
			c.autoDisableAfterLock = v;
			if (v) c.huntCheaper = false;
		}));
		//? if >=26.1 {
		/*addEntry(new HuntRow(), 44);*/
		//?} else {
		addEntry(new HuntRow());
		// the 44px hunt row cannot be used before 26.1: those lists locate a click
		// arithmetically from itemHeight, so a taller row shifts every later click
		// one entry down. Uniform 22px caption rows keep the layout clickable.
		addHuntCaptionRows();
		//?}
		addEntry(new IntRow("trademaster.settings.lockMaxRetries", () -> c.lockMaxRetries, v -> c.lockMaxRetries = v, -1, 999));
		addEntry(new IntRow("trademaster.settings.offhandSwapRetries", () -> c.offhandSwapRetries, v -> c.offhandSwapRetries = v, -1, 999));
		addEntry(new IntRow("trademaster.settings.postDispatchBreakRetries", () -> c.postDispatchBreakRetries, v -> c.postDispatchBreakRetries = v, -1, 999));
		addEntry(new BoolRow("trademaster.settings.grindstoneEnabled", () -> c.grindstoneEnabled, v -> c.grindstoneEnabled = v));
		addEntry(new IntRow("trademaster.settings.grindstoneOpenRetries", () -> c.grindstoneOpenRetries, v -> c.grindstoneOpenRetries = v, -1, 999));
		addEntry(new BoolRow("trademaster.settings.displayRolls", () -> c.displayRolls, v -> c.displayRolls = v));
		addEntry(new BoolRow("trademaster.settings.namingEnabled", () -> c.namingEnabled, v -> c.namingEnabled = v));
		addEntry(new BoolRow("trademaster.settings.forceNamePriceOne", () -> c.forceNamePriceOne, v -> c.forceNamePriceOne = v));
		addEntry(new BoolRow("trademaster.settings.debugLog", () -> c.debugLog, v -> c.debugLog = v));

		addEntry(new BoolRow("trademaster.settings.legitMode", () -> c.legitMode, v -> {
			c.legitMode = v;
			if (!v) c.slowMode = false;
		}));
		addEntry(new BoolRow("trademaster.settings.slowMode", () -> c.slowMode, v -> {
			c.slowMode = v;
			if (v) c.legitMode = true;
		}));
		addEntry(new BoolRow("trademaster.settings.tpToVillager", () -> c.tpToVillager, v -> c.tpToVillager = v));
		addEntry(new BoolRow("trademaster.settings.tpToItem", () -> c.tpToItem, v -> c.tpToItem = v));
		addEntry(new BoolRow("trademaster.settings.preventAxeBreaking", () -> c.preventAxeBreaking, v -> c.preventAxeBreaking = v));
		addEntry(new BoolRow("trademaster.settings.stopOnPlayerMove", () -> c.stopOnPlayerMove, v -> c.stopOnPlayerMove = v));
		addEntry(new IntRow("trademaster.settings.placeDelay", () -> c.placeDelay, v -> c.placeDelay = v, 0, 20));
		addEntry(new IntRow("trademaster.settings.interactDelay", () -> c.interactDelay, v -> c.interactDelay = v, 0, 20));

		addEntry(new PosRow("trademaster.settings.lecternPos", () -> c.lecternPos,
				pos -> c.lecternPos = pos, PositionPicker.Slot.LECTERN));
		addEntry(new PosRow("trademaster.settings.summonButtonPos", () -> c.summonButtonPos,
				pos -> c.summonButtonPos = pos, PositionPicker.Slot.SUMMON_BUTTON));
		// the dispenser is OPTIONAL (unset = the minecart check is skipped), so its row must
		// be clearable like the grindstone's, not treated as a required picker slot
		addEntry(new PosRow("trademaster.settings.dispenserPos", () -> c.dispenserPos,
				pos -> c.dispenserPos = pos, PositionPicker.Slot.DISPENSER, true));
		addEntry(new PosRow("trademaster.settings.sendAwayButtonPos", () -> c.sendAwayButtonPos,
				pos -> c.sendAwayButtonPos = pos, PositionPicker.Slot.SEND_AWAY_BUTTON));
		addEntry(new PosRow("trademaster.settings.grindstonePos", () -> c.grindstonePos,
				pos -> c.grindstonePos = pos, PositionPicker.Slot.GRINDSTONE, true));
		addEntry(new DirectionRow());
		addEntry(new CurseListRow());
		addEntry(new PosRow("trademaster.settings.villagerRegionOrigin", () -> c.villagerRegionOrigin,
				pos -> c.villagerRegionOrigin = pos, null));
		addEntry(new DeltaRow("trademaster.settings.villagerRegionDelta", () -> c.villagerRegionDelta,
				pos -> c.villagerRegionDelta = pos));
		// staging-sphere fallback radius: a click row (deliberate per click, so no
		// transient to defer — not a text field)
		addEntry(new IntRow("trademaster.settings.villagerSphereRadius", () -> c.villagerSphereRadius,
				v -> c.villagerSphereRadius = v, 1, 64));

		for (int i = 0; i < c.chestPositions.size(); i++) {
			addEntry(new ChestRow(i, rebuild));
		}
		addEntry(new ActionRow("trademaster.settings.addChest", () -> {
			boolean began = PositionPicker.begin(PositionPicker.Slot.CHEST,
					pos -> c.addChest(pos.immutable()));
			if (began) McClient.setScreen(Minecraft.getInstance(), null);
			return began;
		}));
	}

	private static <E extends Enum<E>> String nextEnum(String current, E[] values, boolean forward) {
		int index = 0;
		for (int i = 0; i < values.length; i++) {
			if (values[i].name().equals(current)) {
				index = i;
				break;
			}
		}
		return values[(index + (forward ? 1 : values.length - 1)) % values.length].name();
	}

	/** Parses "x y z"; each component may be "~" or "~n" relative to the player when given. */
	private static @Nullable BlockPos parsePos(String text, @Nullable net.minecraft.client.player.LocalPlayer player) {
		String[] parts = text.trim().split("[,\\s]+");
		if (parts.length != 3) return null;
		BlockPos base = player == null ? null : player.blockPosition();
		int[] values = new int[3];
		for (int i = 0; i < 3; i++) {
			String part = parts[i];
			boolean relative = part.startsWith("~");
			String number = relative ? part.substring(1) : part;
			int value;
			if (number.isEmpty()) {
				if (!relative) return null;
				value = 0;
			} else {
				try {
					value = Integer.parseInt(number);
				} catch (NumberFormatException e) {
					return null;
				}
			}
			if (relative) {
				if (base == null) return null;
				value += switch (i) {
					case 0 -> base.getX();
					case 1 -> base.getY();
					default -> base.getZ();
				};
			}
			values[i] = value;
		}
		// out-of-world coordinates would make presses/detection target a place the
		// server rejects or that is beyond the world border — refuse them at parse
		if (Math.abs(values[0]) > 30_000_000 || Math.abs(values[2]) > 30_000_000) return null;
		if (player != null && (values[1] < dev.trademaster.compat.McEntity.level(player).dimensionType().minY()
				|| values[1] >= dev.trademaster.compat.McEntity.level(player).dimensionType().minY() + dev.trademaster.compat.McEntity.level(player).dimensionType().height())) return null;
		return new BlockPos(values[0], values[1], values[2]);
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
				McText.translatable("trademaster.tab.settings"), 9, 32, 0xFF202020);
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
	 * earlier lists' own getRowTop adds a 4px padding (header height is 0 here). A
	 * wrong value leaves a 2px dead strip at the top of every row on old targets. */
	private int firstEntryOffset() {
		//? if >=26.1 {
		/*return 2;*/
		//?} else {
		return 4;
		//?}
	}

	@Override
	public int getRowTop(int index) {
		// heights are summed per row because the hunt row is taller than the default
		// 22px (on 26.1; before that the caption lives in its own uniform rows)
		int top = this.listTop() + this.firstEntryOffset() - (int) McList.scrollAmount(this);
		java.util.List<Row> rows = this.children();
		for (int i = 0; i < index && i < rows.size(); i++) {
			top += rows.get(i).rowHeight();
		}
		return top;
	}

	// 1.19.2's AbstractSelectionList.getRowBottom is private; override it only where it is
	// accessible (1.20+).
	//? if >=1.20 {
	@Override
	public int getRowBottom(int index) {
		return this.getRowTop(index) + this.children().get(index).rowHeight();
	}
	//?}

	@Override
	public int getRowLeft() {
		return this.listLeft() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	//? if >=26.1 {
	/*@Override
	public boolean keyPressed(KeyEvent input) {
		for (Row row : this.children()) {
			if (row.handleKey(input.key(), McInput.scancode(input), input.modifiers())) return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		for (Row row : this.children()) {
			row.handleRelease(input.key(), McInput.scancode(input), input.modifiers());
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (!input.isAllowedChatCharacter()) return false;
		for (Row row : this.children()) {
			if (row.handleChar((char) input.codepoint())) return true;
		}
		return super.charTyped(input);
	}*/
	//?} else {
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		for (Row row : this.children()) {
			if (row.handleKey(keyCode, scanCode, modifiers)) return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		for (Row row : this.children()) {
			row.handleRelease(keyCode, scanCode, modifiers);
		}
		return super.keyReleased(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		if (!McInput.isAllowedChatCharacter(c)) return false;
		for (Row row : this.children()) {
			if (row.handleChar(c)) return true;
		}
		return super.charTyped(c, modifiers);
	}
	//?}

	/** Clicking a partially clipped row makes the list auto-scroll it fully into
	 * view (setFocused -> scrollToEntry): that alignment scroll must not trigger
	 * the focus-clear below, or the click's freshly granted focus dies instantly.
	 * Only user-initiated scrolling clears focus. */
	private boolean suppressFocusClear = false;

	/** IME preedit (Android/IM soft keyboards): the screen delivers it to the focused
	 * widget, which is this list, so hand it to the row whose field is focused —
	 * EditBox.preeditUpdated has no focus guard and must not run on other rows. 26.1-only. */
	//? if >=26.1 {
	/*@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		for (Row row : this.children()) {
			if (row.handlePreedit(input)) return true;
		}
		return false;
	}*/
	//?}

	//? if >=26.1 {
	/*@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		// right-click is not an edit gesture: do not reconcile/blur/commit drafts
		if (click.button() != 0) return super.mouseClicked(click, doubled);
		reconcileRowsNotUnderPointer(click.x(), click.y());
		boolean handled;
		this.suppressFocusClear = true;
		try {
			handled = super.mouseClicked(click, doubled);
		} finally {
			this.suppressFocusClear = false;
		}
		return handled;
	}*/
	//?} else {
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// right-click is not an edit gesture: do not reconcile/blur/commit drafts
		if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
		// Commit the rows the pointer is NOT on BEFORE super runs: a click on [Remove]
		// deletes a chest inside super.mouseClicked, which shifts the rows after it —
		// their originalPos guard would then silently discard an in-flight draft.
		// A row whose own field IS under the pointer keeps editing: committing there
		// would push a half-typed value ("2" on the way to "20") into the live config.
		reconcileRowsNotUnderPointer(mouseX, mouseY);
		boolean handled;
		this.suppressFocusClear = true;
		try {
			handled = super.mouseClicked(mouseX, mouseY, button);
		} finally {
			this.suppressFocusClear = false;
		}
		return handled;
	}
	//?}

	/** Blur semantics for a click: every field the pointer is not on commits its draft
	 * and loses focus; the field under the pointer is left alone. */
	private void reconcileRowsNotUnderPointer(double mouseX, double mouseY) {
		for (Row row : this.children()) {
			if (row instanceof DurationRow duration && !duration.field.isMouseOver(mouseX, mouseY)) {
				duration.reconcileOnFocusLoss();
				duration.field.setFocused(false);
			}
			if (row instanceof PosRow pos && !pos.field.isMouseOver(mouseX, mouseY)) {
				pos.reconcileOnFocusLoss();
				pos.field.setFocused(false);
			}
			if (row instanceof DeltaRow delta && !delta.field.isMouseOver(mouseX, mouseY)) {
				delta.reconcileOnFocusLoss();
				delta.field.setFocused(false);
			}
			if (row instanceof DirectionRow direction && !direction.field.isMouseOver(mouseX, mouseY)) {
				direction.reconcileOnFocusLoss();
				direction.field.setFocused(false);
			}
			if (row instanceof CurseListRow curses && !curses.field.isMouseOver(mouseX, mouseY)) {
				curses.reconcileOnFocusLoss();
				curses.field.setFocused(false);
			}
			if (row instanceof ChestRow chest && !chest.field.isMouseOver(mouseX, mouseY)) {
				chest.reconcileOnFocusLoss();
				chest.field.setFocused(false);
			}
		}
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = McList.scrollAmount(this);
		super.setScrollAmount(amount);
		if (McList.scrollAmount(this) == before || this.suppressFocusClear) return;
		// a focused field scrolled off-screen keeps swallowing keyboard input
		// invisibly: drop focus whenever the viewport actually moves (wheel noise at
		// the scroll boundaries must not kill focus mid-typing). clearFieldFocus() blurs
		// WITHOUT committing, so an involuntary scroll cannot write a half-typed draft —
		// it stays pending and commits at a deliberate focus change, a save, or close.
		for (Row row : this.children()) row.clearFieldFocus();
	}

	/** Commits in-flight text drafts for every row: closing the screen tears the
	 * widgets down without a blur, so a draft typed and never unfocused would
	 * otherwise be silently discarded. */
	public void flushEdits() {
		for (Row row : this.children()) row.flushEdits();
	}

	public abstract static class Row extends AbstractSelectionList.Entry<Row> {
		protected int x, y, entryWidth, entryHeight;

		/** Row height: 26.1 tracks it in the Entry (addEntry(E,int)); before that the list
		 * has no per-entry height, so the row carries its own (default 22, hunt row 44). */
		//? if >=26.1 {
		/*int rowHeight() { return this.getHeight(); }*/
		//?} else {
		int ownHeight = 22;
		int rowHeight() { return this.ownHeight; }
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

		protected abstract void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks);

		public boolean handleKey(int keyCode, int scanCode, int modifiers) { return false; }
		public void handleRelease(int keyCode, int scanCode, int modifiers) {}
		public boolean handleChar(char c) { return false; }

		/** IME preedit forward (Android/IM keyboards): see the list-level override. 26.1-only. */
		//? if >=26.1 {
		/*public boolean handlePreedit(net.minecraft.client.input.PreeditEvent input) { return false; }*/
		//?}
		/** Drops the row's EditBox focus (viewport scrolled away); no-op for rows without a field. */
		void clearFieldFocus() {}

		/** Applies an in-flight text draft when the screen is closed without the
		 * field ever losing focus (ESC tears the widgets down, it does not blur). */
		void flushEdits() {}

		protected static void playClick() {
			McSound.playButtonClick();
		}

		/** Reserved right area on rows hosting a 100px EditBox: field + margin + gap, so drawn text stays visible. */
		protected static final int FIELD_RIGHT_INSET = 114;
		/** Same for DurationRow's 80px EditBox right-anchored at the row edge. */
		protected static final int DURATION_RIGHT_INSET = 94;

		/**
		 * Fits value+suffix between the label and the reserved field area: on
		 * narrow screens the value is truncated with an ellipsis (suffix kept
		 * intact) so it can never overlap the label or slide under the EditBox.
		 */
		protected String fitValueText(Component label, String value, String suffix, int rightInset) {
			Font font = Minecraft.getInstance().font;
			String full = value + suffix;
			// entryWidth is assigned by the gated Entry override before every draw pass
			int width = this.entryWidth;
			if (width <= 0) return full;
			int available = width - rightInset - font.width(label) - 18;
			if (font.width(full) <= available) return full;
			int room = available - font.width(suffix) - font.width("...");
			return font.substrByWidth(McText.literal(value), Math.max(0, room)).getString() + "..." + suffix;
		}

		/** Touch-friendly hit zone: +/-4px tolerance and at least 44px wide (touch target guideline). */
		protected static boolean touchZone(double clickX, int left, int right) {
			int zoneLeft = Math.min(left - 4, right + 4 - 44);
			return clickX >= zoneLeft && clickX <= right + 4;
		}

		/** Precise hit zone for destructive actions: +/-4px tolerance only, no
		 * 44px expansion — the widened zone would otherwise reach into the text
		 * left of the target (e.g. the chest coordinates next to [Remove]). */
		protected static boolean labelZone(double clickX, int left, int right) {
			return clickX >= left - 4 && clickX <= right + 4;
		}

		/** Label components are per-frame constants: resolving the key on every frame
		 * built a Component (and a language lookup) for every visible row every frame. */
		private static final java.util.Map<String, Component> LABELS = new java.util.HashMap<>();

		/** The two boolean state texts are constants once looked up. */
		private static String enabledText = null;
		private static String disabledText = null;

		protected static String valueText(boolean value) {
			if (value) {
				if (enabledText == null) enabledText = McText.translatable("trademaster.button.enabled").getString();
				return enabledText;
			}
			if (disabledText == null) disabledText = McText.translatable("trademaster.button.disabled").getString();
			return disabledText;
		}

		/** Drops the translated-text caches (see the list constructor). */
		static void invalidateTextCaches() {
			LABELS.clear();
			enabledText = null;
			disabledText = null;
		}

		protected static Component label(String key) {
			Component cached = LABELS.get(key);
			if (cached != null) return cached;
			Component made = McText.translatable(key);
			LABELS.put(key, made);
			return made;
		}

		protected void drawBase(McGraphics context, String labelKey, String valueText, int valueColor) {
			drawBase(context, label(labelKey), valueText, valueColor, 10);
		}

		protected void drawBase(McGraphics context, String labelKey, String valueText, int valueColor, int rightInset) {
			drawBase(context, label(labelKey), valueText, valueColor, rightInset);
		}

		protected void drawBase(McGraphics context, Component label, String valueText, int valueColor) {
			drawBase(context, label, valueText, valueColor, 10);
		}

		/** rightInset reserves space on the right so the value does not sit under a hosted EditBox. */
		/** Value-component cache: rebuilt only when the value behind it changes. */
		private String cachedValueText = null;
		private Component cachedValueComponent = null;

		protected Component valueComponent(String valueText) {
			if (!valueText.equals(this.cachedValueText)) {
				this.cachedValueText = valueText;
				this.cachedValueComponent = McText.literal(valueText);
			}
			return this.cachedValueComponent;
		}

		protected void drawBase(McGraphics context, Component label, String valueText, int valueColor, int rightInset) {
			Font font = Minecraft.getInstance().font;
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, 0x1AC7C0C0);
			context.drawString(font, label, this.x + 6, this.y + 6, 0xFFFFFFFF);
			int textWidth = font.width(valueText);
			context.drawString(font, valueComponent(valueText), this.x + this.entryWidth - rightInset - textWidth, this.y + 6, valueColor);
		}
	}

	private static class BoolRow extends Row {
		private final String labelKey;
		private final Supplier<Boolean> getter;
		private final Consumer<Boolean> setter;

		BoolRow(String labelKey, Supplier<Boolean> getter, Consumer<Boolean> setter) {
			this.labelKey = labelKey;
			this.getter = getter;
			this.setter = setter;
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			boolean value = this.getter.get();
			drawBase(context, this.labelKey,
					valueText(value),
					value ? 0xFF00C000 : 0xFFC00000);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				boolean before = this.getter.get();
				this.setter.accept(!before);
				// a refused toggle (hunt blocked by auto-disable or a running
				// pipeline) must not play the confirm sound
				if (this.getter.get() != before) playClick();
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (mouseX > this.x && mouseX < this.x + this.entryWidth && mouseY > this.y && mouseY < this.y + this.entryHeight - 4) {
				boolean before = this.getter.get();
				this.setter.accept(!before);
				// a refused toggle (hunt blocked by auto-disable or a running
				// pipeline) must not play the confirm sound
				if (this.getter.get() != before) playClick();
				return true;
			}
			return false;
				}
		//?}

	}

	/** Hunt-cheaper warning as uniform 22px rows. Used before 26.1 only: a 44px row
	 * would desync that version's arithmetic click hit-testing (see the constructor). */
	private void addHuntCaptionRows() {
		Font font = Minecraft.getInstance().font;
		int wrap = Math.max(40, this.getRowWidth() - 12);
		java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(
				McText.translatable("trademaster.settings.huntCheaper.caption"), wrap);
		for (int start = 0; start < lines.size(); start += 2) {
			addEntry(new HuntCaptionRow(new java.util.ArrayList<>(
					lines.subList(start, Math.min(start + 2, lines.size())))));
		}
	}

	private static class HuntCaptionRow extends Row {
		private final java.util.List<net.minecraft.util.FormattedCharSequence> lines;

		HuntCaptionRow(java.util.List<net.minecraft.util.FormattedCharSequence> lines) {
			this.lines = lines;
		}

		@Override
		protected void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			Font font = Minecraft.getInstance().font;
			for (int i = 0; i < this.lines.size(); i++) {
				context.drawString(font, this.lines.get(i), this.x + 6, this.y + 3 + i * 9, 0xFF909090);
			}
		}
	}

	/** Hunt-cheaper row: the BoolRow toggle plus a small always-visible warning
	 * caption below the label — the native GUI's stand-in for the maLiLib
	 * hover tooltip. Enabling is refused while a run is active (the needs table
	 * holds lock vouchers and was built without hunt rules); disabling mid-run
	 * is allowed and the precheck rebuilds the table. */
	private static class HuntRow extends BoolRow {
		HuntRow() {
			super("trademaster.settings.huntCheaper", () -> TradeMasterConfig.INSTANCE.huntCheaper, v -> {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				if (v && !c.huntCheaper) {
					// mutual exclusion, same rule as the maLiLib screen: when both
					// would end up on, autoDisableAfterLock wins — refuse the toggle
					// instead of silently flipping auto-disable off
					if (c.autoDisableAfterLock) {
						chatRed("trademaster.message.hunt_blocked_by_autodisable");
						return;
					}
					if (RerollController.isRunning()) {
						chatRed("trademaster.message.hunt_enable_blocked");
						return;
					}
				}
				c.huntCheaper = v;
			});
		}

		private static void chatRed(String key) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				McPlayer.sendSystemMessage(mc.player, McText.literal("[Trade Master] ")
						.append(McText.translatable(key).withStyle(net.minecraft.ChatFormatting.RED)));
			}
		}

		/** The wrapped caption is cached: font.split re-lays out the text, and this
		 * row is redrawn on every frame while the settings tab is open. */
		private int cachedCaptionWidth = -1;
		private java.util.List<net.minecraft.util.FormattedCharSequence> cachedCaptionLines = java.util.List.of();

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			super.drawRow(context, mouseX, mouseY, hovered, deltaTicks);
			//? if >=26.1 {
			/*// the caption lives inside this 44px row on 26.1+; older lists draw it
			// in separate uniform-height rows instead (see addHuntCaptionRows)
			Font font = Minecraft.getInstance().font;
			int wrap = Math.max(40, this.entryWidth - 12);
			if (wrap != this.cachedCaptionWidth) {
				this.cachedCaptionWidth = wrap;
				this.cachedCaptionLines = font.split(
						McText.translatable("trademaster.settings.huntCheaper.caption"), wrap);
			}
			java.util.List<net.minecraft.util.FormattedCharSequence> lines = this.cachedCaptionLines;
			for (int i = 0; i < lines.size() && i < 3; i++) {
				context.drawString(font, lines.get(i), this.x + 6, this.y + 14 + i * 9, 0xFF909090);
			}*/
			//?}
		}
	}

	private static class CycleRow extends Row {
		private final String labelKey;
		private final Supplier<String> getter;
		private final Consumer<Boolean> setter;

		CycleRow(String labelKey, Supplier<String> getter, Consumer<Boolean> setter) {
			this.labelKey = labelKey;
			this.getter = getter;
			this.setter = setter;
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, this.labelKey, "< " + this.getter.get() + " >", 0xFFE0E000);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				boolean forward = click.x() > this.x + this.entryWidth / 2.0;
				this.setter.accept(forward);
				playClick();
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (mouseX > this.x && mouseX < this.x + this.entryWidth && mouseY > this.y && mouseY < this.y + this.entryHeight - 4) {
				boolean forward = mouseX > this.x + this.entryWidth / 2.0;
				this.setter.accept(forward);
				playClick();
				return true;
			}
			return false;
				}
		//?}

	}

	private static class IntRow extends Row {
		private final String labelKey;
		private final Supplier<Integer> getter;
		private final Consumer<Integer> setter;
		private final int min, max;

		IntRow(String labelKey, Supplier<Integer> getter, Consumer<Integer> setter, int min, int max) {
			this.labelKey = labelKey;
			this.getter = getter;
			this.setter = setter;
			this.min = min;
			this.max = max;
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, this.labelKey, "< " + this.getter.get() + " >", 0xFFE0E000);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				int delta = click.x() > this.x + this.entryWidth / 2.0 ? 1 : -1;
				this.setter.accept(Mth.clamp(this.getter.get() + delta, this.min, this.max));
				playClick();
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (mouseX > this.x && mouseX < this.x + this.entryWidth && mouseY > this.y && mouseY < this.y + this.entryHeight - 4) {
				int delta = mouseX > this.x + this.entryWidth / 2.0 ? 1 : -1;
				this.setter.accept(Mth.clamp(this.getter.get() + delta, this.min, this.max));
				playClick();
				return true;
			}
			return false;
				}
		//?}

	}

	/** Free-text duration input; accepts "3600", "1h", "30min", "1m", "90s" and compounds. */
	private static class DurationRow extends Row {
		private final String labelKey;
		private final Supplier<Integer> getter;
		private final Consumer<Integer> setter;
		/** The text is only a draft while the box has focus: committing per keystroke
		 * pushed half-typed values ("2" on the way to "20") into the live config. */
		private boolean pendingApply = false;
		private final int minTicks;
		private final int maxTicks;
		final EditBox field;

		DurationRow(String labelKey, Supplier<Integer> getter, Consumer<Integer> setter, int minTicks) {
			this(labelKey, getter, setter, minTicks, Integer.MAX_VALUE);
		}

		DurationRow(String labelKey, Supplier<Integer> getter, Consumer<Integer> setter, int minTicks, int maxTicks) {
			this.labelKey = labelKey;
			this.getter = getter;
			this.setter = setter;
			this.minTicks = minTicks;
			this.maxTicks = maxTicks;
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 80, 14, McText.literal("timeout"));
			this.field.setMaxLength(16);
			this.field.setValue(DurationParser.format(getter.get()));
			this.field.setResponder(text -> this.pendingApply = true);
		}

		private void applyDraft() {
			if (!this.pendingApply) return;
			this.pendingApply = false;
			int ticks = DurationParser.parse(this.field.getValue());
			if (ticks >= this.minTicks && ticks <= this.maxTicks) this.setter.accept(ticks);
		}

		@Override
		void flushEdits() {
			this.applyDraft();
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			int parsed = DurationParser.parse(this.field.getValue());
			boolean valid = parsed >= this.minTicks && parsed <= this.maxTicks;
			drawBase(context, this.labelKey,
					fitValueText(McText.translatable(this.labelKey), DurationParser.format(this.getter.get()), "", DURATION_RIGHT_INSET),
					valid ? 0xFFE0E000 : 0xFFC00000, DURATION_RIGHT_INSET);
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			McWidget.setY(this.field, this.y + 2);
			McRender.pushPose(context);
			context.renderWidget(this.field, mouseX, mouseY, deltaTicks);
			McRender.popPose(context);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}
		//?}


				//? if >=26.1 {
		/*@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			// the drag lands on the row (the list's focused child), not the nested
			// field: forward it so EditBox's onDrag selection works
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
				}*/
		//?} else {
		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			// the drag lands on the row (the list's focused child), not the nested
			// field: forward it so EditBox's onDrag selection works
			return this.field.isFocused() && McInput.mouseDragged(this.field, mouseX, mouseY, button, dragX, dragY);
				}
		//?}


		/** Losing focus with unparsable or out-of-range text restores the stored
		 * value, so the field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			// deliberately NOT gated on isFocused(): the scroll path blurs WITHOUT
			// committing (so a wheel scroll cannot write a half-typed value), and this
			// reconcile is the click path's "commit the drafts the pointer left behind".
			// Gating on focus made a scroll-blurred draft commit only at close, which
			// also let a row removal drop it.
			this.applyDraft();
			int parsed = DurationParser.parse(this.field.getValue());
			if (parsed < this.minTicks || parsed > this.maxTicks) {
				this.field.setValue(DurationParser.format(this.getter.get()));
			}
		}

		@Override
		void clearFieldFocus() {
			// scroll path ONLY: blur WITHOUT committing — an involuntary scroll must not
			// write a half-typed draft (it stays pending and commits at a deliberate
			// focus change, a save, or screen close). The name is legacy.
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(int keyCode, int scanCode, int modifiers) {
			return this.field.isFocused() && McInput.keyPressed(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public void handleRelease(int keyCode, int scanCode, int modifiers) {
			McInput.keyReleased(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public boolean handleChar(char c) {
			return this.field.isFocused() && McInput.charTyped(this.field, c, 0);
		}

		//? if >=26.1 {
		/*@Override
		public boolean handlePreedit(net.minecraft.client.input.PreeditEvent input) {
			return this.field.isFocused() && this.field.preeditUpdated(input);
		}*/
		//?}
	}

	private class PosRow extends Row {
		private final String labelKey;
		private final Supplier<BlockPos> getter;
		private final Consumer<BlockPos> setter;
		private final @Nullable PositionPicker.Slot slot;
		/** Optional picker-backed positions (grindstone) may be blanked; the
		 * required rig positions must not be clearable. */
		private final boolean clearable;
		/** An emptied field is only a draft: applying the clear on the empty moment
		 * mid-typing pushed a null into the live config, and a running job then saw
		 * the position vanish (the sand-wheel check stopped the run, an emptied
		 * region silently disabled the staging guard). Applied on focus loss or at
		 * close instead. */
		private boolean pendingClear = false;
		/** A non-empty edit is a draft too: a half-typed but already parsable
		 * coordinate must not move the sand-wheel / region under a running job. */
		private boolean pendingApply = false;
		final EditBox field;

		PosRow(String labelKey, Supplier<BlockPos> getter, Consumer<BlockPos> setter, @Nullable PositionPicker.Slot slot) {
			this(labelKey, getter, setter, slot, false);
		}

		PosRow(String labelKey, Supplier<BlockPos> getter, Consumer<BlockPos> setter, @Nullable PositionPicker.Slot slot, boolean clearable) {
			this.labelKey = labelKey;
			this.getter = getter;
			this.setter = setter;
			this.slot = slot;
			this.clearable = clearable;
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, McText.literal("position"));
			this.field.setMaxLength(40);
			// pre-fill with the stored value (before the responder exists) so a
			// one-component tweak does not require retyping the whole row
			BlockPos initialPos = getter.get();
			this.field.setValue(initialPos == null ? "" : initialPos.getX() + ", " + initialPos.getY() + ", " + initialPos.getZ());
			this.field.setResponder(text -> {
				if (text.trim().isEmpty()) {
					// required positions (picker-backed) must not be clearable: an empty
					// field that saves null silently breaks the rig on the next run
					this.pendingClear = true;
					this.pendingApply = false;
					return;
				}
				// deferred: a running job must not see a value the player is still
				// typing (a cleared or half-typed grindstone coordinate stops the run)
				this.pendingClear = false;
				this.pendingApply = true;
			});
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			// unset optional positions show neutral yellow, missing required ones red
			int color = this.getter.get() == null
					? (this.clearable ? 0xFFE0E000 : 0xFFC00000)
					: 0xFF00C000;
			drawBase(context, this.labelKey, this.fittedText(), color, FIELD_RIGHT_INSET);
			// right-anchor: a fixed -320 offset pushes the field offscreen on
			// narrow (Android/FCL) widths, and the delta row has no picker fallback
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			McWidget.setY(this.field, this.y + 2);
			McRender.pushPose(context);
			context.renderWidget(this.field, mouseX, mouseY, deltaTicks);
			McRender.popPose(context);
		}

		private String suffix() {
			return this.slot == null ? "" : "  [" + McText.translatable("trademaster.button.pick_in_game").getString() + "]";
		}

		private String fittedText() {
			BlockPos pos = this.getter.get();
			String value = pos == null ? "-" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
			return fitValueText(McText.translatable(this.labelKey), value, this.suffix(), FIELD_RIGHT_INSET);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				if (this.slot != null && this.clickInValueText(click.x())) {
					playClick();
					// a refused pick (run in progress) must keep the settings screen
					// open: closing it would strand the user in-game with no pick armed
					if (PositionPicker.begin(this.slot, this.setter)) {
						McClient.setScreen(Minecraft.getInstance(), null);
					}
					return true;
				}
				return false;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			if (mouseX > this.x && mouseX < this.x + this.entryWidth && mouseY > this.y && mouseY < this.y + this.entryHeight - 4) {
				if (this.slot != null && this.clickInValueText(mouseX)) {
					playClick();
					// a refused pick (run in progress) must keep the settings screen
					// open: closing it would strand the user in-game with no pick armed
					if (PositionPicker.begin(this.slot, this.setter)) {
						McClient.setScreen(Minecraft.getInstance(), null);
					}
					return true;
				}
				return false;
			}
			return false;
				}
		//?}


				//? if >=26.1 {
		/*@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
				}*/
		//?} else {
		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			return this.field.isFocused() && McInput.mouseDragged(this.field, mouseX, mouseY, button, dragX, dragY);
				}
		//?}


		private boolean clickInValueText(double clickX) {
			String suffix = this.suffix();
			// suffix.isEmpty() can never decide this (audited 2026-09-19): suffix() is
			// empty exactly when slot == null, which the first test already caught
			if (this.slot == null || suffix.isEmpty()) return false;
			Font font = Minecraft.getInstance().font;
			int right = this.x + this.entryWidth - FIELD_RIGHT_INSET;
			// only the "[pick]" suffix arms the picker — a tap on the coordinate
			// value itself must not close the screen and start a pick
			return touchZone(clickX, right - font.width(suffix), right);
		}

		/** Losing focus with unparsable text restores the stored position, so the
		 * field never keeps showing a value that was never applied. An emptied
		 * required position is restored the same way. */
		void reconcileOnFocusLoss() {
			// deliberately NOT gated on isFocused(): the scroll path blurs WITHOUT
			// committing (so a wheel scroll cannot write a half-typed value), and this
			// reconcile is the click path's "commit the drafts the pointer left behind".
			// Gating on focus made a scroll-blurred draft commit only at close, which
			// also let a row removal drop it.
			this.applyPendingClear();
			String text = this.field.getValue();
			BlockPos pos = this.getter.get();
			boolean invalid = text.trim().isEmpty()
					? this.slot != null && !this.clearable && pos != null
					: parsePos(text, Minecraft.getInstance().player) == null;
			if (invalid) {
				this.field.setValue(pos == null ? "" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
			}
		}

		@Override
		void clearFieldFocus() {
			// scroll path ONLY: blur WITHOUT committing — an involuntary scroll must not
			// write a half-typed draft (it stays pending and commits at a deliberate
			// focus change, a save, or screen close). The name is legacy.
			this.field.setFocused(false);
		}

		/** Commits whichever draft is pending (the name is historical): an emptied
		 * field clears when it is clearable, a typed value is parsed and applied. */
		private void applyPendingClear() {
			if (this.pendingClear) {
				this.pendingClear = false;
				this.pendingApply = false;
				if (this.slot == null || this.clearable) this.setter.accept(null);
				return;
			}
			if (!this.pendingApply) return;
			this.pendingApply = false;
			BlockPos parsed = parsePos(this.field.getValue(), Minecraft.getInstance().player);
			if (parsed != null) this.setter.accept(parsed);
		}

		@Override
		void flushEdits() {
			this.applyPendingClear();
		}

		@Override
		public boolean handleKey(int keyCode, int scanCode, int modifiers) {
			return this.field.isFocused() && McInput.keyPressed(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public void handleRelease(int keyCode, int scanCode, int modifiers) {
			McInput.keyReleased(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public boolean handleChar(char c) {
			return this.field.isFocused() && McInput.charTyped(this.field, c, 0);
		}

		//? if >=26.1 {
		/*@Override
		public boolean handlePreedit(net.minecraft.client.input.PreeditEvent input) {
			return this.field.isFocused() && this.field.preeditUpdated(input);
		}*/
		//?}
	}

	/** Region extension (dx dy dz); signed values define direction, no ~ relative support. */
	private class DeltaRow extends Row {
		private final String labelKey;
		private final Supplier<BlockPos> getter;
		private final Consumer<BlockPos> setter;
		/** Deferred clear: see PosRow.pendingClear. */
		private boolean pendingClear = false;
		/** Deferred value: see PosRow.pendingApply. */
		private boolean pendingApply = false;
		final EditBox field;

		DeltaRow(String labelKey, Supplier<BlockPos> getter, Consumer<BlockPos> setter) {
			this.labelKey = labelKey;
			this.getter = getter;
			this.setter = setter;
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, McText.literal("delta"));
			this.field.setMaxLength(40);
			BlockPos initialDelta = getter.get();
			this.field.setValue(initialDelta == null ? "" : initialDelta.getX() + ", " + initialDelta.getY() + ", " + initialDelta.getZ());
			this.field.setResponder(text -> {
				if (text.trim().isEmpty()) {
					// deferred: the empty moment while retyping must not reach the live
					// config — an emptied region silently disables the staging guard,
					// which then lets the summon button fire with nobody in the region
					this.pendingClear = true;
					this.pendingApply = false;
					return;
				}
				// deferred as well: a half-typed extent would resize the staging area
				// under a running job
				this.pendingClear = false;
				this.pendingApply = true;
			});
		}

		/** Commits whichever draft is pending (name kept from the clear-only version). */
		private void applyPendingClear() {
			if (this.pendingClear) {
				this.pendingClear = false;
				this.pendingApply = false;
				this.setter.accept(null);
				return;
			}
			if (!this.pendingApply) return;
			this.pendingApply = false;
			BlockPos parsed = parsePos(this.field.getValue(), null);
			if (parsed != null && inDeltaRange(parsed)) this.setter.accept(parsed);
		}

		@Override
		void flushEdits() {
			this.applyPendingClear();
		}

		/** The delta is a region size: components beyond ±64 are typos that would
		 * stretch the staging scan over thousands of blocks every cycle. */
		private static boolean inDeltaRange(BlockPos pos) {
			return Math.abs(pos.getX()) <= 64 && Math.abs(pos.getY()) <= 64 && Math.abs(pos.getZ()) <= 64;
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			BlockPos delta = this.getter.get();
			String value = delta == null ? "-" : delta.getX() + ", " + delta.getY() + ", " + delta.getZ();
			drawBase(context, this.labelKey,
					fitValueText(McText.translatable(this.labelKey), value, "", FIELD_RIGHT_INSET),
					delta == null ? 0xFFC00000 : 0xFF00C000, FIELD_RIGHT_INSET);
			// right-anchor: a fixed -320 offset pushes the field offscreen on
			// narrow (Android/FCL) widths, and the delta row has no picker fallback
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			McWidget.setY(this.field, this.y + 2);
			McRender.pushPose(context);
			context.renderWidget(this.field, mouseX, mouseY, deltaTicks);
			McRender.popPose(context);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}
		//?}


				//? if >=26.1 {
		/*@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
				}*/
		//?} else {
		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			return this.field.isFocused() && McInput.mouseDragged(this.field, mouseX, mouseY, button, dragX, dragY);
				}
		//?}


		/** Losing focus with unparsable or out-of-range text restores the stored
		 * delta, so the field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			// deliberately NOT gated on isFocused(): the scroll path blurs WITHOUT
			// committing (so a wheel scroll cannot write a half-typed value), and this
			// reconcile is the click path's "commit the drafts the pointer left behind".
			// Gating on focus made a scroll-blurred draft commit only at close, which
			// also let a row removal drop it.
			this.applyPendingClear();
			String text = this.field.getValue();
			if (text.trim().isEmpty()) return;
			BlockPos parsed = parsePos(text, null);
			if (parsed == null || !inDeltaRange(parsed)) {
				BlockPos delta = this.getter.get();
				this.field.setValue(delta == null ? "" : delta.getX() + ", " + delta.getY() + ", " + delta.getZ());
			}
		}

		@Override
		void clearFieldFocus() {
			// scroll path ONLY: blur WITHOUT committing — an involuntary scroll must not
			// write a half-typed draft (it stays pending and commits at a deliberate
			// focus change, a save, or screen close). The name is legacy.
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(int keyCode, int scanCode, int modifiers) {
			return this.field.isFocused() && McInput.keyPressed(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public void handleRelease(int keyCode, int scanCode, int modifiers) {
			McInput.keyReleased(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public boolean handleChar(char c) {
			return this.field.isFocused() && McInput.charTyped(this.field, c, 0);
		}

		//? if >=26.1 {
		/*@Override
		public boolean handlePreedit(net.minecraft.client.input.PreeditEvent input) {
			return this.field.isFocused() && this.field.preeditUpdated(input);
		}*/
		//?}
	}

	/** Curse-book drop direction (yaw, pitch); blank = unset. Yaw semantics:
	 * -180=N, -90=E, 0=S, 90=W; pitch positive looks down, ±90 max. */
	private class DirectionRow extends Row {
		/** Deferred clear: see PosRow.pendingClear. */
		private boolean pendingClear = false;
		/** Deferred value: see PosRow.pendingApply. */
		private boolean pendingApply = false;
		final EditBox field;

		DirectionRow() {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, McText.literal("direction"));
			this.field.setMaxLength(24);
			this.field.setValue(format(c.curseDropYaw, c.curseDropPitch));
			this.field.setResponder(text -> {
				if (text.trim().isEmpty()) {
					// deferred: nothing may punch a null into the live config while
					// typing, or a cursed book reaching DROP_AIM aborts the recycle
					this.pendingClear = true;
					this.pendingApply = false;
					return;
				}
				// deferred as well: a half-typed direction would be used by a drop
				// that happens to run at that very moment
				this.pendingClear = false;
				this.pendingApply = true;
			});
		}

		private void applyPendingClear() {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			if (this.pendingClear) {
				this.pendingClear = false;
				this.pendingApply = false;
				c.curseDropYaw = null;
				c.curseDropPitch = null;
				return;
			}
			if (!this.pendingApply) return;
			this.pendingApply = false;
			float[] parsed = parse(this.field.getValue());
			if (parsed != null) {
				c.curseDropYaw = parsed[0];
				c.curseDropPitch = parsed[1];
			}
		}

		@Override
		void flushEdits() {
			this.applyPendingClear();
		}

		private static String format(Float yaw, Float pitch) {
			if (yaw == null || pitch == null) return "";
			return trim(yaw) + ", " + trim(pitch);
		}

		private static String trim(float value) {
			return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
		}

		private static float @Nullable [] parse(String text) {
			String[] parts = text.trim().split("[,\\s]+");
			if (parts.length != 2) return null;
			try {
				float yaw = Float.parseFloat(parts[0]);
				float pitch = Float.parseFloat(parts[1]);
				if (Float.isNaN(yaw) || Float.isNaN(pitch)) return null;
				if (yaw < -180f || yaw > 180f || pitch < -90f || pitch > 90f) return null;
				return new float[]{yaw, pitch};
			} catch (NumberFormatException e) {
				return null;
			}
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			boolean set = c.curseDropYaw != null && c.curseDropPitch != null;
			String value = set ? format(c.curseDropYaw, c.curseDropPitch) : "-";
			drawBase(context, "trademaster.settings.curseDropDirection",
					fitValueText(McText.translatable("trademaster.settings.curseDropDirection"), value, "", FIELD_RIGHT_INSET),
					set ? 0xFF00C000 : 0xFFE0E000, FIELD_RIGHT_INSET);
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			McWidget.setY(this.field, this.y + 2);
			McRender.pushPose(context);
			context.renderWidget(this.field, mouseX, mouseY, deltaTicks);
			McRender.popPose(context);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}
		//?}


				//? if >=26.1 {
		/*@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
				}*/
		//?} else {
		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			return this.field.isFocused() && McInput.mouseDragged(this.field, mouseX, mouseY, button, dragX, dragY);
				}
		//?}


		/** Losing focus with unparsable text restores the stored direction, so the
		 * field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			// deliberately NOT gated on isFocused(): the scroll path blurs WITHOUT
			// committing (so a wheel scroll cannot write a half-typed value), and this
			// reconcile is the click path's "commit the drafts the pointer left behind".
			// Gating on focus made a scroll-blurred draft commit only at close, which
			// also let a row removal drop it.
			this.applyPendingClear();
			String text = this.field.getValue();
			if (text.trim().isEmpty()) return;
			if (parse(text) == null) {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				this.field.setValue(format(c.curseDropYaw, c.curseDropPitch));
			}
		}

		@Override
		void clearFieldFocus() {
			// scroll path ONLY: blur WITHOUT committing — an involuntary scroll must not
			// write a half-typed draft (it stays pending and commits at a deliberate
			// focus change, a save, or screen close). The name is legacy.
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(int keyCode, int scanCode, int modifiers) {
			return this.field.isFocused() && McInput.keyPressed(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public void handleRelease(int keyCode, int scanCode, int modifiers) {
			McInput.keyReleased(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public boolean handleChar(char c) {
			return this.field.isFocused() && McInput.charTyped(this.field, c, 0);
		}

		//? if >=26.1 {
		/*@Override
		public boolean handlePreedit(net.minecraft.client.input.PreeditEvent input) {
			return this.field.isFocused() && this.field.preeditUpdated(input);
		}*/
		//?}
	}

	/** Custom curse enchantment ids (mod curses outside the vanilla curse tag);
	 * minecraft: ids are rejected — vanilla curses are tag-detected anyway. */
	private class CurseListRow extends Row {
		/** The list is only rewritten when the edit is committed: rebuilding it on
		 * every keystroke wiped ids the moment the text became unparsable mid-edit,
		 * and that loss was then written to disk on close. */
		private boolean pendingApply = false;
		final EditBox field;

		CurseListRow() {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, McText.literal("curses"));
			this.field.setMaxLength(256);
			this.field.setValue(String.join(", ", c.customCurseEnchantments));
			this.field.setResponder(text -> this.pendingApply = true);
		}

		private void applyDraft() {
			if (!this.pendingApply) return;
			this.pendingApply = false;
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			c.customCurseEnchantments.clear();
			c.customCurseEnchantments.addAll(parseCurses(this.field.getValue()));
		}

		@Override
		void flushEdits() {
			this.applyDraft();
		}

		private static java.util.LinkedHashSet<String> parseCurses(String text) {
			java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
			for (String token : text.split("[,\\s]+")) {
				if (token.isEmpty()) continue;
				net.minecraft.resources.ResourceLocation id = McIds.tryParse(token);
				if (id != null && !"minecraft".equals(id.getNamespace())) {
					ids.add(id.toString());
				}
			}
			return ids;
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			String value = c.customCurseEnchantments.isEmpty() ? "-" : String.join(", ", c.customCurseEnchantments);
			drawBase(context, "trademaster.settings.customCurses",
					fitValueText(McText.translatable("trademaster.settings.customCurses"), value, "", FIELD_RIGHT_INSET),
					0xFFE0E000, FIELD_RIGHT_INSET);
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			McWidget.setY(this.field, this.y + 2);
			McRender.pushPose(context);
			context.renderWidget(this.field, mouseX, mouseY, deltaTicks);
			McRender.popPose(context);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
				}
		//?}


				//? if >=26.1 {
		/*@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
				}*/
		//?} else {
		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			return this.field.isFocused() && McInput.mouseDragged(this.field, mouseX, mouseY, button, dragX, dragY);
				}
		//?}


		/** Normalize on focus loss: invalid tokens and forbidden vanilla ids
		 * vanish from the field, matching what is actually stored. */
		void reconcileOnFocusLoss() {
			// deliberately NOT gated on isFocused(): the scroll path blurs WITHOUT
			// committing (so a wheel scroll cannot write a half-typed value), and this
			// reconcile is the click path's "commit the drafts the pointer left behind".
			// Gating on focus made a scroll-blurred draft commit only at close, which
			// also let a row removal drop it.
			this.applyDraft();
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			this.field.setValue(String.join(", ", c.customCurseEnchantments));
		}

		@Override
		void clearFieldFocus() {
			// scroll path ONLY: blur WITHOUT committing — an involuntary scroll must not
			// write a half-typed draft (it stays pending and commits at a deliberate
			// focus change, a save, or screen close). The name is legacy.
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(int keyCode, int scanCode, int modifiers) {
			return this.field.isFocused() && McInput.keyPressed(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public void handleRelease(int keyCode, int scanCode, int modifiers) {
			McInput.keyReleased(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public boolean handleChar(char c) {
			return this.field.isFocused() && McInput.charTyped(this.field, c, 0);
		}

		//? if >=26.1 {
		/*@Override
		public boolean handlePreedit(net.minecraft.client.input.PreeditEvent input) {
			return this.field.isFocused() && this.field.preeditUpdated(input);
		}*/
		//?}
	}

	/** One bound overflow chest; type coordinates to move it, click the [Remove] label to remove it. */
	private class ChestRow extends Row {
		private final int index;
		private final Runnable rebuild;
		final EditBox field;
		/** The rebuild after a removal is deferred to the next tick; a fast
		 * double-tap hits this same stale row instance twice, and the second
		 * click's index would point at the shifted-in NEXT chest — ignore it. */
		private boolean removed = false;
		/** The text is only a draft while the box has focus: a half-typed coordinate
		 * ("1, -60, 11" on the way to "15, -60, 11") was written straight into the
		 * chest list, sending the depositor to a block that is not the chest. */
		private boolean pendingApply = false;
		/** The binding this row was built for: a draft must never be written into
		 * whatever chest slid into this index after a removal. Refreshed after every
		 * successful write so a second edit of the same row is not mistaken for an
		 * index shift (a refused start keeps the screen open and never rebuilds). */
		private BlockPos originalPos;

		ChestRow(int index, Runnable rebuild) {
			this.index = index;
			this.rebuild = rebuild;
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, McText.literal("chest"));
			this.field.setMaxLength(40);
			BlockPos initialChest = TradeMasterConfig.INSTANCE.chestPositions.get(index);
			this.originalPos = initialChest;
			this.field.setValue(initialChest == null ? "" : initialChest.getX() + ", " + initialChest.getY() + ", " + initialChest.getZ());
			this.field.setResponder(text -> {
				if (this.removed || text.trim().isEmpty()) return;
				this.pendingApply = true;
			});
		}

		private void applyDraft() {
			if (!this.pendingApply) return;
			this.pendingApply = false;
			if (this.removed || this.field.getValue().trim().isEmpty()) return;
			// a removal elsewhere in the list shifts every later index: never write
			// this draft into whichever chest slid into this row's index
			if (!this.originalPos.equals(this.currentPos())) return;
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			BlockPos parsed = parsePos(this.field.getValue(), Minecraft.getInstance().player);
			if (parsed != null && this.index < c.chestPositions.size()) {
				BlockPos stored = parsed.immutable();
				c.chestPositions.set(this.index, stored);
				this.originalPos = stored;
			}
		}

		@Override
		void flushEdits() {
			this.applyDraft();
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, McText.translatable("trademaster.settings.chestPos", this.index + 1),
					this.fittedText(),
					this.currentPos() == null ? 0xFFC00000 : 0xFF00C000, FIELD_RIGHT_INSET);
			// right-anchor: a fixed -320 offset pushes the field offscreen on
			// narrow (Android/FCL) widths, and the delta row has no picker fallback
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			McWidget.setY(this.field, this.y + 2);
			McRender.pushPose(context);
			context.renderWidget(this.field, mouseX, mouseY, deltaTicks);
			McRender.popPose(context);
		}

		private @Nullable BlockPos currentPos() {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			return this.index < c.chestPositions.size() ? c.chestPositions.get(this.index) : null;
		}

		private String valueText() {
			BlockPos pos = this.currentPos();
			return pos == null ? "-" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
		}

		private String removeLabel() {
			return "[" + McText.translatable("trademaster.button.remove").getString() + "]";
		}

		private String fittedText() {
			return fitValueText(McText.translatable("trademaster.settings.chestPos", this.index + 1),
					this.valueText(), "  " + this.removeLabel(), FIELD_RIGHT_INSET);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (this.removed) return false;
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				if (this.index < c.chestPositions.size() && this.clickInRemoveLabel(click.x())) {
					if (!this.originalPos.equals(this.currentPos())) {
						// a removal earlier in this tick already shifted the list (the
						// rebuild is deferred to the next tick): this row no longer
						// describes the chest at its index — rebuild instead of
						// deleting the wrong chest
						this.rebuild.run();
						return false;
					}
					playClick();
					this.removed = true;
					c.chestPositions.remove(this.index);
					c.save();
					this.rebuild.run();
					return true;
				}
				return false;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (this.removed) return false;
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			if (mouseX > this.x && mouseX < this.x + this.entryWidth && mouseY > this.y && mouseY < this.y + this.entryHeight - 4) {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				if (this.index < c.chestPositions.size() && this.clickInRemoveLabel(mouseX)) {
					if (!this.originalPos.equals(this.currentPos())) {
						// a removal earlier in this tick already shifted the list (the
						// rebuild is deferred to the next tick): this row no longer
						// describes the chest at its index — rebuild instead of
						// deleting the wrong chest
						this.rebuild.run();
						return false;
					}
					playClick();
					this.removed = true;
					c.chestPositions.remove(this.index);
					c.save();
					this.rebuild.run();
					return true;
				}
				return false;
			}
			return false;
				}
		//?}


				//? if >=26.1 {
		/*@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return !this.removed && this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
				}*/
		//?} else {
		@Override
		public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
			return !this.removed && this.field.isFocused() && McInput.mouseDragged(this.field, mouseX, mouseY, button, dragX, dragY);
				}
		//?}


		private boolean clickInRemoveLabel(double clickX) {
			Font font = Minecraft.getInstance().font;
			int right = this.x + this.entryWidth - FIELD_RIGHT_INSET;
			int suffixStart = right - font.width(this.removeLabel());
			return labelZone(clickX, suffixStart, right);
		}

		/** Losing focus with unparsable text restores the stored coordinates, so
		 * the field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			// deliberately NOT gated on isFocused(): the scroll path blurs WITHOUT
			// committing (so a wheel scroll cannot write a half-typed value), and this
			// reconcile is the click path's "commit the drafts the pointer left behind".
			// Gating on focus made a scroll-blurred draft commit only at close, which
			// also let a row removal drop it.
			this.applyDraft();
			BlockPos pos = this.currentPos();
			if (pos != null && parsePos(this.field.getValue(), Minecraft.getInstance().player) == null) {
				this.field.setValue(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
			}
		}

		@Override
		void clearFieldFocus() {
			// scroll path ONLY: blur WITHOUT committing — an involuntary scroll must not
			// write a half-typed draft (it stays pending and commits at a deliberate
			// focus change, a save, or screen close). The name is legacy.
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(int keyCode, int scanCode, int modifiers) {
			return this.field.isFocused() && McInput.keyPressed(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public void handleRelease(int keyCode, int scanCode, int modifiers) {
			McInput.keyReleased(this.field, keyCode, scanCode, modifiers);
		}

		@Override
		public boolean handleChar(char c) {
			return this.field.isFocused() && McInput.charTyped(this.field, c, 0);
		}

		//? if >=26.1 {
		/*@Override
		public boolean handlePreedit(net.minecraft.client.input.PreeditEvent input) {
			return this.field.isFocused() && this.field.preeditUpdated(input);
		}*/
		//?}
	}

	private static class ActionRow extends Row {
		private final String labelKey;
		/** Reports whether the action actually ran: the picker refuses while a run
		 * is active, and a refusal must not click. */
		private final java.util.function.BooleanSupplier action;

		ActionRow(String labelKey, java.util.function.BooleanSupplier action) {
			this.labelKey = labelKey;
			this.action = action;
		}

		public void drawRow(McGraphics context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, this.labelKey, "[+]", 0xFFE0E000);
		}

				//? if >=26.1 {
		/*@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.button() != 0) return false;
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				// the action itself may refuse (the picker rejects while a run is
				// active): only sound when it actually ran, like BoolRow does
				boolean ran = this.action.getAsBoolean();
				if (ran) playClick();
				return true;
			}
			return false;
				}*/
		//?} else {
		@Override
		public boolean mouseClicked(double mouseX, double mouseY, int button) {
			if (button != 0) return false;
			if (mouseX > this.x && mouseX < this.x + this.entryWidth && mouseY > this.y && mouseY < this.y + this.entryHeight - 4) {
				// the action itself may refuse (the picker rejects while a run is
				// active): only sound when it actually ran, like BoolRow does
				boolean ran = this.action.getAsBoolean();
				if (ran) playClick();
				return true;
			}
			return false;
				}
		//?}

	}
}
