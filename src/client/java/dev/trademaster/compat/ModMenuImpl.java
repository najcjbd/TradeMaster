package dev.trademaster.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.trademaster.gui.TradeMasterScreen;

public class ModMenuImpl implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// open the full six-tab Trade Master screen (enchantments / settings /
		// keybinds / aliases / offers / villagers), not just a flat option list;
		// keep the ModMenu screen as the parent so closing returns there
		return TradeMasterScreen::new;
	}
}
