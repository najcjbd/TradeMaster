package dev.trademaster.gui;

//? if >=26.1 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;*/
//?}
import dev.trademaster.compat.McList;
import dev.trademaster.compat.McClient;
import dev.trademaster.compat.McGraphics;
import dev.trademaster.compat.McInput;
import dev.trademaster.compat.McButton;
import dev.trademaster.compat.McText;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RerollController;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
//? if >=1.20 {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.client.gui.components.Button;
//? if >=1.20 {
import net.minecraft.client.gui.components.Renderable;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

public class TradeMasterScreen extends Screen {

	public enum Tab { ENCHANTMENTS, SETTINGS, KEYBINDS, ALIASES, OFFERS, VILLAGERS }

	private final @Nullable Screen parent;
	private Tab tab = Tab.ENCHANTMENTS;
	private boolean pendingRebuild = false;
	/** Scroll offset to restore after a settings-list rebuild (removing a chest
	 * row rebuilds the whole list and would otherwise jump back to the top). */
	private double pendingScroll = -1;
	/** Same for the enchantments list on resize rebuilds (R23-09). */
	private double pendingEnchantScroll = -1;
	/** Same for the offers list. */
	private double pendingOfferScroll = -1;
	/** Same for the aliases list (no scroll restore would jump it back to the top). */
	private double pendingAliasScroll = -1;
	/** Same for the villagers list. */
	private double pendingVillagersScroll = -1;

	private EnchantmentsListWidget enchantmentsList;
	private SettingsListWidget settingsList;
	private KeybindsListWidget keybindsList;
	private AliasListWidget aliasesList;
	private OffersListWidget offersList;
	private VillagersListWidget villagersList;

	public TradeMasterScreen(@Nullable Screen parent) {
		super(McText.literal("Trade Master"));
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
		if (this.aliasesList != null) this.aliasesList.flushEdits();
		if (this.offersList != null) this.offersList.flushEdits();
		if (this.villagersList != null) this.villagersList.flushEdits();
		// same rebuild resets scroll to the top: remember where the user was.
		// Tab switches never capture here — the tab is already flipped by then
		if (this.tab == Tab.SETTINGS && this.settingsList != null) {
			this.pendingScroll = McList.scrollAmount(this.settingsList);
		}
		if (this.tab == Tab.ENCHANTMENTS && this.enchantmentsList != null) {
			this.pendingEnchantScroll = McList.scrollAmount(this.enchantmentsList);
		}
		if (this.tab == Tab.ALIASES && this.aliasesList != null) {
			this.pendingAliasScroll = McList.scrollAmount(this.aliasesList);
		}
		if (this.tab == Tab.OFFERS && this.offersList != null) {
			this.pendingOfferScroll = McList.scrollAmount(this.offersList);
		}
		if (this.tab == Tab.VILLAGERS && this.villagersList != null) {
			this.pendingVillagersScroll = McList.scrollAmount(this.villagersList);
		}
		// six tabs must still fit a narrow (Android/FCL) screen: shrink them rather
		// than let the last one clip off the right edge. No floor at all — a floor of
		// ANY size makes the row wider than the screen once the width drops below it,
		// and an unreachable tab is worse than a cramped one
		int tabWidth = Math.min(80, Math.max(1, (this.width - 10) / 6 - 2));
		int tabsWidth = (tabWidth + 2) * 6 - 2;
		// narrow (Android/FCL) screens: a centered row wider than the screen
		// would clip at both edges — clamp it fully on-screen instead
		int x = Math.max(2, Math.min(this.width / 2 - tabsWidth / 2, this.width - tabsWidth - 2));
		addTabButton(x, Tab.ENCHANTMENTS, "trademaster.tab.enchantments", tabWidth);
		addTabButton(x + tabWidth + 2, Tab.SETTINGS, "trademaster.tab.settings", tabWidth);
		addTabButton(x + (tabWidth + 2) * 2, Tab.KEYBINDS, "trademaster.tab.keybinds", tabWidth);
		addTabButton(x + (tabWidth + 2) * 3, Tab.ALIASES, "trademaster.tab.aliases", tabWidth);
		addTabButton(x + (tabWidth + 2) * 4, Tab.OFFERS, "trademaster.tab.offers", tabWidth);
		addTabButton(x + (tabWidth + 2) * 5, Tab.VILLAGERS, "trademaster.tab.villagers", tabWidth);

		// the row must fit too: scale ALL THREE buttons on narrow screens, and again
		// with NO floor — a 34px floor still overflowed below ~112px logical width
		int saveWidth = Math.max(1, Math.min(140, this.width - 152));
		int actionWidth = Math.max(1, Math.min(70, (this.width - saveWidth - 10) / 2));
		int buttonsWidth = saveWidth + 6 + actionWidth + 2 + actionWidth;
		int left = Math.max(2, Math.min(this.width / 2 - buttonsWidth / 2, this.width - buttonsWidth - 2));

		this.addRenderableWidget(McButton.builder(
						McText.translatable("trademaster.button.start").withStyle(ChatFormatting.GREEN),
						button -> {
							// the start reads the config immediately: commit in-flight
							// field edits first or the typed price/level is ignored
							if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
							// and the settings drafts: a deferred clear (region/grindstone
							// coordinate) would otherwise land AFTER start() snapshotted
							// the config and stop the run on the very next tick
							if (this.settingsList != null) this.settingsList.flushEdits();
							if (this.aliasesList != null) this.aliasesList.flushEdits();
		if (this.offersList != null) this.offersList.flushEdits();
		if (this.villagersList != null) this.villagersList.flushEdits();
							// a refused start (missing coordinates, all targets done...)
							// must keep this screen open: it is the only place the
							// user can fix the reported problem
							if (RerollController.start() && this.minecraft != null) {
								McClient.setScreen(this.minecraft, this.parent);
							}
						})
				.bounds(left + saveWidth + 6, this.height - 26, actionWidth, 20)
				.build());

		this.addRenderableWidget(McButton.builder(
						McText.translatable("trademaster.button.stop").withStyle(ChatFormatting.RED),
						button -> {
							RerollController.stopManual();
								if (this.minecraft != null) McClient.setScreen(this.minecraft, this.parent);
						})
				.bounds(left + saveWidth + 8 + actionWidth, this.height - 26, actionWidth, 20)
				.build());

		this.addRenderableWidget(McButton.builder(
						McText.translatable("trademaster.button.save"),
						button -> {
							// commit in-flight settings drafts first: the rebuild discards
							// the widgets, and saving without them wrote the OLD value
							if (this.settingsList != null) this.settingsList.flushEdits();
							if (this.aliasesList != null) this.aliasesList.flushEdits();
		if (this.offersList != null) this.offersList.flushEdits();
		if (this.villagersList != null) this.villagersList.flushEdits();
							TradeMasterConfig.INSTANCE.save();
								if (this.minecraft != null) McClient.setScreen(this.minecraft, this.parent);
						})
				.bounds(left, this.height - 26, saveWidth, 20)
				.build());

		rebuildBody();
		super.init();
	}

	private void addTabButton(int x, Tab tab, String key, int width) {
		Button button = McButton.builder(McText.translatable(key), b -> {
			// the rebuild below discards the entry widgets; commit their
			// in-flight edits first or the keystrokes are lost (R20-2)
			if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
			if (this.settingsList != null) this.settingsList.flushEdits();
			if (this.aliasesList != null) this.aliasesList.flushEdits();
		if (this.offersList != null) this.offersList.flushEdits();
		if (this.villagersList != null) this.villagersList.flushEdits();
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
			//? if >=1.19 {
			this.rebuildWidgets();
			//?} else {
			/*this.init(this.minecraft, this.width, this.height);*/
			//?}
		}
	}

	private void requestRebuild() {
		// the rebuild replaces the list widget: remember where the user scrolled
		if (this.settingsList != null) this.pendingScroll = McList.scrollAmount(this.settingsList);
		if (this.offersList != null) this.pendingOfferScroll = McList.scrollAmount(this.offersList);
		if (this.villagersList != null) this.pendingVillagersScroll = McList.scrollAmount(this.villagersList);
		this.pendingRebuild = true;
	}

	private void rebuildBody() {
		this.enchantmentsList = null;
		this.settingsList = null;
		this.keybindsList = null;
		this.aliasesList = null;
		this.offersList = null;
		this.villagersList = null;
		if (this.tab != Tab.SETTINGS) this.pendingScroll = -1;
		if (this.tab != Tab.ENCHANTMENTS) this.pendingEnchantScroll = -1;
		if (this.tab != Tab.OFFERS) this.pendingOfferScroll = -1;
		if (this.tab != Tab.VILLAGERS) this.pendingVillagersScroll = -1;
		if (this.tab != Tab.ALIASES) this.pendingAliasScroll = -1;
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
			case OFFERS -> {
				// the list starts below the air-item header (58) and must still end
				// above the button row: -94 keeps the same bottom edge as the other tabs
				this.offersList = new OffersListWidget(this.minecraft,
						this.width - 20, this.height - 94, 58, 22, this::requestRebuild);
				if (this.pendingOfferScroll >= 0) {
					this.offersList.setScrollAmount(this.pendingOfferScroll);
				}
				this.pendingOfferScroll = -1;
				this.addRenderableWidget(this.offersList);
			}
			case VILLAGERS -> {
				this.villagersList = new VillagersListWidget(this.minecraft,
						this.width - 20, this.height - 82, 46, 22);
				if (this.pendingVillagersScroll >= 0) {
					this.villagersList.setScrollAmount(this.pendingVillagersScroll);
				}
				this.pendingVillagersScroll = -1;
				this.addRenderableWidget(this.villagersList);
			}
			case KEYBINDS -> {
				this.keybindsList = new KeybindsListWidget(this.minecraft,
						this.width - 20, this.height - 82, 46, 22);
				this.addRenderableWidget(this.keybindsList);
			}
			case ALIASES -> {
				this.aliasesList = new AliasListWidget(this.minecraft,
						this.width - 20, this.height - 82, 46, 22);
				if (this.pendingAliasScroll >= 0) {
					this.aliasesList.setScrollAmount(this.pendingAliasScroll);
				}
				this.pendingAliasScroll = -1;
				this.addRenderableWidget(this.aliasesList);
			}
		}
	}

	// 1.19.2 threads a PoseStack instead of GuiGraphics and exposes Widget instead of
	// Renderable; McGraphics keeps the child-render and text calls identical.
	//? if >=1.20 {
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		// 1.21.8+ and 26.x draw the background BEFORE render/ extractRenderState; on
		// 1.18.2..1.21.5 this override REPLACES the vanilla render that would draw
		// it, so the dim/panorama must be painted here or the screen floats over the
		// world undimmed.
		//? if >=1.21 && <1.21.8 {
		this.renderBackground(context, mouseX, mouseY, delta);
		//?} elif >=1.20 && <1.21 {
		/*this.renderBackground(context);*/
		//?}
		for (Renderable drawable : this.renderables) {
			drawable.render(context, mouseX, mouseY, delta);
		}
		Component status = RerollController.isRunning()
				? McText.literal(RerollController.statusText()).withStyle(ChatFormatting.GREEN)
				: McText.translatable("trademaster.status.idle").withStyle(ChatFormatting.GRAY);
		// above the button row: at height-22 the text overlapped the buttons
		// on narrow (Android) screens where the row is clamped to the left edge
		context.drawString(Minecraft.getInstance().font, status, 10, this.height - 35, 0xFFFFFFFF);
	}
	//?} else {
	/*@Override
	public void render(com.mojang.blaze3d.vertex.PoseStack context, int mouseX, int mouseY, float delta) {
		// 1.18/1.19's Screen.render does not draw the background for us
		this.renderBackground(context);
		McGraphics graphics = new McGraphics(context);
		for (var drawable : this.renderables) {
			graphics.renderWidget(drawable, mouseX, mouseY, delta);
		}
		Component status = RerollController.isRunning()
				? McText.literal(RerollController.statusText()).withStyle(ChatFormatting.GREEN)
				: McText.translatable("trademaster.status.idle").withStyle(ChatFormatting.GRAY);
		graphics.drawString(Minecraft.getInstance().font, status, 10, this.height - 35, 0xFFFFFFFF);
	}*/
	//?}

	// 1.20.1's Screen.renderBackground(GuiGraphics) already draws the panorama/overlay plus
	// the menu background; the 4-arg form and renderPanorama/renderMenuBackground exist from 1.21.
	//? if >=1.21 {
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		if (this.minecraft != null && this.minecraft.level == null) {
			this.renderPanorama(context, deltaTicks);
		} else {
			context.fill(0, 0, this.width, this.height, 0x90202020);
		}
		this.renderMenuBackground(context);
	}
	//?}

	//? if >=26.1 {
	/*@Override
	public boolean keyPressed(KeyEvent input) {
		if (super.keyPressed(input)) return true;
		if (this.enchantmentsList != null && this.enchantmentsList.keyPressed(input)) return true;
		if (this.settingsList != null && this.settingsList.keyPressed(input)) return true;
		if (this.aliasesList != null && this.aliasesList.keyPressed(input)) return true;
		if (this.offersList != null && this.offersList.keyPressed(input)) return true;
		if (this.villagersList != null && this.villagersList.keyPressed(input)) return true;
		return false;
	}*/
	//?} else {
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
		if (this.enchantmentsList != null && this.enchantmentsList.keyPressed(keyCode, scanCode, modifiers)) return true;
		if (this.settingsList != null && this.settingsList.keyPressed(keyCode, scanCode, modifiers)) return true;
		if (this.aliasesList != null && this.aliasesList.keyPressed(keyCode, scanCode, modifiers)) return true;
		if (this.offersList != null && this.offersList.keyPressed(keyCode, scanCode, modifiers)) return true;
		if (this.villagersList != null && this.villagersList.keyPressed(keyCode, scanCode, modifiers)) return true;
		return false;
	}
	//?}

	//? if >=26.1 {
	/*@Override
	public boolean charTyped(CharacterEvent input) {
		if (this.enchantmentsList != null && this.enchantmentsList.charTyped(input)) return true;
		if (this.settingsList != null && this.settingsList.charTyped(input)) return true;
		if (this.aliasesList != null && this.aliasesList.charTyped(input)) return true;
		if (this.offersList != null && this.offersList.charTyped(input)) return true;
		if (this.villagersList != null && this.villagersList.charTyped(input)) return true;
		return super.charTyped(input);
	}*/
	//?} else {
	@Override
	public boolean charTyped(char c, int modifiers) {
		if (this.enchantmentsList != null && McInput.charTyped(this.enchantmentsList, c, modifiers)) return true;
		if (this.settingsList != null && McInput.charTyped(this.settingsList, c, modifiers)) return true;
		if (this.aliasesList != null && McInput.charTyped(this.aliasesList, c, modifiers)) return true;
		if (this.offersList != null && McInput.charTyped(this.offersList, c, modifiers)) return true;
		if (this.villagersList != null && McInput.charTyped(this.villagersList, c, modifiers)) return true;
		return super.charTyped(c, modifiers);
	}
	//?}

	//? if >=26.1 {
	/*@Override
	public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
		return handleMouseClicked(click.x(), click.y(), click.button(), doubled) || super.mouseClicked(click, doubled);
	}*/
	//?} else {
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return handleMouseClicked(mouseX, mouseY, button, false) || super.mouseClicked(mouseX, mouseY, button);
	}
	//?}

	private boolean handleMouseClicked(double mouseX, double mouseY, int button, boolean doubled) {
		if (this.enchantmentsList != null) {
			EnchantmentEntry hit = this.enchantmentsList.entryAt(mouseX, mouseY);
			for (var entry : this.enchantmentsList.children()) {
				// any click ends a scroll-deferral, so the draft a wheel only blurred
				// commits on the next render (a background/Reset/Select-All click too)
				entry.clearDeferCommit();
				if (entry == hit) {
					boolean price = McInput.mouseClicked(entry.maxPriceField, mouseX, mouseY, button, doubled);
					boolean level = McInput.mouseClicked(entry.levelField, mouseX, mouseY, button, doubled);
					entry.maxPriceField.setFocused(price);
					entry.levelField.setFocused(level);
				} else {
					entry.maxPriceField.setFocused(false);
					entry.levelField.setFocused(false);
				}
			}
		}
		return false;
	}

	//? if >=1.21 {
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		return handleMouseScrolled(mouseX, mouseY, verticalAmount)
				|| super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}
	//?} else {
	/*@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double verticalAmount) {
		return handleMouseScrolled(mouseX, mouseY, verticalAmount)
				|| super.mouseScrolled(mouseX, mouseY, verticalAmount);
	}*/
	//?}

	private boolean handleMouseScrolled(double mouseX, double mouseY, double verticalAmount) {
		if (this.enchantmentsList != null) {
			EnchantmentEntry hit = this.enchantmentsList.entryAt(mouseX, mouseY);
			if (hit != null && hit.handleMouseScrolled(mouseX, mouseY, verticalAmount)) {
				return true;
			}
		}
		return false;
	}

	/** Commits in-flight settings drafts. RerollController.start() calls this so the
	 * command and hotkey start paths read the same config as the Start button. */
	public void flushSettingsDrafts() {
		if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
		if (this.settingsList != null) this.settingsList.flushEdits();
		if (this.aliasesList != null) this.aliasesList.flushEdits();
		if (this.offersList != null) this.offersList.flushEdits();
		if (this.villagersList != null) this.villagersList.flushEdits();
	}

	@Override
	public void removed() {
		// removed() runs on every exit path (ESC, start/stop/save buttons);
		// onClose() alone would skip the button paths
		if (this.enchantmentsList != null) this.enchantmentsList.flushEdits();
		if (this.settingsList != null) this.settingsList.flushEdits();
		if (this.aliasesList != null) this.aliasesList.flushEdits();
		if (this.offersList != null) this.offersList.flushEdits();
		if (this.villagersList != null) this.villagersList.flushEdits();
		TradeMasterConfig.INSTANCE.save();
		super.removed();
	}
}
