package dev.trademaster.gametest;

import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.gui.TradeMasterScreen;
import dev.trademaster.gui.VillagersListWidget;
import dev.trademaster.strategy.RigTarget;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.level.block.Blocks;

/**
 * The Villagers tab is actually EDITABLE: a click on the work-block field focuses it, keys and
 * characters are routed to it, and the screen's commit path writes the new id to the config.
 *
 * <p>This is a regression test. The row used to inherit the entry's no-op click handler, so
 * the field could never take focus and the whole tab was inert — an assertion on the widget
 * tree alone (like the sibling tab test) cannot see that.
 *
 * <p>Input events are delivered through the widget API rather than synthetic window events:
 * the client-gametest harness injects keystrokes/chars through the real screen but has no
 * screen-level mouse injection, so the focus click is delivered where the screen would put it.
 */
@SuppressWarnings("UnstableApiUsage")
public class VillagersTabEditClientGameTest implements FabricClientGameTest {

	/** GLFW_KEY_HOME / GLFW_KEY_DELETE. */
	private static final int HOME = 268;
	private static final int DELETE = 261;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientLevel().waitForChunksRender();
			// read the PRIOR value before overwriting it: capturing after the write would
			// "restore" the constant this test itself just set
			String original = context.computeOnClient((Minecraft mc) -> TradeMasterConfig.INSTANCE.workBlockId);
			context.runOnClient((Minecraft mc) -> TradeMasterConfig.INSTANCE.workBlockId = "minecraft:lectern");
			try {
				context.setScreen(() -> new TradeMasterScreen(null));
				context.waitForScreen(TradeMasterScreen.class);
				context.clickScreenButton("trademaster.tab.villagers");
				context.waitTicks(2);

				context.runOnClient((Minecraft mc) -> {
					VillagersListWidget list = findVillagersList(mc.gui.screen());
					if (list == null) throw new AssertionError("no Villagers list");
					VillagersListWidget.Row row = list.children().get(0);

					// the row must ACCEPT a click on its field (before the fix it fell through to
					// the entry's no-op handler, returned false and never focused anything)
					int fieldX = Math.max(row.getX() + 4, row.getX() + row.getWidth() - 160);
					MouseButtonEvent click = new MouseButtonEvent(fieldX + 75, row.getY() + 2 + 7,
							new MouseButtonInfo(0, 0));
					if (!row.mouseClicked(click, false)) {
						throw new AssertionError("clicking the work-block field did not focus it");
					}

					// the list routes keys to the focused field: empty it. HOME first so the
					// deletion is forward from the start — independent of where setValue left the
					// cursor (backspace alone would delete nothing if the cursor sat at position 0)
					if (!list.keyPressed(new KeyEvent(HOME, 0, 0))) {
						throw new AssertionError("the focused field did not consume HOME");
					}
					for (int i = 0; i < 40; i++) {
						if (!list.keyPressed(new KeyEvent(DELETE, 0, 0))) {
							throw new AssertionError("the focused field did not consume DELETE");
						}
					}
					// …and characters
					for (char c : "minecraft:barrel".toCharArray()) {
						if (!list.charTyped(new CharacterEvent(c))) {
							throw new AssertionError("the focused field did not consume '" + c + "'");
						}
					}

					// the screen's own commit path (tab switch / close / start all flush drafts)
					list.flushEdits();

					if (!"minecraft:barrel".equals(TradeMasterConfig.INSTANCE.workBlockId)) {
						throw new AssertionError("the typed work block id was not committed: "
								+ TradeMasterConfig.INSTANCE.workBlockId);
					}
					if (RigTarget.workBlock() != Blocks.BARREL) {
						throw new AssertionError("RigTarget did not follow the new work block");
					}
				});
				System.out.println("[TM-GAMETEST] villagers tab edit ok");
			} finally {
				// restore and close even when an assertion above threw, so the leaked config
				// cannot poison the NEXT game test in the same client JVM
				context.runOnClient((Minecraft mc) -> {
					TradeMasterConfig.INSTANCE.workBlockId = original;
					TradeMasterConfig.INSTANCE.save();
				});
				context.setScreen(() -> null);
				context.waitTick();
			}
		}
	}

	private static VillagersListWidget findVillagersList(Screen screen) {
		if (screen == null) return null;
		for (GuiEventListener child : screen.children()) {
			if (child instanceof VillagersListWidget list) return list;
		}
		return null;
	}
}
