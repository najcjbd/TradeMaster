package dev.trademaster.gui;

import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.exec.EnchantmentNamer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.enchantment.Enchantment;
import org.joml.Matrix3x2fStack;

public class EnchantmentEntry extends AbstractSelectionList.Entry<EnchantmentEntry> {

	public final Identifier id;
	public final Enchantment enchantment;
	public final String translationKey;
	public final EditBox maxPriceField;
	public final EditBox levelField;
	/** Keystroke flags for B4: parsed, clamped and persisted once on focus loss. */
	private boolean priceDirty = false;
	private boolean levelDirty = false;
	private int x, y, entryWidth, entryHeight;

	public EnchantmentEntry(Identifier id) {
		this.id = id;
		Registry<Enchantment> registry = enchantmentRegistry();
		this.enchantment = registry == null ? null : registry.getValue(id);
		this.translationKey = "enchantment." + id.getNamespace() + "." + id.getPath();

		EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(id);
		int level = target == null ? editableMaxLevel() : target.level();
		int maxPrice = target == null ? 64 : target.maxPrice();

		this.maxPriceField = new EditBox(Minecraft.getInstance().font, 0, 0, 24, 14, Component.literal("price"));
		this.maxPriceField.setMaxLength(2);
		this.maxPriceField.setValue(String.valueOf(maxPrice));
		// keystrokes only mark the field dirty; commit happens once on focus
		// loss in extractContent, instead of saving the config per keystroke
		this.maxPriceField.setResponder(text -> this.priceDirty = true);

		this.levelField = new EditBox(Minecraft.getInstance().font, 0, 0, 14, 14, Component.literal("level"));
		// 2 chars: stored levels >= 10 (datapack/mod enchantments) must display
		// fully, or the focus-loss reconciliation loops with a save every frame
		this.levelField.setMaxLength(2);
		this.levelField.setValue(String.valueOf(level));
		this.levelField.setResponder(text -> this.levelDirty = true);
	}

	private static Registry<Enchantment> enchantmentRegistry() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return null;
		return client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
	}

	private int maxLevel() {
		return this.enchantment == null ? 1 : this.enchantment.getMaxLevel();
	}

	/** The level field holds 2 digits; anything higher truncates the display and
	 * spins the focus-loss reconciliation forever, so bound stored values at 99. */
	private int editableMaxLevel() {
		return Math.min(maxLevel(), 99);
	}

	private boolean enabled() {
		EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(this.id);
		return target != null && target.enabled();
	}

	public void setEnabled(boolean enabled) {
		EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(this.id);
		if (target == null) {
			if (this.enchantment == null) return; // no registry: cannot build a sane default
			target = new EnchantmentTarget(this.id, false, editableMaxLevel(), 64);
		}
		if (!enabled) {
			// hidden fields must not keep focus: charTyped only checks isFocused,
			// so a focused invisible field would silently accept keystrokes
			this.maxPriceField.setFocused(false);
			this.levelField.setFocused(false);
		}
		TradeMasterConfig.INSTANCE.enchantments.put(this.id, target.withEnabled(enabled));
		TradeMasterConfig.INSTANCE.save();
	}

	public void resetToDefault() {
		if (this.enchantment == null) return; // registry unavailable: reset would store level 1
		TradeMasterConfig.INSTANCE.enchantments.put(this.id,
				new EnchantmentTarget(this.id, false, editableMaxLevel(), 64));
		this.maxPriceField.setValue("64");
		this.levelField.setValue(String.valueOf(editableMaxLevel()));
		TradeMasterConfig.INSTANCE.save();
	}

	private static Integer parseIntSafe(String value) {
		if (value == null || value.isEmpty()) return null;
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private int minPrice(int level) {
		return TradeMasterConfig.minPrice(level, this.id);
	}

	/** Commits in-flight field edits to the config (R20-2). extractContent commits
	 * on focus loss, but the close paths (ESC, Save/Start/Stop buttons, tab
	 * switch) tear the widgets down without another render pass — call this
	 * first or the keystrokes are silently discarded. */
	public void flushEdits() {
		if (!this.priceDirty && !this.levelDirty) return;
		EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(this.id);
		if (target == null) {
			this.priceDirty = false;
			this.levelDirty = false;
			return;
		}
		if (this.levelDirty) {
			target = commitLevel(target);
			this.levelDirty = false;
		}
		if (this.priceDirty) {
			commitPrice(target);
			this.priceDirty = false;
		}
	}

	/** Drops both fields' focus when the entry scrolls off-screen: an invisible
	 * focused field would keep swallowing keyboard input. In-flight text is not
	 * lost — flushEdits still commits it at close. */
	void clearFieldFocus() {
		this.levelField.setFocused(false);
		this.maxPriceField.setFocused(false);
	}

	private EnchantmentTarget commitLevel(EnchantmentTarget target) {
		Integer levelValue = parseIntSafe(this.levelField.getValue());
		// registry unavailable (e.g. main menu): committing would silently
		// downgrade a stored level >= maxLevel() and persist it
		if (levelValue != null && this.enchantment != null) {
			int newLevel = Mth.clamp(levelValue, 1, editableMaxLevel());
			// raising the level raises the vanilla minimum price: lift the
			// stored price with it, otherwise the target can never match
			int newPrice = Math.min(64, Math.max(target.maxPrice(), minPrice(newLevel)));
			if (newLevel != target.level() || newPrice != target.maxPrice()) {
				target = target.withLevel(newLevel).withMaxPrice(newPrice);
				TradeMasterConfig.INSTANCE.enchantments.put(this.id, target);
				TradeMasterConfig.INSTANCE.save();
			}
		}
		return target;
	}

	private EnchantmentTarget commitPrice(EnchantmentTarget target) {
		Integer price = parseIntSafe(this.maxPriceField.getValue());
		if (price != null) {
			// minPrice() can exceed 64 for high-level treasures; Mth.clamp
			// with min>max would then return min and store >64
			int clamped = Mth.clamp(price, Math.min(minPrice(target.level()), 64), 64);
			if (clamped != target.maxPrice()) {
				target = target.withMaxPrice(clamped);
				TradeMasterConfig.INSTANCE.enchantments.put(this.id, target);
				TradeMasterConfig.INSTANCE.save();
			}
		}
		return target;
	}

	@Override
	public void extractContent(GuiGraphicsExtractor context, int mouseX, int mouseY, boolean hovered, float deltaTicks) {
		Matrix3x2fStack matrices = context.pose();
		this.x = this.getX();
		this.y = this.getY();
		this.entryWidth = this.getWidth();
		this.entryHeight = this.getHeight();

		Font font = Minecraft.getInstance().font;
		boolean enabled = enabled();

		EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(this.id);
		if (target == null) {
			// registry entry vanished and nothing is stored: keep the row readable
			// instead of a blank strip that silently swallows every interaction
			if (this.enchantment == null) {
				context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, 0x1AC7C0C0);
				context.text(font, Component.literal("[ ] " + EnchantmentNamer.displayName(this.id, this.translationKey)
								+ " (" + Component.translatable("trademaster.settings.enchantment_unavailable").getString() + ")"),
						this.x + 4, this.y + 4, 0xFF808080);
				context.text(font, Component.literal(this.id.toString()), this.x + 4, this.y + 12, 0xFF808080);
			}
			return;
		}

		// Commit once on focus loss (B4): while focused, keystrokes only edit the
		// text. Not dirty means no keystroke since the last pass, so the config is
		// authoritative (e.g. a mouse scroll changed it under a focused field).
		if (!this.levelField.isFocused()) {
			if (this.levelDirty) target = commitLevel(target);
			if (!this.levelField.getValue().equals(String.valueOf(target.level()))) {
				this.levelField.setValue(String.valueOf(target.level()));
			}
			this.levelDirty = false;
		}
		if (!this.maxPriceField.isFocused()) {
			// reassign: the commit may clamp the price, and the stale target
			// would make the field reset to the pre-clamp value for one frame
			if (this.priceDirty) target = commitPrice(target);
			if (!this.maxPriceField.getValue().equals(String.valueOf(target.maxPrice()))) {
				this.maxPriceField.setValue(String.valueOf(target.maxPrice()));
			}
			this.priceDirty = false;
		}

		int color = enabled ? 0x3F00FF00 : 0x1AC7C0C0;
		context.fill(this.x, this.y, this.x + this.entryWidth, this.y + this.entryHeight - 4, color);

		String name = EnchantmentNamer.displayName(this.id, this.translationKey);
		context.text(font, Component.literal((enabled ? "[x] " : "[ ] ") + name), this.x + 4, this.y + 4, 0xFFFFFFFF);
		context.text(font, Component.literal(this.id.toString()), this.x + 4, this.y + 12, 0xFF808080);

		int right = this.x + this.entryWidth;
		String maxLevelText = "Max " + EnchantmentNamer.romanNumerals(maxLevel());
		int minPrice = minPrice(target.level());
		String minPriceText = "Min $" + minPrice;
		// Anchored to the field block instead of the old fixed right-200/-140: those
		// spilled past the row's left edge on a narrow (Android/FCL) screen. Drawn
		// only when they clear the name, so the two never overlap.
		int fieldsLeft = right - 78;
		int minPriceX = fieldsLeft - font.width(minPriceText);
		int maxLevelX = minPriceX - 6 - font.width(maxLevelText);
		int nameRight = this.x + 4 + font.width(Component.literal((enabled ? "[x] " : "[ ] ") + name));
		if (maxLevelX >= nameRight + 4) {
			context.text(font, Component.literal(maxLevelText), maxLevelX, this.y + 6, 0xFFC0C0C0);
			// min price above the 64 emerald cap: the target can never match
			context.text(font, Component.literal(minPriceText), minPriceX, this.y + 6,
					minPrice > 64 ? 0xFFFF5555 : 0xFFC0C0C0);
		}

		this.maxPriceField.setVisible(enabled);
		this.levelField.setVisible(enabled);
		if (enabled) {
			matrices.pushMatrix();
			int maxPriceX = right - 28;
			int levelX = maxPriceX - 34;
			context.text(font, Component.literal("L:"), levelX - 10, this.y + 4, 0xFFFFFFFF);
			context.text(font, Component.literal("$:"), maxPriceX - 10, this.y + 4, 0xFFFFFFFF);
			this.levelField.setX(levelX);
			this.levelField.setY(this.y + 1);
			this.levelField.extractRenderState(context, mouseX, mouseY, deltaTicks);
			this.maxPriceField.setX(maxPriceX);
			this.maxPriceField.setY(this.y + 1);
			this.maxPriceField.extractRenderState(context, mouseX, mouseY, deltaTicks);
			matrices.popMatrix();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();
		boolean inRowY = mouseY > this.y && mouseY < this.y + this.entryHeight - 4;
		int right = this.x + this.entryWidth;
		// touch halo around the tiny level/price boxes (14x14 / 24x14): a tap
		// drifting a few pixels used to fall into the toggle zone 2px left of
		// the level box and silently disable the enchantment. Halos butt
		// against each other and the toggle zone so no pixel is a dead zone.
		if (inRowY && enabled()) {
			if (mouseX >= right - 66 && mouseX <= right - 44) {
				focusField(this.levelField);
				return true;
			}
			if (mouseX > right - 44 && mouseX <= right) {
				focusField(this.maxPriceField);
				return true;
			}
		}
		// toggle zone ends exactly where the level halo starts: overlapping it
		// let one tap focus the field AND toggle the entry off
		if (mouseX > this.x && mouseX < right - 66 && inRowY) {
			// registry gone, nothing stored: there is nothing to toggle — no sound
			if (this.enchantment == null && TradeMasterConfig.INSTANCE.enchantments.get(this.id) == null) {
				return false;
			}
			setEnabled(!enabled());
			AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
			return true;
		}
		return false;
	}

	private void focusField(EditBox field) {
		this.maxPriceField.setFocused(field == this.maxPriceField);
		this.levelField.setFocused(field == this.levelField);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		this.maxPriceField.mouseMoved(mouseX, mouseY);
		this.levelField.mouseMoved(mouseX, mouseY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent click) {
		return this.maxPriceField.mouseReleased(click) || this.levelField.mouseReleased(click);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent drag, double mouseX, double mouseY) {
		// vanilla delivers the drag to this entry (the list's focused child after
		// the press) but not to the nested field: without forwarding, EditBox's
		// onDrag selection never runs and drag-select is dead
		if (this.maxPriceField.isFocused()) return this.maxPriceField.mouseDragged(drag, mouseX, mouseY);
		if (this.levelField.isFocused()) return this.levelField.mouseDragged(drag, mouseX, mouseY);
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(this.id);
		if (target == null || !target.enabled()) return false;
		if (this.maxPriceField.isMouseOver(mouseX, mouseY)) {
			// a focused field is mid-input: scrolling would silently clobber the
			// value being typed (let the list scroll instead)
			if (this.maxPriceField.isFocused()) return false;
			int price = Mth.clamp((int) (target.maxPrice() + verticalAmount), Math.min(minPrice(target.level()), 64), 64);
			TradeMasterConfig.INSTANCE.enchantments.put(this.id, target.withMaxPrice(price));
			this.maxPriceField.setValue(String.valueOf(price));
			TradeMasterConfig.INSTANCE.save();
			return true;
		} else if (this.levelField.isMouseOver(mouseX, mouseY)) {
			if (this.levelField.isFocused()) return false;
			// no registry entry: the level cannot be bounded — let the list scroll
			// instead of swallowing the event with no visible effect
			if (this.enchantment == null) return false;
			int level = Mth.clamp((int) (target.level() + verticalAmount), 1, editableMaxLevel());
			int price = Math.min(64, Math.max(target.maxPrice(), minPrice(level)));
			TradeMasterConfig.INSTANCE.enchantments.put(this.id, target.withLevel(level).withMaxPrice(price));
			this.levelField.setValue(String.valueOf(level));
			// never clobber partial input: the price field may be focused mid-typing
			if (price != target.maxPrice() && !this.maxPriceField.isFocused()) {
				this.maxPriceField.setValue(String.valueOf(price));
			}
			TradeMasterConfig.INSTANCE.save();
			return true;
		}
		return false;
	}
}
