package dev.trademaster.config;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;

/**
 * Loads the maLiLib adapter only when maLiLib is installed. The no-maLiLib build
 * keeps the adapter classes in the jar (so installing maLiLib later enables them)
 * but never touches them on a vanilla loader: an unconditional call would throw
 * NoClassDefFoundError before Mod Menu could even open the screen.
 */
public final class MaliLibSupport {

	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded("malilib");
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

	public static String startStopKeys() {
		return available() ? MaliLibAdapter.HOTKEY_START_STOP.getKeybind().getKeysDisplayString() : "-";
	}

	public static String pickPositionKeys() {
		return available() ? MaliLibAdapter.HOTKEY_PICK_POSITION.getKeybind().getKeysDisplayString() : "-";
	}

	private MaliLibSupport() {}
}
