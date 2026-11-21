package dev.trademaster.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.trademaster.gui.TradeMasterScreen;

/**
 * Optional Mod Menu entry. Declared only in the Fabric metadata; on a game without
 * Mod Menu the entrypoint is never loaded, so the mod still runs with the V hotkey.
 */
public class ModMenuImpl implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// the full six-tab screen, with Mod Menu as the parent/back target
		return TradeMasterScreen::new;
	}
}
