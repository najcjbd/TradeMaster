package dev.trademaster.config;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;

/**
 * The only class that links against maLiLib/adapter types. It is loaded by
 * {@link MaliLibSupport} via reflection only after {@code available()} is true, so a
 * vanilla loader never resolves maLiLib or {@link MaliLibAdapter} (whose supertypes
 * include maLiLib's GuiBase).
 */
public final class MaliLibBootstrap {

	public static void register() {
		// "recommends" does not order mod initialization; register from maLiLib's
		// own init-done callback instead.
		fi.dy.masa.malilib.event.InitializationHandler.getInstance()
				.registerInitializationHandler(MaliLibAdapter::register);
	}

	public static Screen createConfigScreen() {
		return MaliLibAdapter.INSTANCE.get();
	}

	public static JsonObject writeHotkeys() {
		return MaliLibAdapter.writeHotkeys();
	}

	public static void readHotkeys(JsonObject hotkeys) {
		MaliLibAdapter.readHotkeys(hotkeys);
	}

	public static String startStopKeys() {
		return MaliLibAdapter.HOTKEY_START_STOP.getKeybind().getKeysDisplayString();
	}

	public static String pickPositionKeys() {
		return MaliLibAdapter.HOTKEY_PICK_POSITION.getKeybind().getKeysDisplayString();
	}

	public static boolean isConfigScreenOpen() {
		return MaliLibAdapter.isConfigScreenOpen();
	}

	private MaliLibBootstrap() {}
}
