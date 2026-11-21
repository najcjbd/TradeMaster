package dev.trademaster.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.trademaster.config.MaliLibSupport;
import dev.trademaster.gui.TradeMasterScreen;

public class ModMenuImpl implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// maLiLib present: its config screen owns the hotkey/config UI.
		// no maLiLib: fall back to the mod's own six-tab screen, with the ModMenu
		// screen as the parent so closing returns there.
		return parent -> {
			var maLiLib = MaliLibSupport.createConfigScreen();
			return maLiLib != null ? maLiLib : new TradeMasterScreen(parent);
		};
	}
}
