package dev.trademaster.config;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;

/**
 * Loads the maLiLib bridge only when maLiLib is installed. This class itself has NO
 * maLiLib or adapter references in its constant pool: those live in
 * {@link MaliLibBootstrap}, loaded by name only when {@link #available()} is true.
 * Without this split the verifier resolves MaliLibAdapter (whose supertypes include
 * maLiLib's GuiBase) even on a vanilla loader, crashing the client.
 */
public final class MaliLibSupport {

	private static @Nullable Boolean availableCache;
	private static @Nullable Class<?> bridgeClass;
	private static final java.util.Map<String, java.lang.reflect.Method> BRIDGE_METHODS =
			new java.util.HashMap<>();

	public static boolean available() {
		if (availableCache != null) return availableCache;
		boolean result;
		if (!FabricLoader.getInstance().isModLoaded("malilib")) {
			result = false;
		} else {
			var container = FabricLoader.getInstance().getModContainer("malilib");
			if (container.isEmpty()) {
				result = false;
			} else {
				try {
					result = container.get().getMetadata().getVersion()
							.compareTo(net.fabricmc.loader.api.Version.parse("0.29.3")) >= 0;
				} catch (net.fabricmc.loader.api.VersionParsingException e) {
					result = false;
				}
			}
		}
		availableCache = result;
		return result;
	}

	public static void register() {
		invoke("register");
	}

	public static @Nullable Screen createConfigScreen() {
		return invoke("createConfigScreen") instanceof Screen screen ? screen : null;
	}

	public static JsonObject writeHotkeys() {
		return invoke("writeHotkeys") instanceof JsonObject hotkeys ? hotkeys : new JsonObject();
	}

	public static void readHotkeys(@Nullable JsonObject hotkeys) {
		if (hotkeys != null) invoke("readHotkeys", new Class<?>[]{JsonObject.class}, hotkeys);
	}

	public static String startStopKeys() {
		Object result = invoke("startStopKeys");
		if (result instanceof String keys) return keys;
		var key = dev.trademaster.TradeMaster.getStartStopKey();
		return key == null ? "-" : key.getTranslatedKeyMessage().getString();
	}

	public static String pickPositionKeys() {
		Object result = invoke("pickPositionKeys");
		if (result instanceof String keys) return keys;
		var key = dev.trademaster.TradeMaster.getPickPositionKey();
		return key == null ? "-" : key.getTranslatedKeyMessage().getString();
	}

	public static boolean isConfigScreenOpen() {
		return invoke("isConfigScreenOpen") instanceof Boolean open && open;
	}

	private static @Nullable Object invoke(String method, Class<?>[] parameterTypes, Object... args) {
		if (!available()) return null;
		try {
			String key = method + java.util.Arrays.toString(parameterTypes);
			java.lang.reflect.Method cached = BRIDGE_METHODS.get(key);
			if (cached == null) {
				if (bridgeClass == null) bridgeClass = Class.forName("dev.trademaster.config.MaliLibBootstrap");
				cached = bridgeClass.getMethod(method, parameterTypes);
				BRIDGE_METHODS.put(key, cached);
			}
			return cached.invoke(null, args);
		} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
				| InvocationTargetException e) {
			dev.trademaster.TradeMaster.LOGGER.error("maLiLib bridge call {} failed", method, e);
			return null;
		}
	}

	private static @Nullable Object invoke(String method) {
		return invoke(method, new Class<?>[0]);
	}

	private MaliLibSupport() {}
}
