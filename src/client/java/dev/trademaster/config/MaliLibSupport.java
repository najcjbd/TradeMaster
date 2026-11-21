package dev.trademaster.config;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Loads the maLiLib adapter only when maLiLib is installed. The no-maLiLib build
 * keeps the adapter classes in the jar (so installing maLiLib later enables them)
 * but never touches them on a vanilla loader: an unconditional call would throw
 * NoClassDefFoundError before Mod Menu could even open the screen.
 */
public final class MaliLibSupport {

	public static boolean available() {
		if (!FabricLoader.getInstance().isModLoaded("malilib")) return false;
		var container = FabricLoader.getInstance().getModContainer("malilib");
		if (container.isEmpty()) return false;
		try {
			return container.get().getMetadata().getVersion()
					.compareTo(net.fabricmc.loader.api.Version.parse("0.29.3")) >= 0;
		} catch (net.fabricmc.loader.api.VersionParsingException e) {
			return false;
		}
	}

	public static void register() {
		if (available()) MaliLibAdapter.register();
	}

	public static boolean isConfigScreenOpen() {
		return available() && MaliLibAdapter.isConfigScreenOpen();
	}

	public static JsonObject writeHotkeys() {
		return available() ? MaliLibAdapter.writeHotkeys() : new JsonObject();
	}

	public static void readHotkeys(@Nullable JsonObject hotkeys) {
		if (available()) MaliLibAdapter.readHotkeys(hotkeys);
	}

	/** The maLiLib config screen, or null when the no-lib build must use the mod's
	 * own six-tab screen. */
	public static @Nullable Screen createConfigScreen() {
		return available() ? MaliLibAdapter.INSTANCE.get() : null;
	}

	public static String startStopKeys() {
		if (available()) return MaliLibAdapter.HOTKEY_START_STOP.getKeybind().getKeysDisplayString();
		var key = dev.trademaster.TradeMaster.getStartStopKey();
		return key == null ? "-" : key.getTranslatedKeyMessage().getString();
	}

	public static String pickPositionKeys() {
		if (available()) return MaliLibAdapter.HOTKEY_PICK_POSITION.getKeybind().getKeysDisplayString();
		var key = dev.trademaster.TradeMaster.getPickPositionKey();
		return key == null ? "-" : key.getTranslatedKeyMessage().getString();
	}

	private MaliLibSupport() {}
}
