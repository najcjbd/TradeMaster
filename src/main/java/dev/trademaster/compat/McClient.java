package dev.trademaster.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Version-gated access to the client's screen and overlay-message APIs.
 *
 * <p>26.2 moved both onto {@code Minecraft.gui}: {@code gui.screen()}, {@code
 * gui.setScreen()} and {@code gui.hud.setOverlayMessage()}. On 26.1 and every earlier
 * version they hang directly off {@code Minecraft} ({@code screen}, {@code setScreen()})
 * and off {@code gui} ({@code gui.setOverlayMessage()}). Same "plaintext is the branch
 * active on the default target" rule as {@link McIds}.
 */
public final class McClient {

	/** The currently open screen, or null. */
	public static @Nullable Screen screen(Minecraft mc) {
		//? if >=26.2 {
		/*return mc.gui.screen();*/
		//?} else {
		return mc.screen;
		//?}
	}

	/** Opens {@code screen} (null closes the current one). */
	public static void setScreen(Minecraft mc, @Nullable Screen screen) {
		//? if >=26.2 {
		/*mc.gui.setScreen(screen);*/
		//?} else {
		mc.setScreen(screen);
		//?}
	}

	/** Shows {@code text} as the action-bar overlay message. */
	public static void setOverlayMessage(Minecraft mc, Component text, boolean animate) {
		//? if >=26.2 {
		/*mc.gui.hud.setOverlayMessage(text, animate);*/
		//?} else {
		mc.gui.setOverlayMessage(text, animate);
		//?}
	}

	private McClient() {}
}
