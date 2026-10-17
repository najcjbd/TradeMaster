package dev.trademaster.platform;

/**
 * Per-loader platform services. Each loader's entrypoint installs an implementation on
 * startup; the shared (client-only) source set talks to the loader ONLY through this.
 *
 * <p>This is the seam that keeps loader-specific APIs out of the shared code so it
 * compiles for Fabric, NeoForge and Forge alike:
 * <ul>
 *   <li>keybind registration (Fabric {@code KeyBindingHelper} vs NeoForge/Forge events),</li>
 *   <li>the in-game config screen entry,</li>
 *   <li>the OPTIONAL maLiLib config-screen integration, which is <b>Fabric-only</b>
 *       (maLiLib is a Fabric library) — hence {@link #hasMalilib()} defaults to false.</li>
 * </ul>
 */
public interface TradeMasterPlatform {

	/** Registers this mod's keybinds (start/stop, pick position). */
	void registerKeybinds();

	/** Opens the mod's own in-game config screen (used on every loader). */
	void openConfigScreen();

	/** The loader's config directory (where the config file is read/written). */
	java.nio.file.Path configDir();

	/** Hotkey definitions to persist in the config file. maLiLib owns the hotkeys on
	 * Fabric; other loaders have none, so the default is empty. */
	default java.util.Map<String, com.google.gson.JsonElement> writeHotkeys() {
		return java.util.Map.of();
	}

	/** Load persisted hotkeys. No-op when the loader has none. */
	default void readHotkeys(com.google.gson.JsonObject section) {
	}

	/** True only on Fabric with maLiLib present, where the extra maLiLib screen is wired. */
	default boolean hasMalilib() {
		return false;
	}

	/** Display label for one of the mod's keybinds, identified by a stable id
	 * ({@code "open_menu"}, {@code "start_stop"}, {@code "pick_position"}), or {@code "-"}
	 * when the loader exposes none. The keybinds tab reads this so the shared GUI never
	 * touches a loader keybind type. */
	default String keybindLabel(String id) {
		return "-";
	}

	/** Whether a loader-provided config screen is currently open. maLiLib only pushes its
	 * edits into the model when it CLOSES, so {@code RerollController.start()} must refuse
	 * while one is up; loaders with no such screen always return false. */
	default boolean isConfigScreenOpen() {
		return false;
	}
}
