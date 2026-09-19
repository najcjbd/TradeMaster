package dev.trademaster.gui;

import dev.trademaster.config.DurationParser;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RerollController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class SettingsListWidget extends AbstractSelectionList<SettingsListWidget.Row> {

	public SettingsListWidget(Minecraft client, int width, int height, int top, int itemHeight, Runnable rebuild) {
		super(client, width, height, top, itemHeight);
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;

		addEntry(new CycleRow("trademaster.settings.mechanism",
				() -> c.rerollMechanism.getDisplayName(),
				cycle -> c.rerollMechanism = dev.trademaster.config.RerollMechanism.valueOf(nextEnum(c.rerollMechanism.name(), dev.trademaster.config.RerollMechanism.values(), cycle))));
		addEntry(new CycleRow("trademaster.settings.detection",
				() -> c.detectionMode.getDisplayName(),
				cycle -> c.detectionMode = dev.trademaster.config.DetectionMode.valueOf(nextEnum(c.detectionMode.name(), dev.trademaster.config.DetectionMode.values(), cycle))));

		addEntry(new BoolRow("trademaster.settings.reserveCheaperTraders", () -> c.reserveCheaperTraders, v -> c.reserveCheaperTraders = v));
		addEntry(new IntRow("trademaster.settings.minLockEmeralds", () -> c.minLockEmeralds, v -> c.minLockEmeralds = v, 1, 64));
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
		addEntry(new HuntRow(), 44);
		addEntry(new IntRow("trademaster.settings.lockMaxRetries", () -> c.lockMaxRetries, v -> c.lockMaxRetries = v, -1, 999));
		addEntry(new IntRow("trademaster.settings.offhandSwapRetries", () -> c.offhandSwapRetries, v -> c.offhandSwapRetries = v, -1, 999));
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
			if (PositionPicker.begin(PositionPicker.Slot.CHEST, pos -> c.chestPositions.add(pos.immutable()))) {
				Minecraft.getInstance().gui.setScreen(null);
			}
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
		if (player != null && (values[1] < player.level().dimensionType().minY()
				|| values[1] >= player.level().dimensionType().minY() + player.level().dimensionType().height())) return null;
		return new BlockPos(values[0], values[1], values[2]);
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		context.fill(5, 27, this.width + 5, 44, 0xAFC7C0C0);
		context.text(Minecraft.getInstance().font,
				Component.translatable("trademaster.tab.settings"), 9, 32, 0xFF202020);
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
		// +2 matches the list's getFirstEntryY(); heights are summed per row
		// because the hunt row is taller than the default 22px
		int top = this.getY() + 2 - (int) this.scrollAmount();
		java.util.List<Row> rows = this.children();
		for (int i = 0; i < index && i < rows.size(); i++) {
			top += rows.get(i).getHeight();
		}
		return top;
	}

	@Override
	public int getRowBottom(int index) {
		return this.getRowTop(index) + this.children().get(index).getHeight();
	}

	@Override
	public int getRowLeft() {
		return this.getX() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		for (Row row : this.children()) {
			if (row.handleKey(input)) return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		for (Row row : this.children()) {
			row.handleRelease(input);
		}
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (!input.isAllowedChatCharacter()) return false;
		for (Row row : this.children()) {
			if (row.handleChar(input)) return true;
		}
		return super.charTyped(input);
	}

	/** Clicking a partially clipped row makes the list auto-scroll it fully into
	 * view (setFocused -> scrollToEntry): that alignment scroll must not trigger
	 * the focus-clear below, or the click's freshly granted focus dies instantly.
	 * Only user-initiated scrolling clears focus. */
	private boolean suppressFocusClear = false;

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		boolean handled;
		this.suppressFocusClear = true;
		try {
			handled = super.mouseClicked(click, doubled);
		} finally {
			this.suppressFocusClear = false;
		}
		for (Row row : this.children()) {
			if (row instanceof DurationRow duration && !duration.field.isMouseOver(click.x(), click.y())) {
				duration.reconcileOnFocusLoss();
				duration.field.setFocused(false);
			}
			if (row instanceof PosRow pos && !pos.field.isMouseOver(click.x(), click.y())) {
				pos.reconcileOnFocusLoss();
				pos.field.setFocused(false);
			}
			if (row instanceof DeltaRow delta && !delta.field.isMouseOver(click.x(), click.y())) {
				delta.reconcileOnFocusLoss();
				delta.field.setFocused(false);
			}
			if (row instanceof DirectionRow direction && !direction.field.isMouseOver(click.x(), click.y())) {
				direction.reconcileOnFocusLoss();
				direction.field.setFocused(false);
			}
			if (row instanceof CurseListRow curses && !curses.field.isMouseOver(click.x(), click.y())) {
				curses.reconcileOnFocusLoss();
				curses.field.setFocused(false);
			}
			if (row instanceof ChestRow chest && !chest.field.isMouseOver(click.x(), click.y())) {
				chest.reconcileOnFocusLoss();
				chest.field.setFocused(false);
			}
		}
		return handled;
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = this.scrollAmount();
		super.setScrollAmount(amount);
		if (this.scrollAmount() == before || this.suppressFocusClear) return;
		// a focused field scrolled off-screen keeps swallowing keyboard input
		// invisibly (and its responder silently edits the config): drop focus
		// whenever the viewport actually moves (wheel noise at the scroll
		// boundaries must not kill focus mid-typing)
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

		public boolean handleKey(KeyEvent input) { return false; }
		public void handleRelease(KeyEvent input) {}
		public boolean handleChar(CharacterEvent input) { return false; }
		/** Drops the row's EditBox focus (viewport scrolled away); no-op for rows without a field. */
		void clearFieldFocus() {}

		/** Applies an in-flight text draft when the screen is closed without the
		 * field ever losing focus (ESC tears the widgets down, it does not blur). */
		void flushEdits() {}

		protected static void playClick() {
			AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
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
			// entryWidth is only assigned during drawBase: before the first draw
			// fall back to the list-assigned width instead of truncating to "..."
			int width = this.entryWidth > 0 ? this.entryWidth : this.getWidth();
			if (width <= 0) return full;
			int available = width - rightInset - font.width(label) - 18;
			if (font.width(full) <= available) return full;
			int room = available - font.width(suffix) - font.width("...");
			return font.substrByWidth(Component.literal(value), Math.max(0, room)).getString() + "..." + suffix;
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

		protected void drawBase(GuiGraphicsExtractor context, String labelKey, String valueText, int valueColor) {
			drawBase(context, Component.translatable(labelKey), valueText, valueColor, 10);
		}

		protected void drawBase(GuiGraphicsExtractor context, String labelKey, String valueText, int valueColor, int rightInset) {
			drawBase(context, Component.translatable(labelKey), valueText, valueColor, rightInset);
		}

		protected void drawBase(GuiGraphicsExtractor context, Component label, String valueText, int valueColor) {
			drawBase(context, label, valueText, valueColor, 10);
		}

		/** rightInset reserves space on the right so the value does not sit under a hosted EditBox. */
		protected void drawBase(GuiGraphicsExtractor context, Component label, String valueText, int valueColor, int rightInset) {
			this.x = this.getX();
			this.y = this.getY();
			this.entryWidth = this.getWidth();
			this.entryHeight = this.getHeight();
			Font font = Minecraft.getInstance().font;
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, 0x1AC7C0C0);
			context.text(font, label, this.x + 6, this.y + 6, 0xFFFFFFFF);
			int textWidth = font.width(valueText);
			context.text(font, Component.literal(valueText), this.x + this.entryWidth - rightInset - textWidth, this.y + 6, valueColor);
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			boolean value = this.getter.get();
			drawBase(context, this.labelKey,
					Component.translatable(value ? "trademaster.button.enabled" : "trademaster.button.disabled").getString(),
					value ? 0xFF00C000 : 0xFFC00000);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				boolean before = this.getter.get();
				this.setter.accept(!before);
				// a refused toggle (hunt blocked by auto-disable or a running
				// pipeline) must not play the confirm sound
				if (this.getter.get() != before) playClick();
				return true;
			}
			return false;
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
				mc.player.sendSystemMessage(Component.literal("[Trade Master] ")
						.append(Component.translatable(key).withStyle(net.minecraft.ChatFormatting.RED)));
			}
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			super.extractContent(context, mouseX, mouseY, hovered, deltaTicks);
			Font font = Minecraft.getInstance().font;
			java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(
					Component.translatable("trademaster.settings.huntCheaper.caption"),
					Math.max(40, this.entryWidth - 12));
			for (int i = 0; i < lines.size() && i < 3; i++) {
				context.text(font, lines.get(i), this.x + 6, this.y + 14 + i * 9, 0xFF909090);
			}
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, this.labelKey, "< " + this.getter.get() + " >", 0xFFE0E000);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				boolean forward = click.x() > this.x + this.entryWidth / 2.0;
				this.setter.accept(forward);
				playClick();
				return true;
			}
			return false;
		}
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, this.labelKey, "< " + this.getter.get() + " >", 0xFFE0E000);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				int delta = click.x() > this.x + this.entryWidth / 2.0 ? 1 : -1;
				this.setter.accept(Mth.clamp(this.getter.get() + delta, this.min, this.max));
				playClick();
				return true;
			}
			return false;
		}
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
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 80, 14, Component.literal("timeout"));
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			int parsed = DurationParser.parse(this.field.getValue());
			boolean valid = parsed >= this.minTicks && parsed <= this.maxTicks;
			drawBase(context, this.labelKey,
					fitValueText(Component.translatable(this.labelKey), DurationParser.format(this.getter.get()), "", DURATION_RIGHT_INSET),
					valid ? 0xFFE0E000 : 0xFFC00000, DURATION_RIGHT_INSET);
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			this.field.setY(this.y + 2);
			context.pose().pushMatrix();
			this.field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			// the drag lands on the row (the list's focused child), not the nested
			// field: forward it so EditBox's onDrag selection works
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

		/** Losing focus with unparsable or out-of-range text restores the stored
		 * value, so the field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			if (!this.field.isFocused()) return;
			this.applyDraft();
			int parsed = DurationParser.parse(this.field.getValue());
			if (parsed < this.minTicks || parsed > this.maxTicks) {
				this.field.setValue(DurationParser.format(this.getter.get()));
			}
		}

		@Override
		void clearFieldFocus() {
			this.reconcileOnFocusLoss();
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(KeyEvent input) {
			return this.field.isFocused() && this.field.keyPressed(input);
		}

		@Override
		public void handleRelease(KeyEvent input) {
			this.field.keyReleased(input);
		}

		@Override
		public boolean handleChar(CharacterEvent input) {
			return this.field.isFocused() && this.field.charTyped(input);
		}
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
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, Component.literal("position"));
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			// unset optional positions show neutral yellow, missing required ones red
			int color = this.getter.get() == null
					? (this.clearable ? 0xFFE0E000 : 0xFFC00000)
					: 0xFF00C000;
			drawBase(context, this.labelKey, this.fittedText(), color, FIELD_RIGHT_INSET);
			// right-anchor: a fixed -320 offset pushes the field offscreen on
			// narrow (Android/FCL) widths, and the delta row has no picker fallback
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			this.field.setY(this.y + 2);
			context.pose().pushMatrix();
			this.field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
		}

		private String suffix() {
			return this.slot == null ? "" : "  [" + Component.translatable("trademaster.button.pick_in_game").getString() + "]";
		}

		private String fittedText() {
			BlockPos pos = this.getter.get();
			String value = pos == null ? "-" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
			return fitValueText(Component.translatable(this.labelKey), value, this.suffix(), FIELD_RIGHT_INSET);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
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
						Minecraft.getInstance().gui.setScreen(null);
					}
					return true;
				}
				return false;
			}
			return false;
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

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
			if (!this.field.isFocused()) return;
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
			this.reconcileOnFocusLoss();
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
		public boolean handleKey(KeyEvent input) {
			return this.field.isFocused() && this.field.keyPressed(input);
		}

		@Override
		public void handleRelease(KeyEvent input) {
			this.field.keyReleased(input);
		}

		@Override
		public boolean handleChar(CharacterEvent input) {
			return this.field.isFocused() && this.field.charTyped(input);
		}
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
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, Component.literal("delta"));
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			BlockPos delta = this.getter.get();
			String value = delta == null ? "-" : delta.getX() + ", " + delta.getY() + ", " + delta.getZ();
			drawBase(context, this.labelKey,
					fitValueText(Component.translatable(this.labelKey), value, "", FIELD_RIGHT_INSET),
					delta == null ? 0xFFC00000 : 0xFF00C000, FIELD_RIGHT_INSET);
			// right-anchor: a fixed -320 offset pushes the field offscreen on
			// narrow (Android/FCL) widths, and the delta row has no picker fallback
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			this.field.setY(this.y + 2);
			context.pose().pushMatrix();
			this.field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

		/** Losing focus with unparsable or out-of-range text restores the stored
		 * delta, so the field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			if (!this.field.isFocused()) return;
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
			this.reconcileOnFocusLoss();
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(KeyEvent input) {
			return this.field.isFocused() && this.field.keyPressed(input);
		}

		@Override
		public void handleRelease(KeyEvent input) {
			this.field.keyReleased(input);
		}

		@Override
		public boolean handleChar(CharacterEvent input) {
			return this.field.isFocused() && this.field.charTyped(input);
		}
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
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, Component.literal("direction"));
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			boolean set = c.curseDropYaw != null && c.curseDropPitch != null;
			String value = set ? format(c.curseDropYaw, c.curseDropPitch) : "-";
			drawBase(context, "trademaster.settings.curseDropDirection",
					fitValueText(Component.translatable("trademaster.settings.curseDropDirection"), value, "", FIELD_RIGHT_INSET),
					set ? 0xFF00C000 : 0xFFE0E000, FIELD_RIGHT_INSET);
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			this.field.setY(this.y + 2);
			context.pose().pushMatrix();
			this.field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

		/** Losing focus with unparsable text restores the stored direction, so the
		 * field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			if (!this.field.isFocused()) return;
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
			this.reconcileOnFocusLoss();
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(KeyEvent input) {
			return this.field.isFocused() && this.field.keyPressed(input);
		}

		@Override
		public void handleRelease(KeyEvent input) {
			this.field.keyReleased(input);
		}

		@Override
		public boolean handleChar(CharacterEvent input) {
			return this.field.isFocused() && this.field.charTyped(input);
		}
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
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, Component.literal("curses"));
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
				net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(token);
				if (id != null && !"minecraft".equals(id.getNamespace())) {
					ids.add(id.toString());
				}
			}
			return ids;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			String value = c.customCurseEnchantments.isEmpty() ? "-" : String.join(", ", c.customCurseEnchantments);
			drawBase(context, "trademaster.settings.customCurses",
					fitValueText(Component.translatable("trademaster.settings.customCurses"), value, "", FIELD_RIGHT_INSET),
					0xFFE0E000, FIELD_RIGHT_INSET);
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			this.field.setY(this.y + 2);
			context.pose().pushMatrix();
			this.field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

		/** Normalize on focus loss: invalid tokens and forbidden vanilla ids
		 * vanish from the field, matching what is actually stored. */
		void reconcileOnFocusLoss() {
			if (!this.field.isFocused()) return;
			this.applyDraft();
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			this.field.setValue(String.join(", ", c.customCurseEnchantments));
		}

		@Override
		void clearFieldFocus() {
			this.reconcileOnFocusLoss();
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(KeyEvent input) {
			return this.field.isFocused() && this.field.keyPressed(input);
		}

		@Override
		public void handleRelease(KeyEvent input) {
			this.field.keyReleased(input);
		}

		@Override
		public boolean handleChar(CharacterEvent input) {
			return this.field.isFocused() && this.field.charTyped(input);
		}
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

		ChestRow(int index, Runnable rebuild) {
			this.index = index;
			this.rebuild = rebuild;
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, Component.literal("chest"));
			this.field.setMaxLength(40);
			BlockPos initialChest = TradeMasterConfig.INSTANCE.chestPositions.get(index);
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
			TradeMasterConfig c = TradeMasterConfig.INSTANCE;
			BlockPos parsed = parsePos(this.field.getValue(), Minecraft.getInstance().player);
			if (parsed != null && this.index < c.chestPositions.size()) {
				c.chestPositions.set(this.index, parsed.immutable());
			}
		}

		@Override
		void flushEdits() {
			this.applyDraft();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, Component.translatable("trademaster.settings.chestPos", this.index + 1),
					this.fittedText(),
					this.currentPos() == null ? 0xFFC00000 : 0xFF00C000, FIELD_RIGHT_INSET);
			// right-anchor: a fixed -320 offset pushes the field offscreen on
			// narrow (Android/FCL) widths, and the delta row has no picker fallback
			this.field.setX(this.x + this.entryWidth - this.field.getWidth() - 10);
			this.field.setY(this.y + 2);
			context.pose().pushMatrix();
			this.field.extractRenderState(context, mouseX, mouseY, deltaTicks);
			context.pose().popMatrix();
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
			return "[" + Component.translatable("trademaster.button.remove").getString() + "]";
		}

		private String fittedText() {
			return fitValueText(Component.translatable("trademaster.settings.chestPos", this.index + 1),
					this.valueText(), "  " + this.removeLabel(), FIELD_RIGHT_INSET);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (this.removed) return false;
			if (this.field.mouseClicked(click, doubled)) {
				this.field.setFocused(true);
				return true;
			}
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				TradeMasterConfig c = TradeMasterConfig.INSTANCE;
				if (this.index < c.chestPositions.size() && this.clickInRemoveLabel(click.x())) {
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

		@Override
		public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
			return !this.removed && this.field.isFocused() && this.field.mouseDragged(drag, mouseX, mouseY);
		}

		private boolean clickInRemoveLabel(double clickX) {
			Font font = Minecraft.getInstance().font;
			int right = this.x + this.entryWidth - FIELD_RIGHT_INSET;
			int suffixStart = right - font.width(this.removeLabel());
			return labelZone(clickX, suffixStart, right);
		}

		/** Losing focus with unparsable text restores the stored coordinates, so
		 * the field never keeps showing a value that was never applied. */
		void reconcileOnFocusLoss() {
			if (!this.field.isFocused()) return;
			this.applyDraft();
			BlockPos pos = this.currentPos();
			if (pos != null && parsePos(this.field.getValue(), Minecraft.getInstance().player) == null) {
				this.field.setValue(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
			}
		}

		@Override
		void clearFieldFocus() {
			this.reconcileOnFocusLoss();
			this.field.setFocused(false);
		}

		@Override
		public boolean handleKey(KeyEvent input) {
			return this.field.isFocused() && this.field.keyPressed(input);
		}

		@Override
		public void handleRelease(KeyEvent input) {
			this.field.keyReleased(input);
		}

		@Override
		public boolean handleChar(CharacterEvent input) {
			return this.field.isFocused() && this.field.charTyped(input);
		}
	}

	private static class ActionRow extends Row {
		private final String labelKey;
		private final Runnable action;

		ActionRow(String labelKey, Runnable action) {
			this.labelKey = labelKey;
			this.action = action;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			drawBase(context, this.labelKey, "[+]", 0xFFE0E000);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
			if (click.x() > this.x && click.x() < this.x + this.entryWidth && click.y() > this.y && click.y() < this.y + this.entryHeight - 4) {
				playClick();
				this.action.run();
				return true;
			}
			return false;
		}
	}
}
