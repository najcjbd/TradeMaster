package dev.trademaster.gametest;

import dev.trademaster.gui.TradeMasterScreen;
import dev.trademaster.gui.VillagersListWidget;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Cross-feature GUI test: in a real (singleplayer) client, the mod screen opens with all
 * six tabs and the Villagers tab builds its two-row list. This is the wiring a pure-logic
 * test cannot reach — the tab layout math and the new list widget only exist as widgets.
 */
@SuppressWarnings("UnstableApiUsage")
public class VillagersTabClientGameTest implements FabricClientGameTest {

	private static final String[] TAB_KEYS = {
			"trademaster.tab.enchantments",
			"trademaster.tab.settings",
			"trademaster.tab.keybinds",
			"trademaster.tab.aliases",
			"trademaster.tab.offers",
			"trademaster.tab.villagers",
	};

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			context.runOnClient((Minecraft mc) -> {
				if (mc.level == null || mc.player == null) {
					throw new AssertionError("the singleplayer world did not load");
				}
			});

			context.setScreen(() -> new TradeMasterScreen(null));
			context.waitForScreen(TradeMasterScreen.class);

			context.runOnClient((Minecraft mc) -> {
				for (String key : TAB_KEYS) {
					String label = Component.translatable(key).getString();
					// an unresolvable key renders as the key itself: catch a missing lang entry
					if (label.equals(key)) {
						throw new AssertionError("the tab label is not translated: " + key);
					}
					if (!hasButton(mc.gui.screen(), label)) {
						throw new AssertionError("missing tab button: " + label);
					}
				}
			});

			context.clickScreenButton("trademaster.tab.villagers");
			context.waitTick();
			context.runOnClient((Minecraft mc) -> {
				VillagersListWidget list = findVillagersList(mc.gui.screen());
				if (list == null) {
					throw new AssertionError("the Villagers tab did not build its list");
				}
				// work block, profession and the non-tradeable profession list
				if (list.children().size() != 3) {
					throw new AssertionError("expected 3 rows, got " + list.children().size());
				}
			});
			System.out.println("[TM-GAMETEST] villagers tab ok");

			// drop the screen before the world closes, so the disconnect is clean
			context.setScreen(() -> null);
			context.waitTick();
		}
	}

	private static boolean hasButton(Screen screen, String label) {
		if (screen == null) return false;
		for (GuiEventListener child : screen.children()) {
			if (child instanceof AbstractButton button && button.getMessage().getString().equals(label)) {
				return true;
			}
		}
		return false;
	}

	private static VillagersListWidget findVillagersList(Screen screen) {
		if (screen == null) return null;
		for (GuiEventListener child : screen.children()) {
			if (child instanceof VillagersListWidget list) return list;
		}
		return null;
	}
}
