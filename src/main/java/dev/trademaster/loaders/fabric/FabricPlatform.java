//? if fabric {
package dev.trademaster.loaders.fabric;

import dev.trademaster.compat.McClient;
import dev.trademaster.core.RerollController;
import dev.trademaster.gui.PositionPicker;
import dev.trademaster.gui.TradeMasterScreen;
import dev.trademaster.platform.TradeMasterPlatform;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;*/
//?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?}
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Fabric platform services: the mod's keybinds, its own config screen and the config dir.
 *
 * <p>This is the ONLY place a Fabric-only API is referenced. maLiLib is an OPTIONAL,
 * Fabric-only, MODERN-ONLY extra carrying a second config screen and its own hotkeys; its
 * wiring is added by the optional-integration pass, so nothing here names it and this class
 * compiles on every Fabric target.
 */
public class FabricPlatform implements TradeMasterPlatform {

	// The key-mapping category. 26.1 turned it into a KeyMapping.Category; before that it
	// was a plain translation-key string. The branch ACTIVE on the default target (1.21.4)
	// is plain text; the other lives in the block comment Stonecutter uncomments.
	//? if >=26.1 {
	/*private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("trademaster", "main"));*/
	//?} else {
	private static final String CATEGORY = "trademaster.main";
	//?}

	private KeyMapping openMenuKey;
	private KeyMapping startStopKey;
	private KeyMapping pickPositionKey;

	private static KeyMapping register(String name, int code) {
		//? if >=26.1 {
		/*return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, code, CATEGORY));*/
		//?} else {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, code, CATEGORY));
		//?}
	}

	@Override
	public void registerKeybinds() {
		this.openMenuKey = register("key.trade-master.open_menu", InputConstants.KEY_V);
		this.startStopKey = register("trademaster.hotkey.startStop", InputConstants.KEY_R);
		this.pickPositionKey = register("trademaster.hotkey.pickPosition", InputConstants.KEY_P);
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
	}

	private void onClientTick(Minecraft client) {
		RerollController.tick(client);
		while (this.openMenuKey != null && this.openMenuKey.consumeClick()) {
			// the screen's lists assume an in-game world: don't open it from the
			// title/menu screens
			if (client.player == null || client.level == null) continue;
			if (McClient.screen(client) == null) McClient.setScreen(client, new TradeMasterScreen(null));
		}
		while (this.startStopKey != null && this.startStopKey.consumeClick()) {
			RerollController.toggle();
		}
		while (this.pickPositionKey != null && this.pickPositionKey.consumeClick()) {
			PositionPicker.onHotkey();
		}
	}

	@Override
	public String keybindLabel(String id) {
		KeyMapping key = switch (id) {
			case "open_menu" -> this.openMenuKey;
			case "start_stop" -> this.startStopKey;
			case "pick_position" -> this.pickPositionKey;
			default -> null;
		};
		return key == null ? "-" : key.getTranslatedKeyMessage().getString();
	}

	@Override
	public void openConfigScreen() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) return;
		McClient.setScreen(client, new TradeMasterScreen(null));
	}

	@Override
	public boolean hasMalilib() {
		// maLiLib is not wired yet; the optional-integration pass restores it and flips
		// this back to true on the targets where it is available.
		return false;
	}

	@Override
	public java.nio.file.Path configDir() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
	}
}
//?}
