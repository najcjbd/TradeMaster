package dev.trademaster.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/** Version-gated sound helpers. */
public final class McSound {

	/** Plays the vanilla UI button-click sound. 1.21.2 added
	 * {@code AbstractWidget.playButtonClickSound}; before that it is played directly. */
	public static void playButtonClick() {
		//? if >=1.21.2 {
		AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
		//?} else {
		/*Minecraft.getInstance().getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));*/
		//?}
	}

	private McSound() {}
}
