package dev.trademaster.gui;

import dev.trademaster.config.TradeMasterConfig;
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
				.sorted(Comparator.comparing(Identifier::toString))
				.forEach(id -> this.addEntry(new AliasRow(id)));
	}

	@Override
	protected void extractListBackground(GuiGraphicsExtractor context) {
		this.setSelected(null);
		context.fill(5, 27, this.width - 14, 44, 0xAFC7C0C0);
		Font font = Minecraft.getInstance().font;
		context.text(font, Component.translatable("trademaster.column.enchantment"), 9, 32, 0xFF202020);
		context.text(font, Component.translatable("trademaster.column.alias"), this.width - 122, 32, 0xFF202020);
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

	private @Nullable AliasRow editing() {
		for (AliasRow row : this.children()) {
			if (row.field.isFocused()) return row;
		}
		return null;
	}

	/** Commits every row's in-flight alias draft: the screen close and tab switch
	 * discard the widgets without another focus-loss pass. */
	public void flushEdits() {
		for (AliasRow row : this.children()) row.applyDraft();
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
		// a focused field scrolled off-screen would keep swallowing keystrokes
		for (AliasRow row : this.children()) {
			if (row.field.isFocused()) {
				row.applyDraft();
				row.field.setFocused(false);
			}
		}
	}

	public static class AliasRow extends AbstractSelectionList.Entry<AliasRow> {

		private final Identifier id;
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

		public AliasRow(Identifier id) {
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

		/** Commits whichever draft is pending: empty removes the alias, otherwise the
		 * trimmed text replaces it — unless it collides with another enchantment's
		 * alias or display name, in which case the row shows the reason and nothing is
		 * written (the named-villager deduction matches by name, so a duplicate would
		 * make both sides unresolvable). A no-op when nothing changed. */
		void applyDraft() {
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			if (this.pendingClear) {
				this.dirty = false;
				// setValue fires the responder, which would set pendingClear again:
				// clear the flag AFTER the text so the row does not stay "pending"
				this.field.setValue("");
				this.pendingClear = false;
				if (config.customEnchantNames.remove(this.id) != null) config.save();
				return;
			}
			if (!this.dirty) return;
			String value = this.field.getValue().trim();
			if (value.isEmpty()) return;
			String conflict = conflictKey(this.id, value);
			if (conflict != null) {
				// keep dirty: the collision may be resolved by editing ANOTHER row, and
				// this draft must then be re-validated and committed (clearing dirty
				// here left the row showing a red reason forever, never saving)
				this.conflictKey = conflict;
				return;
			}
			this.dirty = false;
			if (!value.equals(config.customEnchantNames.get(this.id))) {
				config.customEnchantNames.put(this.id, value);
				config.save();
			}
			// re-sync: show exactly what was stored (trimmed) instead of the raw draft
			if (!this.field.getValue().equals(value)) this.field.setValue(value);
		}

		/** @return a lang key explaining why {@code value} is refused, or null when it
		 * is usable. Mirrors {@code TradeMatcher.buildDisplayNameIndex}: a name must not
		 * collide with another enchantment's alias, its display name, its registry
		 * path, or a name recorded in nameHistory — the deduction matches villagers by
		 * name, and a collision drops BOTH sides from the index. Comparisons use the
		 * same sanitizer the naming path uses. */
		private static @Nullable String conflictKey(Identifier id, String value) {
			TradeMasterConfig config = TradeMasterConfig.INSTANCE;
			String probe = EnchantmentNamer.sanitizeAlias(value);
			for (Map.Entry<Identifier, String> entry : config.customEnchantNames.entrySet()) {
				if (entry.getKey().equals(id)) continue;
				if (EnchantmentNamer.sanitizeAlias(entry.getValue()).equalsIgnoreCase(probe)) {
					return "trademaster.alias.duplicate_alias";
				}
			}
			for (Identifier other : config.enchantments.keySet()) {
				if (other.equals(id)) continue;
				String otherKey = "enchantment." + other.getNamespace() + "." + other.getPath();
				if (other.getPath().equalsIgnoreCase(probe)
						|| EnchantmentNamer.sanitizeAlias(EnchantmentNamer.displayName(other, otherKey))
								.equalsIgnoreCase(probe)) {
					return "trademaster.alias.duplicate_name";
				}
			}
			for (Map.Entry<String, Identifier> entry : config.nameHistory.entrySet()) {
				if (entry.getValue().equals(id)) continue;
				if (EnchantmentNamer.sanitizeAlias(entry.getKey()).equalsIgnoreCase(probe)) {
					return "trademaster.alias.duplicate_name";
				}
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
			String name = EnchantmentNamer.displayName(this.id, this.translationKey);
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
