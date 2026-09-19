package dev.trademaster.gui;

import dev.trademaster.config.MaliLibAdapter;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RerollController;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class TradeMasterScreen extends Screen {

	public enum Tab { ENCHANTMENTS, SETTINGS, KEYBINDS }

	private final @Nullable Screen parent;
	private Tab tab = Tab.ENCHANTMENTS;
	private boolean pendingRebuild = false;
	/** Scroll offset to restore after a settings-list rebuild (removing a chest
	 * row rebuilds the whole list and would otherwise jump back to the top). */
	private double pendingScroll = -1;
	/** Same for the enchantments list on resize rebuilds (R23-09). */
	private double pendingEnchantScroll = -1;

	private EnchantmentsListWidget enchantmentsList;
	private SettingsListWidget settingsList;
	private KeybindsListWidget keybindsList;
	private Button startButton;
	private Button stopButton;

	public TradeMasterScreen(@Nullable Screen parent) {
		super(Component.literal("Trade Master"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// resize (Android rotation, soft keyboard) re-runs init() and rebuildBody
		// discards the entry widgets: commit in-flight edits first or keystrokes
		// typed into a price/level field are silently lost
		if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
		// same for the settings tab: its rows only commit their draft on blur, and
		// the rebuild never blurs them
		if (this.settingsList != null) this.settingsList.flushEdits();
		// same rebuild resets scroll to the top: remember where the user was.
		// Tab switches never capture here — the tab is already flipped by then
		if (this.tab == Tab.SETTINGS && this.settingsList != null) {
			this.pendingScroll = this.settingsList.scrollAmount();
		}
		if (this.tab == Tab.ENCHANTMENTS && this.enchantmentsList != null) {
			this.pendingEnchantScroll = this.enchantmentsList.scrollAmount();
		}
		int tabWidth = 80;
		int tabsWidth = tabWidth * 3 + 4;
		// narrow (Android/FCL) screens: a centered row wider than the screen
		// would clip at both edges — clamp it fully on-screen instead
		int x = Math.max(2, Math.min(this.width / 2 - tabsWidth / 2, this.width - tabsWidth - 2));
		addTabButton(x, Tab.ENCHANTMENTS, "trademaster.tab.enchantments", tabWidth);
		addTabButton(x + tabWidth + 2, Tab.SETTINGS, "trademaster.tab.settings", tabWidth);
		addTabButton(x + (tabWidth + 2) * 2, Tab.KEYBINDS, "trademaster.tab.keybinds", tabWidth);

		int buttonsWidth = 288; // save(140) + gap + start(70) + gap + stop(70)
		int left = Math.max(2, Math.min(this.width / 2 - buttonsWidth / 2, this.width - buttonsWidth - 2));

		this.startButton = this.addRenderableWidget(Button.builder(
						Component.translatable("trademaster.button.start").withStyle(ChatFormatting.GREEN),
						button -> {
							// the start reads the config immediately: commit in-flight
							// field edits first or the typed price/level is ignored
							if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
							// and the settings drafts: a deferred clear (region/grindstone
							// coordinate) would otherwise land AFTER start() snapshotted
							// the config and stop the run on the very next tick
							if (this.settingsList != null) this.settingsList.flushEdits();
							// a refused start (missing coordinates, all targets done...)
							// must keep this screen open: it is the only place the
							// user can fix the reported problem
							if (RerollController.start() && this.minecraft != null) {
								this.minecraft.gui.setScreen(this.parent);
							}
						})
				.bounds(left + 146, this.height - 26, 70, 20)
				.build());

		this.stopButton = this.addRenderableWidget(Button.builder(
						Component.translatable("trademaster.button.stop").withStyle(ChatFormatting.RED),
						button -> {
							RerollController.stopManual();
							if (this.minecraft != null) this.minecraft.gui.setScreen(this.parent);
						})
				.bounds(left + 218, this.height - 26, 70, 20)
				.build());

		this.addRenderableWidget(Button.builder(
						Component.translatable("trademaster.button.save"),
						button -> {
							// commit in-flight settings drafts first: the rebuild discards
							// the widgets, and saving without them wrote the OLD value
							if (this.settingsList != null) this.settingsList.flushEdits();
							TradeMasterConfig.INSTANCE.save();
							if (this.minecraft != null) this.minecraft.gui.setScreen(this.parent);
						})
				.bounds(left, this.height - 26, 140, 20)
				.build());

		rebuildBody();
		super.init();
	}

	private void addTabButton(int x, Tab tab, String key, int width) {
		Button button = Button.builder(Component.translatable(key), b -> {
			// the rebuild below discards the entry widgets; commit their
			// in-flight edits first or the keystrokes are lost (R20-2)
			if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
			if (this.settingsList != null) this.settingsList.flushEdits();
			this.tab = tab;
			// defer: rebuilding mid-dispatch would mutate Screen.children while iterating
			this.pendingRebuild = true;
		}).bounds(x, 5, width, 20).build();
		button.active = this.tab != tab;
		this.addRenderableWidget(button);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.pendingRebuild) {
			this.pendingRebuild = false;
			this.rebuildWidgets();
		}
	}

	private void requestRebuild() {
		// the rebuild replaces the list widget: remember where the user scrolled
		if (this.settingsList != null) this.pendingScroll = this.settingsList.scrollAmount();
		this.pendingRebuild = true;
	}

	private void rebuildBody() {
		this.enchantmentsList = null;
		this.settingsList = null;
		this.keybindsList = null;
		if (this.tab != Tab.SETTINGS) this.pendingScroll = -1;
		if (this.tab != Tab.ENCHANTMENTS) this.pendingEnchantScroll = -1;
		switch (this.tab) {
			case ENCHANTMENTS -> {
				this.enchantmentsList = new EnchantmentsListWidget(this.minecraft,
						this.width - 20, this.height - 82, 46, 22);
				if (this.pendingEnchantScroll >= 0) {
					this.enchantmentsList.setScrollAmount(this.pendingEnchantScroll);
				}
				this.pendingEnchantScroll = -1;
				this.addRenderableWidget(this.enchantmentsList);
				this.addRenderableWidget(this.enchantmentsList.resetButton);
				this.addRenderableWidget(this.enchantmentsList.selectAllButton);
			}
			case SETTINGS -> {
				this.settingsList = new SettingsListWidget(this.minecraft,
						this.width - 20, this.height - 82, 46, 22, this::requestRebuild);
				if (this.pendingScroll >= 0) {
					this.settingsList.setScrollAmount(this.pendingScroll);
				}
				this.pendingScroll = -1;
				this.addRenderableWidget(this.settingsList);
			}
			case KEYBINDS -> {
				this.keybindsList = new KeybindsListWidget(this.minecraft,
						this.width - 20, this.height - 82, 46, 22);
				this.addRenderableWidget(this.keybindsList);
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		// no extractBackground call here: the Screen render path already draws it
		// before extractRenderState — calling it again paints the overlay twice
		for (Renderable drawable : this.renderables) {
			drawable.extractRenderState(context, mouseX, mouseY, delta);
		}
		Component status = RerollController.isRunning()
				? Component.literal(RerollController.statusText()).withStyle(ChatFormatting.GREEN)
				: Component.translatable("trademaster.status.idle").withStyle(ChatFormatting.GRAY);
		// above the button row: at height-22 the text overlapped the buttons
		// on narrow (Android) screens where the row is clamped to the left edge
		context.text(Minecraft.getInstance().font, status, 10, this.height - 35, 0xFFFFFFFF);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
		if (this.minecraft != null && this.minecraft.level == null) {
			this.extractPanorama(context, deltaTicks);
		} else {
			context.fill(0, 0, this.width, this.height, 0x90202020);
		}
		this.extractMenuBackground(context);
	}

	@Override
	public boolean keyPressed(KeyEvent input) {
		if (super.keyPressed(input)) return true;
		if (this.enchantmentsList != null && this.enchantmentsList.keyPressed(input)) return true;
		if (this.settingsList != null && this.settingsList.keyPressed(input)) return true;
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent input) {
		if (this.enchantmentsList != null && this.enchantmentsList.charTyped(input)) return true;
		if (this.settingsList != null && this.settingsList.charTyped(input)) return true;
		return super.charTyped(input);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		if (this.enchantmentsList != null) {
			EnchantmentEntry hit = this.enchantmentsList.entryAt(click.x(), click.y());
			for (var entry : this.enchantmentsList.children()) {
				if (entry == hit) {
					boolean price = entry.maxPriceField.mouseClicked(click, doubled);
					boolean level = entry.levelField.mouseClicked(click, doubled);
					entry.maxPriceField.setFocused(price);
					entry.levelField.setFocused(level);
				} else {
					entry.maxPriceField.setFocused(false);
					entry.levelField.setFocused(false);
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (this.enchantmentsList != null) {
			EnchantmentEntry hit = this.enchantmentsList.entryAt(mouseX, mouseY);
			if (hit != null && hit.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) {
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void removed() {
		// removed() runs on every exit path (ESC, start/stop/save buttons);
		// onClose() alone would skip the button paths
		if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
		if (this.settingsList != null) this.settingsList.flushEdits();
		TradeMasterConfig.INSTANCE.save();
		super.removed();
	}
}
