package dev.trademaster.gui;

//? if >=26.1 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;*/
//?}
import dev.trademaster.compat.McList;
import dev.trademaster.compat.McInput;
import dev.trademaster.compat.McRender;
import dev.trademaster.compat.McGraphics;
import dev.trademaster.compat.McWidget;
import dev.trademaster.compat.McText;
import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.exec.EnchantmentNamer;
import dev.trademaster.matching.TradeMatcher;
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
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;

/**
 * Alias editor: one row per enchantment, each with a free-text field that overrides
 * the name used when a villager is anvil-named (see {@link EnchantmentNamer}).
 * Lists EVERY stored enchantment, enabled or not, so an alias can be prepared
 * before the target is switched on.
 *
 * The field only commits a DRAFT: typing never reaches the config, and the value is
 * applied on focus loss (or when the screen closes) — the same discipline as the
 * settings rows, so a half-typed alias can never end up in a villager's name.
 */
public class AliasListWidget extends AbstractSelectionList<AliasListWidget.AliasRow> {

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

	public AliasListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		//? if >=1.20.2 {
		super(client, width, height, top, itemHeight);
		//?} else {
		/*super(client, width, height, top, top + height, itemHeight);*/
		//?}
		TradeMasterConfig.INSTANCE.enchantments.keySet().stream()
				.sorted(Comparator.comparing(ResourceLocation::toString))
				.forEach(id -> this.addEntry(new AliasRow(id)));
		// custom goods are named too, so their names must be aliasable (and must take
		// part in the collision check below) exactly like enchantments
		TradeMasterConfig.INSTANCE.goods.stream()
				.map(GoodSpec::id)
				.sorted(Comparator.comparing(ResourceLocation::toString))
				.forEach(id -> this.addEntry(new AliasRow(id)));
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
		// clamp: on a narrow list these fixed offsets fall left of the 5px margin
		// (the enchantments page already guards its own fill the same way)
		context.fill(5, 27, Math.max(8, this.width - 14), 44, 0xAFC7C0C0);
		Font font = Minecraft.getInstance().font;
		context.drawString(font, McText.translatable("trademaster.column.enchantment"), 9, 32, 0xFF202020);
		context.drawString(font, McText.translatable("trademaster.column.alias"),
				Math.max(8, this.width - 122), 32, 0xFF202020);
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
		// the scrollbar sits on the widget's right edge, outside the bounds the
		// Screen uses to route mouse events: claim it explicitly (same as the
		// enchantments list)
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
		return this.listTop() + this.firstEntryOffset() - (int) McList.scrollAmount(this) + index * this.entryRowHeight();
	}

	@Override
	public int getRowLeft() {
		return this.listLeft() + this.width / 2 - this.getRowWidth() / 2 - 1;
	}

	//? if >=26.1 {
	/*@Override
	public boolean keyPressed(KeyEvent input) {
		AliasRow editing = this.editing();
		if (editing != null) return editing.field.keyPressed(input);
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(KeyEvent input) {
		AliasRow editing = this.editing();
		if (editing != null) editing.field.keyReleased(input);
		return super.keyReleased(input);
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		AliasRow editing = this.editing();
		// consuming matters: the screen would otherwise deliver the same character a
		// second time to the screen-level focused widget (this list)
		if (editing == null) return super.charTyped(input);
		if (!input.isAllowedChatCharacter()) return false;
		return editing.field.charTyped(input) || super.charTyped(input);
	}

	@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		AliasRow editing = this.editing();
		return editing != null && editing.field.preeditUpdated(input);
	}*/
	//?} else {
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		AliasRow editing = this.editing();
		if (editing != null) return McInput.keyPressed(editing.field, keyCode, scanCode, modifiers);
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
		AliasRow editing = this.editing();
		if (editing != null) McInput.keyReleased(editing.field, keyCode, scanCode, modifiers);
		return super.keyReleased(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean charTyped(char c, int modifiers) {
		AliasRow editing = this.editing();
		// consuming matters: the screen would otherwise deliver the same character a
		// second time to the screen-level focused widget (this list)
		if (editing == null) return super.charTyped(c, modifiers);
		if (!McInput.isAllowedChatCharacter(c)) return false;
		return McInput.charTyped(editing.field, c, modifiers) || super.charTyped(c, modifiers);
	}
	//?}

	private @Nullable AliasRow editing() {
		for (AliasRow row : this.children()) {
			if (row.field.isFocused()) return row;
		}
		return null;
	}

	/** Commits every row's in-flight alias draft: the screen close and tab switch
	 * discard the widgets without another focus-loss pass. Runs to a fixed point:
	 * aliases are validated against each other, so one row's commit can resolve (or
	 * create) another row's conflict, and a single pass in id order would silently
	 * drop the earlier row's draft. */
	public void flushEdits() {
		for (int pass = 0; pass < 8; pass++) {
			boolean progress = false;
			for (AliasRow row : this.children()) {
				if (row.applyDraft()) progress = true;
			}
			if (!progress) break;
		}
	}

	private boolean suppressFocusClear = false;

	//? if >=26.1 {
	/*@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		// right-click is not an edit gesture: do not reconcile/blur/commit drafts
		if (click.button() != 0) return super.mouseClicked(click, doubled);
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
		// right-click is not an edit gesture: do not reconcile/blur/commit drafts
		if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
		beginMouseClick(mouseX, mouseY);
		try {
			return super.mouseClicked(mouseX, mouseY, button);
		} finally {
			this.suppressFocusClear = false;
		}
	}
	//?}

	private void beginMouseClick(double mouseX, double mouseY) {
		this.suppressFocusClear = true;
		// Commit + unfocus every row the click did NOT land in. Without this two
		// fields can hold focus at once, and editing() would route the keystrokes
		// to whichever row comes first — i.e. the wrong enchantment's alias.
		for (AliasRow row : this.children()) {
			if (!row.field.isMouseOver(mouseX, mouseY)) {
				row.field.setFocused(false);
				row.applyDraft();
			}
		}
	}

	@Override
	public void setScrollAmount(double amount) {
		double before = McList.scrollAmount(this);
		super.setScrollAmount(amount);
		if (McList.scrollAmount(this) == before || this.suppressFocusClear) return;
		// a focused field scrolled off-screen would keep swallowing keystrokes: drop
		// focus WITHOUT committing — an involuntary scroll must not write a half-typed
		// alias draft (it still commits at the documented points: a deliberate focus
		// change, a save, or screen close)
		for (AliasRow row : this.children()) {
			row.field.setFocused(false);
		}
	}

	public static class AliasRow extends AbstractSelectionList.Entry<AliasRow> {

		private final ResourceLocation id;
		private final String translationKey;
		public final EditBox field;
		private int x, y, entryWidth, entryHeight;
		/** A non-empty edit is only a draft until focus is lost / the screen closes. */
		private boolean dirty = false;
		/** An emptied field means "remove the alias", applied the same way. */
		private boolean pendingClear = false;
		/** Set when the draft collides with another enchantment's alias or display
		 * name (see conflictKey): the row shows the reason in red and nothing is
		 * written. Cleared as soon as the text changes again. */
		private @Nullable String conflictKey = null;

		public AliasRow(ResourceLocation id) {
			this.id = id;
			this.translationKey = "enchantment." + id.getNamespace() + "." + id.getPath();
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, McText.literal("alias"));
			this.field.setMaxLength(40);
			this.field.setValue(TradeMasterConfig.INSTANCE.customEnchantNames.getOrDefault(id, ""));
			this.field.setResponder(text -> {
				this.conflictKey = null;
				if (text.trim().isEmpty()) {
					this.pendingClear = true;
					this.dirty = false;
					return;
				}
				this.pendingClear = false;
				this.dirty = true;
			});
		}

		/** Commits whichever draft is pending. @return whether the config changed. */
		boolean applyDraft() {
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			if (this.pendingClear) {
				this.dirty = false;
				// setValue fires the responder, which would set pendingClear again:
				// clear the flag AFTER the text so the row does not stay "pending"
				this.field.setValue("");
				this.pendingClear = false;
				boolean removed = config.customEnchantNames.remove(this.id) != null;
				if (removed) config.save();
				return removed;
			}
			if (!this.dirty) return false;
			String value = this.field.getValue().trim();
			if (value.isEmpty()) return false;
			String conflict = conflictKey(this.id, value);
			if (conflict != null) {
				// keep dirty: the collision may be resolved by editing ANOTHER row, and
				// this draft must then be re-validated (flushEdits iterates until no row
				// makes progress, so it gets another chance)
				this.conflictKey = conflict;
				return false;
			}
			boolean changed = false;
			if (!value.equals(config.customEnchantNames.get(this.id))) {
				config.customEnchantNames.put(this.id, value);
				config.save();
				changed = true;
			}
			this.conflictKey = null;
			// setValue always fires the responder (which would set dirty again): re-sync
			// the text first and clear the flag last
			if (!this.field.getValue().equals(value)) this.field.setValue(value);
			this.dirty = false;
			return changed;
		}

		/** @return a lang key explaining why {@code value} is refused, or null when it
		 * is usable. Mirrors {@code TradeMatcher.buildDisplayNameIndex}: a name must not
		 * collide with another enchantment's alias, its display name, its registry
		 * path, or a name recorded in nameHistory — the deduction matches villagers by
		 * name, and a collision drops BOTH sides from the index. Comparisons use the
		 * same sanitizer the naming path uses. */
		private static @Nullable String conflictKey(ResourceLocation id, String value) {
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			// the SAME two transformations the index applies: sanitize ('+' -> '*',
			// digits -> superscripts) AND canonical (collapse whitespace runs). Both
			// sides must agree, or a collision differing only in whitespace passes
			// here and then drops BOTH enchantments from the index.
			String probe = EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(value));
			// SAME SOURCE as buildDisplayNameIndex: it is built over the ENABLED
			// targets only, and its keys are case-SENSITIVE (a HashMap). Checking a
			// disabled target, or comparing case-insensitively, would refuse names
			// that the index happily keeps distinct — a pure false rejection.
			for (EnchantmentTarget other : config.enabledTargets()) {
				if (other.id().equals(id)) continue;
				// the index ALSO drops targets that are not tradeable, whose vanilla
				// minimum price exceeds the configured max, or whose spec result is an
				// air item ("dead" targets): such a name cannot collide there, so
				// refusing it here is a pure false rejection. Same gate as buildNeeds.
				if (!TradeMatcher.targetMatchable(other)) continue;
				String refusal = collision(probe, other.id(),
						EnchantmentNamer.demandDisplayName(other.id()), other.id().getPath());
				if (refusal != null) return refusal;
			}
			// custom goods are named from the same alias map: their names (and their
			// registry paths) are collision sources for enchantment aliases and vice
			// versa, or the index would drop BOTH sides on a collision
			for (GoodSpec good : config.enabledGoods()) {
				if (good.id().equals(id)) continue;
				String refusal = collision(probe, good.id(), good.displayName(), good.path());
				if (refusal != null) return refusal;
			}
			for (Map.Entry<String, ResourceLocation> entry : config.nameHistory.entrySet()) {
				ResourceLocation otherId = entry.getValue();
				if (otherId.equals(id)) continue;
				// the index only records a history name when that demand is still live:
				// an entry for a dead/untradeable target (or a removed good) must not
				// refuse a perfectly legal name
				boolean live = config.goodFor(otherId) != null;
				if (!live) {
					EnchantmentTarget otherTarget = config.enchantments.get(otherId);
					live = otherTarget != null && otherTarget.enabled() && TradeMatcher.targetMatchable(otherTarget);
				}
				if (!live) continue;
				if (EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(entry.getKey())).equals(probe)) {
					return "trademaster.alias.duplicate_name";
				}
			}
			return null;
		}

		/** The refusal for ONE other demand, or null: the alias-vs-alias wording when
		 * the other side is the aliased one, the plain name collision otherwise. */
		private static @Nullable String collision(String probe, ResourceLocation otherId,
				String displayName, String path) {
			String alias = TradeMasterConfig.INSTANCE.customEnchantNames.get(otherId);
			if (alias != null && EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(alias)).equals(probe)) {
				return "trademaster.alias.duplicate_alias";
			}
			if (EnchantmentNamer.canonical(path).equals(probe)
					|| EnchantmentNamer.canonical(EnchantmentNamer.sanitizeAlias(displayName)).equals(probe)) {
				return "trademaster.alias.duplicate_name";
			}
			return null;
		}

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
			boolean aliased = TradeMasterConfig.INSTANCE.customEnchantNames.containsKey(this.id);
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4,
					aliased ? 0x3F00FF00 : 0x1AC7C0C0);
			String name = EnchantmentNamer.demandDisplayName(this.id);
			context.drawString(font, McText.literal(name), this.x + 4, this.y + 4, 0xFFFFFFFF);
			if (this.conflictKey != null) {
				// refuse reason instead of the id: the row is red, nothing was stored
				context.drawString(font, McText.translatable(this.conflictKey), this.x + 4, this.y + 12, 0xFFFF5555);
			} else {
				context.drawString(font, McText.literal(this.id.toString()), this.x + 4, this.y + 12, 0xFF808080);
			}

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
			if (button != 0) return false;
			if (McInput.mouseClicked(this.field, mouseX, mouseY, button, false)) {
				this.field.setFocused(true);
				return true;
			}
			return false;
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
	}
}
