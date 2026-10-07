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

	/** True only on Fabric with maLiLib present, where the extra maLiLib screen is wired. */
	default boolean hasMalilib() {
		return false;
	}
}
