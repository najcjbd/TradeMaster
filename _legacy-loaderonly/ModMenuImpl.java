//? if fabric {
package dev.trademaster.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.trademaster.config.MaliLibAdapter;

public class ModMenuImpl implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// Must be a maLiLib GuiConfigsBase screen: MasaGadget's FastMasaGuiSwitcher
		// probes every ModMenuApi factory and drops screens that are not
		// GuiConfigsBase, which would hide Trade Master from the masa GUI switcher.
		// setParent keeps the ModMenu screen as the back target: discarding the
		// parent would drop the user into the game on close instead
		return parent -> MaliLibAdapter.INSTANCE.get().setParent(parent);
	}
}
//?}
