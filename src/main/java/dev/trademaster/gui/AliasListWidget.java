package dev.trademaster.gui;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.exec.EnchantmentNamer;
import dev.trademaster.matching.TradeMatcher;
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

	public AliasListWidget(Minecraft client, int width, int height, int top, int itemHeight) {
		super(client, width, height, top, itemHeight);
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

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		// clamp: on a narrow list these fixed offsets fall left of the 5px margin
		// (the enchantments page already guards its own fill the same way)
		context.fill(5, 27, Math.max(8, this.width - 14), 44, 0xAFC7C0C0);
		Font font = Minecraft.getInstance().font;
		context.text(font, Component.translatable("trademaster.column.enchantment"), 9, 32, 0xFF202020);
		context.text(font, Component.translatable("trademaster.column.alias"),
				Math.max(8, this.width - 122), 32, 0xFF202020);
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
		// the scrollbar sits on the widget's right edge, outside the bounds the
		// Screen uses to route mouse events: claim it explicitly (same as the
		// enchantments list)
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

	@Override
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

	/** IME preedit for the row being edited (see EnchantmentsListWidget). */
	@Override
	public boolean preeditUpdated(net.minecraft.client.input.PreeditEvent input) {
		AliasRow editing = this.editing();
		return editing != null && editing.field.preeditUpdated(input);
	}

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

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		this.suppressFocusClear = true;
		try {
			// Commit + unfocus every row the click did NOT land in. Without this two
			// fields can hold focus at once, and editing() would route the keystrokes
			// to whichever row comes first — i.e. the wrong enchantment's alias.
			for (AliasRow row : this.children()) {
				if (!row.field.isMouseOver(click.x(), click.y())) {
					row.field.setFocused(false);
					row.applyDraft();
				}
			}
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
			this.field = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, Component.literal("alias"));
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

		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
			this.x = this.getX();
			this.y = this.getY();
			this.entryWidth = this.getWidth();
			this.entryHeight = this.getHeight();

			Font font = Minecraft.getInstance().font;
			boolean aliased = TradeMasterConfig.INSTANCE.customEnchantNames.containsKey(this.id);
			context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4,
					aliased ? 0x3F00FF00 : 0x1AC7C0C0);
			String name = EnchantmentNamer.demandDisplayName(this.id);
			context.text(font, Component.literal(name), this.x + 4, this.y + 4, 0xFFFFFFFF);
			if (this.conflictKey != null) {
				// refuse reason instead of the id: the row is red, nothing was stored
				context.text(font, Component.translatable(this.conflictKey), this.x + 4, this.y + 12, 0xFFFF5555);
			} else {
				context.text(font, Component.literal(this.id.toString()), this.x + 4, this.y + 12, 0xFF808080);
			}

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

		@Override
		public boolean mouseReleased(MouseButtonEvent click) {
			return this.field.mouseReleased(click);
		}
	}
}
