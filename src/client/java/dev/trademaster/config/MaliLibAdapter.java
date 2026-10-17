package dev.trademaster.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.trademaster.TradeMaster;
import dev.trademaster.core.RerollController;
import dev.trademaster.gui.PositionPicker;
import fi.dy.masa.malilib.MaLiLibConfigs;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import fi.dy.masa.malilib.config.options.ConfigString;
import fi.dy.masa.malilib.event.InitializationHandler;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase.ConfigOptionWrapper;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * maLiLib view over {@link TradeMasterConfig}. The model stays the single
 * source of truth: values are pushed into these options when the maLiLib GUI
 * opens, and pulled back into the model when it closes ("OK"/apply).
 */
public class MaliLibAdapter implements IConfigHandler, IKeybindProvider, Supplier<GuiBase> {

	public static final MaliLibAdapter INSTANCE = new MaliLibAdapter();

	// hotkeys (only editable here, per requirement)
	public static final ConfigHotkey HOTKEY_START_STOP = labeled(new ConfigHotkey("startStop", "R", "", ""),
			"trademaster.hotkey.startStop");
	public static final ConfigHotkey HOTKEY_PICK_POSITION = labeled(new ConfigHotkey("pickPosition", "P", "", ""),
			"trademaster.hotkey.pickPosition");

	private final List<IConfigBase> genericOptions = new ArrayList<>();

	// mirrors of the model, kept in sync on open/apply
	private ConfigOptionList optReroll, optDetection, optNonBookDisposal;
	private ConfigBoolean optReserveCheaper, optDisplayRolls, optDebugLog;
	private ConfigBoolean optBasePriceAsSalePrice;
	private ConfigBoolean optNaming, optForceNamePriceOne;
	private ConfigBoolean optTimeoutEnabled, optPhaseTimeouts, optAutoDisable, optHuntCheaper;
	private ConfigInteger optMinLockEmeralds, optTimeout, optVillagerCheckInterval, optLecternHealGrace;
	private ConfigBoolean optProfessionHealCapEnabled;
	private ConfigInteger optProfessionHealCap;
	private ConfigInteger optLockMaxRetries;
	private ConfigInteger optOffhandSwapRetries;
	private ConfigInteger optPostDispatchBreakRetries;
	private ConfigBoolean optMultiEnchantProtection;
	private ConfigInteger optPlaceDelay, optInteractDelay;
	private ConfigBoolean optLegit, optSlow, optTp, optTpItem, optProtectAxe, optStopOnMove;
	private ConfigString optLecternPos, optSummonButtonPos, optSendAwayButtonPos;
	private ConfigString optRegionOrigin, optRegionDelta;
	private ConfigInteger optSphereRadius;
	private ConfigBoolean optGrindstone;
	private ConfigInteger optGrindstoneOpenRetries;
	private ConfigString optGrindstonePos, optCurseDropDir, optCustomCurses;
	/** The position text exactly as refreshFromModel last pushed it into the
	 * widgets: applyToModel skips options whose text never changed, so a
	 * position captured by the picker hotkey while this GUI was open cannot be
	 * clobbered by the stale open-time snapshot on close. */
	private String snapshotLecternPos = "", snapshotSummonPos = "", snapshotSendAwayPos = "";
	private String snapshotRegionOrigin = "", snapshotRegionDelta = "";
	private String snapshotGrindstonePos = "", snapshotCurseDropDir = "", snapshotCustomCurses = "";

	private MaliLibAdapter() {}

	public static void register() {
		// All targets are maLiLib static singletons; our entrypoint runs after
		// maLiLib's own (fabric.mod.json depends), so registering directly here
		// is safe and happens before maLiLib's onGameInitDone loads configs.
		ConfigManager.getInstance().registerConfigHandler(TradeMaster.MOD_ID, INSTANCE);
		Registry.CONFIG_SCREEN.registerConfigScreenFactory(
				new ModInfo(TradeMaster.MOD_ID, "Trade Master", INSTANCE));
		InputEventHandler.getKeybindManager().registerKeybindProvider(INSTANCE);
		HOTKEY_START_STOP.getKeybind().setCallback((action, key) -> {
			// activateOn=BOTH fires on press AND release; act on the press edge
			// only or toggle() fires twice and cancels itself out (R20-9)
			if (key.getSettings().getActivateOn() == KeyAction.BOTH && action != KeyAction.PRESS) return false;
			Minecraft client = Minecraft.getInstance();
			if (client.player == null) return false;
			RerollController.toggle();
			return true;
		});
		HOTKEY_PICK_POSITION.getKeybind().setCallback((action, key) -> {
			if (key.getSettings().getActivateOn() == KeyAction.BOTH && action != KeyAction.PRESS) return false;
			Minecraft client = Minecraft.getInstance();
			if (client.player == null) return false;
			PositionPicker.onHotkey();
			return true;
		});
		TradeMaster.LOGGER.info("Registered config screen and hotkeys with maLiLib");
		TradeMaster.LOGGER.info("Config screen registry now contains: {}",
				Registry.CONFIG_SCREEN.getAllModsWithConfigScreens().stream().map(ModInfo::modId).toList());
		// Diagnostic: verify our entry survives until maLiLib finishes game init
		InitializationHandler.getInstance().registerInitializationHandler(() ->
				TradeMaster.LOGGER.info("After maLiLib init done; registry contains: {}, config switcher enabled: {}",
						Registry.CONFIG_SCREEN.getAllModsWithConfigScreens().stream().map(ModInfo::modId).toList(),
						MaLiLibConfigs.Generic.ENABLE_CONFIG_SWITCHER.getBooleanValue()));
	}

	@Override
	public void addKeysToMap(IKeybindManager manager) {
		manager.addKeybindToMap(HOTKEY_START_STOP.getKeybind());
		manager.addKeybindToMap(HOTKEY_PICK_POSITION.getKeybind());
	}

	@Override
	public void addHotkeys(IKeybindManager manager) {
		// addKeybindToMap only feeds the internal conflict map: the Hotkeys tab
		// lists categories built by addHotkeysForCategory exclusively
		manager.addHotkeysForCategory("Trade Master", "trade-master",
				List.of(HOTKEY_START_STOP, HOTKEY_PICK_POSITION));
	}

	// maLiLib renders the row label from getTranslatedName() (which defaults to the
	// untranslated internal name) and the tooltip from getComment(); constructor arg
	// order is (name, value, comment, prettyName). Point both at lang keys so the
	// label and the detail come out in the game language.
	private static <T extends IConfigBase> T labeled(T option, String key) {
		((fi.dy.masa.malilib.config.options.ConfigBase<?>) option).setTranslatedName(key);
		option.setComment(key + ".comment");
		return option;
	}

	private void buildOptions() {
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		this.genericOptions.clear();

		this.optReroll = labeled(new ConfigOptionList("rerollMechanism", c.rerollMechanism, "", ""),
				"trademaster.settings.mechanism");
		this.optDetection = labeled(new ConfigOptionList("detectionMode", c.detectionMode, "", ""),
				"trademaster.settings.detection");
		this.optNonBookDisposal = labeled(new ConfigOptionList("nonBookDisposal", c.nonBookDisposal, "", ""),
				"trademaster.settings.nonBookDisposal");

		this.optReserveCheaper = labeled(new ConfigBoolean("reserveCheaperTraders", c.reserveCheaperTraders, "", ""),
				"trademaster.settings.reserveCheaperTraders");
		this.optMinLockEmeralds = labeled(new ConfigInteger("minLockEmeralds", c.minLockEmeralds, 1, 64, "", ""),
				"trademaster.settings.minLockEmeralds");
		this.optBasePriceAsSalePrice = labeled(new ConfigBoolean("basePriceAsSalePrice", c.basePriceAsSalePrice, "", ""),
				"trademaster.settings.basePriceAsSalePrice");
		this.optTimeoutEnabled = labeled(new ConfigBoolean("timeoutEnabled", c.timeoutEnabled, "", ""),
				"trademaster.settings.timeoutEnabled");
		this.optTimeout = labeled(new ConfigInteger("timeoutTicks", c.timeoutTicks, 1200, Integer.MAX_VALUE, "", ""),
				"trademaster.settings.timeoutTicks");
		this.optPhaseTimeouts = labeled(new ConfigBoolean("phaseTimeoutsEnabled", c.phaseTimeoutsEnabled, "", ""),
				"trademaster.settings.phaseTimeoutsEnabled");
		this.optVillagerCheckInterval = labeled(new ConfigInteger("villagerCheckInterval", c.villagerCheckInterval, 1, 72000, "", ""),
				"trademaster.settings.villagerCheckInterval");
		this.optLecternHealGrace = labeled(new ConfigInteger("lecternHealGrace", c.lecternHealGrace, 20, 1200, "", ""),
				"trademaster.settings.lecternHealGrace");
		this.optProfessionHealCapEnabled = labeled(new ConfigBoolean("professionHealCapEnabled", c.professionHealCapEnabled, "", ""),
				"trademaster.settings.professionHealCapEnabled");
		this.optProfessionHealCap = labeled(new ConfigInteger("professionHealCap", c.professionHealCap, 1, 20, "", ""),
				"trademaster.settings.professionHealCap");
		this.optAutoDisable = labeled(new ConfigBoolean("autoDisableAfterLock", c.autoDisableAfterLock, "", ""),
				"trademaster.settings.autoDisableAfterLock");
		this.optHuntCheaper = labeled(new ConfigBoolean("huntCheaper", c.huntCheaper, "", ""),
				"trademaster.settings.huntCheaper");
		this.optLockMaxRetries = labeled(new ConfigInteger("lockMaxRetries", c.lockMaxRetries, -1, 999, "", ""),
				"trademaster.settings.lockMaxRetries");
		this.optOffhandSwapRetries = labeled(new ConfigInteger("offhandSwapRetries", c.offhandSwapRetries, -1, 999, "", ""),
				"trademaster.settings.offhandSwapRetries");
		this.optPostDispatchBreakRetries = labeled(new ConfigInteger("postDispatchBreakRetries", c.postDispatchBreakRetries, -1, 999, "", ""),
				"trademaster.settings.postDispatchBreakRetries");
		this.optMultiEnchantProtection = labeled(new ConfigBoolean("multiEnchantProtection", c.multiEnchantProtection, "", ""),
				"trademaster.settings.multiEnchantProtection");
		this.optDisplayRolls = labeled(new ConfigBoolean("displayRolls", c.displayRolls, "", ""),
				"trademaster.settings.displayRolls");
		this.optDebugLog = labeled(new ConfigBoolean("debugLog", c.debugLog, "", ""),
				"trademaster.settings.debugLog");

		this.optNaming = labeled(new ConfigBoolean("namingEnabled", c.namingEnabled, "", ""),
				"trademaster.settings.namingEnabled");
		this.optForceNamePriceOne = labeled(new ConfigBoolean("forceNamePriceOne", c.forceNamePriceOne, "", ""),
				"trademaster.settings.forceNamePriceOne");

		this.optLegit = labeled(new ConfigBoolean("legitMode", c.legitMode, "", ""),
				"trademaster.settings.legitMode");
		this.optSlow = labeled(new ConfigBoolean("slowMode", c.slowMode, "", ""),
				"trademaster.settings.slowMode");
		this.optTp = labeled(new ConfigBoolean("tpToVillager", c.tpToVillager, "", ""),
				"trademaster.settings.tpToVillager");
		this.optTpItem = labeled(new ConfigBoolean("tpToItem", c.tpToItem, "", ""),
				"trademaster.settings.tpToItem");
		this.optProtectAxe = labeled(new ConfigBoolean("preventAxeBreaking", c.preventAxeBreaking, "", ""),
				"trademaster.settings.preventAxeBreaking");
		this.optStopOnMove = labeled(new ConfigBoolean("stopOnPlayerMove", c.stopOnPlayerMove, "", ""),
				"trademaster.settings.stopOnPlayerMove");
		this.optPlaceDelay = labeled(new ConfigInteger("placeDelay", c.placeDelay, 0, 20, "", ""),
				"trademaster.settings.placeDelay");
		this.optInteractDelay = labeled(new ConfigInteger("interactDelay", c.interactDelay, 0, 20, "", ""),
				"trademaster.settings.interactDelay");

		this.optLecternPos = labeled(new ConfigString("lecternPos", posToString(c.lecternPos), "", ""),
				"trademaster.settings.lecternPos");
		this.optSummonButtonPos = labeled(new ConfigString("summonButtonPos", posToString(c.summonButtonPos), "", ""),
				"trademaster.settings.summonButtonPos");
		this.optSendAwayButtonPos = labeled(new ConfigString("sendAwayButtonPos", posToString(c.sendAwayButtonPos), "", ""),
				"trademaster.settings.sendAwayButtonPos");
		this.optRegionOrigin = labeled(new ConfigString("villagerRegionOrigin", posToString(c.villagerRegionOrigin), "", ""),
				"trademaster.settings.villagerRegionOrigin");
		this.optRegionDelta = labeled(new ConfigString("villagerRegionDelta", posToString(c.villagerRegionDelta), "", ""),
				"trademaster.settings.villagerRegionDelta");
		this.optSphereRadius = labeled(new ConfigInteger("villagerSphereRadius", c.villagerSphereRadius, 1, 64, "", ""),
				"trademaster.settings.villagerSphereRadius");
		this.optGrindstone = labeled(new ConfigBoolean("grindstoneEnabled", c.grindstoneEnabled, "", ""),
				"trademaster.settings.grindstoneEnabled");
		this.optGrindstoneOpenRetries = labeled(new ConfigInteger("grindstoneOpenRetries", c.grindstoneOpenRetries, -1, 999, "", ""),
				"trademaster.settings.grindstoneOpenRetries");
		this.optGrindstonePos = labeled(new ConfigString("grindstonePos", posToString(c.grindstonePos), "", ""),
				"trademaster.settings.grindstonePos");
		this.optCurseDropDir = labeled(new ConfigString("curseDropDirection", directionToString(c.curseDropYaw, c.curseDropPitch), "", ""),
				"trademaster.settings.curseDropDirection");
		this.optCustomCurses = labeled(new ConfigString("customCurses", String.join(", ", c.customCurseEnchantments), "", ""),
				"trademaster.settings.customCurses");

		this.genericOptions.addAll(List.of(
				this.optReroll, this.optDetection, this.optNonBookDisposal,
				this.optReserveCheaper, this.optMinLockEmeralds, this.optBasePriceAsSalePrice,
				this.optTimeoutEnabled, this.optTimeout, this.optPhaseTimeouts,
				this.optVillagerCheckInterval, this.optLecternHealGrace, this.optProfessionHealCapEnabled, this.optProfessionHealCap,
				this.optAutoDisable, this.optHuntCheaper, this.optLockMaxRetries, this.optOffhandSwapRetries,
				this.optPostDispatchBreakRetries, this.optMultiEnchantProtection,
				this.optDisplayRolls, this.optDebugLog,
				this.optNaming, this.optForceNamePriceOne,
				this.optLegit, this.optSlow, this.optTp, this.optTpItem, this.optProtectAxe, this.optStopOnMove,
				this.optPlaceDelay, this.optInteractDelay,
				this.optLecternPos, this.optSummonButtonPos, this.optSendAwayButtonPos,
				this.optRegionOrigin, this.optRegionDelta, this.optSphereRadius,
				this.optGrindstone, this.optGrindstoneOpenRetries, this.optGrindstonePos, this.optCurseDropDir, this.optCustomCurses));
	}

	private void refreshFromModel() {
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		this.optReroll.setOptionListValue(c.rerollMechanism);
		this.optDetection.setOptionListValue(c.detectionMode);
		this.optNonBookDisposal.setOptionListValue(c.nonBookDisposal);
		this.optReserveCheaper.setBooleanValue(c.reserveCheaperTraders);
		this.optMinLockEmeralds.setIntegerValue(c.minLockEmeralds);
		this.optBasePriceAsSalePrice.setBooleanValue(c.basePriceAsSalePrice);
		this.optTimeoutEnabled.setBooleanValue(c.timeoutEnabled);
		this.optTimeout.setIntegerValue(c.timeoutTicks);
		this.optPhaseTimeouts.setBooleanValue(c.phaseTimeoutsEnabled);
		this.optVillagerCheckInterval.setIntegerValue(c.villagerCheckInterval);
		this.optLecternHealGrace.setIntegerValue(c.lecternHealGrace);
		this.optProfessionHealCapEnabled.setBooleanValue(c.professionHealCapEnabled);
		this.optProfessionHealCap.setIntegerValue(c.professionHealCap);
		this.optAutoDisable.setBooleanValue(c.autoDisableAfterLock);
		this.optHuntCheaper.setBooleanValue(c.huntCheaper);
		this.optLockMaxRetries.setIntegerValue(c.lockMaxRetries);
		this.optOffhandSwapRetries.setIntegerValue(c.offhandSwapRetries);
		this.optPostDispatchBreakRetries.setIntegerValue(c.postDispatchBreakRetries);
		this.optMultiEnchantProtection.setBooleanValue(c.multiEnchantProtection);
		this.optDisplayRolls.setBooleanValue(c.displayRolls);
		this.optDebugLog.setBooleanValue(c.debugLog);
		this.optNaming.setBooleanValue(c.namingEnabled);
		this.optForceNamePriceOne.setBooleanValue(c.forceNamePriceOne);
		this.optLegit.setBooleanValue(c.legitMode);
		this.optSlow.setBooleanValue(c.slowMode);
		this.optTp.setBooleanValue(c.tpToVillager);
		this.optTpItem.setBooleanValue(c.tpToItem);
		this.optProtectAxe.setBooleanValue(c.preventAxeBreaking);
		this.optStopOnMove.setBooleanValue(c.stopOnPlayerMove);
		this.optPlaceDelay.setIntegerValue(c.placeDelay);
		this.optInteractDelay.setIntegerValue(c.interactDelay);
		this.optLecternPos.setStringValue(posToString(c.lecternPos));
		this.optSummonButtonPos.setStringValue(posToString(c.summonButtonPos));
		this.optSendAwayButtonPos.setStringValue(posToString(c.sendAwayButtonPos));
		this.optRegionOrigin.setStringValue(posToString(c.villagerRegionOrigin));
		this.optRegionDelta.setStringValue(posToString(c.villagerRegionDelta));
		this.optSphereRadius.setIntegerValue(c.villagerSphereRadius);
		this.optGrindstone.setBooleanValue(c.grindstoneEnabled);
		this.optGrindstoneOpenRetries.setIntegerValue(c.grindstoneOpenRetries);
		this.optGrindstonePos.setStringValue(posToString(c.grindstonePos));
		this.optCurseDropDir.setStringValue(directionToString(c.curseDropYaw, c.curseDropPitch));
		this.optCustomCurses.setStringValue(String.join(", ", c.customCurseEnchantments));
		this.snapshotLecternPos = this.optLecternPos.getStringValue();
		this.snapshotSummonPos = this.optSummonButtonPos.getStringValue();
		this.snapshotSendAwayPos = this.optSendAwayButtonPos.getStringValue();
		this.snapshotRegionOrigin = this.optRegionOrigin.getStringValue();
		this.snapshotRegionDelta = this.optRegionDelta.getStringValue();
		this.snapshotGrindstonePos = this.optGrindstonePos.getStringValue();
		this.snapshotCurseDropDir = this.optCurseDropDir.getStringValue();
		this.snapshotCustomCurses = this.optCustomCurses.getStringValue();
	}

	private void applyToModel() {
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		boolean huntWasOn = c.huntCheaper;
		c.rerollMechanism = (RerollMechanism) this.optReroll.getOptionListValue();
		c.detectionMode = (DetectionMode) this.optDetection.getOptionListValue();
		c.nonBookDisposal = (NonBookDisposal) this.optNonBookDisposal.getOptionListValue();
		c.reserveCheaperTraders = this.optReserveCheaper.getBooleanValue();
		c.minLockEmeralds = this.optMinLockEmeralds.getIntegerValue();
		boolean basisRequested = this.optBasePriceAsSalePrice.getBooleanValue();
		if (basisRequested != c.basePriceAsSalePrice && RerollController.isRunning()) {
			// hunt marks were recorded in the old basis; switching mid-run would
			// reinterpret them, so refuse and put the checkbox back
			this.optBasePriceAsSalePrice.setBooleanValue(c.basePriceAsSalePrice);
			chatRed("trademaster.message.price_basis_locked");
		} else {
			c.basePriceAsSalePrice = basisRequested;
		}
		c.timeoutEnabled = this.optTimeoutEnabled.getBooleanValue();
		c.timeoutTicks = this.optTimeout.getIntegerValue();
		c.phaseTimeoutsEnabled = this.optPhaseTimeouts.getBooleanValue();
		c.villagerCheckInterval = this.optVillagerCheckInterval.getIntegerValue();
		c.lecternHealGrace = this.optLecternHealGrace.getIntegerValue();
		c.professionHealCapEnabled = this.optProfessionHealCapEnabled.getBooleanValue();
		c.professionHealCap = this.optProfessionHealCap.getIntegerValue();
		c.autoDisableAfterLock = this.optAutoDisable.getBooleanValue();
		boolean huntRequested = this.optHuntCheaper.getBooleanValue();
		c.huntCheaper = huntRequested;
		// mutual exclusion (same rule as the built-in widget): when both would
		// end up on, autoDisableAfterLock wins — and tell the user when they
		// just tried to enable hunt, instead of silently bouncing the checkbox
		if (c.autoDisableAfterLock && c.huntCheaper) {
			c.huntCheaper = false;
			if (!huntWasOn) chatRed("trademaster.message.hunt_blocked_by_autodisable");
		}
		// enabling hunt mid-run is refused (same rule as the built-in widget):
		// the live needs table holds lock vouchers and was built without hunt
		// rules. Disabling mid-run stays allowed — the precheck rebuilds it
		if (c.huntCheaper && !huntWasOn && RerollController.isRunning()) {
			c.huntCheaper = false;
			chatRed("trademaster.message.hunt_enable_blocked");
		}
		c.lockMaxRetries = this.optLockMaxRetries.getIntegerValue();
		c.offhandSwapRetries = this.optOffhandSwapRetries.getIntegerValue();
		c.postDispatchBreakRetries = this.optPostDispatchBreakRetries.getIntegerValue();
		c.multiEnchantProtection = this.optMultiEnchantProtection.getBooleanValue();
		c.displayRolls = this.optDisplayRolls.getBooleanValue();
		c.debugLog = this.optDebugLog.getBooleanValue();
		c.namingEnabled = this.optNaming.getBooleanValue();
		c.forceNamePriceOne = this.optForceNamePriceOne.getBooleanValue();
		c.legitMode = this.optLegit.getBooleanValue();
		c.slowMode = this.optSlow.getBooleanValue();
		// same coupling as the built-in settings widget, both directions: slow
		// requires legit, and turning legit off drags slow off with it (without
		// the second rule a legit-off would be silently bounced back here while
		// the built-in page accepts it)
		if (!c.legitMode) c.slowMode = false;
		if (c.slowMode) c.legitMode = true;
		c.tpToVillager = this.optTp.getBooleanValue();
		c.tpToItem = this.optTpItem.getBooleanValue();
		c.preventAxeBreaking = this.optProtectAxe.getBooleanValue();
		c.stopOnPlayerMove = this.optStopOnMove.getBooleanValue();
		c.placeDelay = this.optPlaceDelay.getIntegerValue();
		c.interactDelay = this.optInteractDelay.getIntegerValue();
		applyPosIfEdited(this.optLecternPos, this.snapshotLecternPos, c.lecternPos, v -> c.lecternPos = v, true, true);
		applyPosIfEdited(this.optSummonButtonPos, this.snapshotSummonPos, c.summonButtonPos, v -> c.summonButtonPos = v, true, true);
		applyPosIfEdited(this.optSendAwayButtonPos, this.snapshotSendAwayPos, c.sendAwayButtonPos, v -> c.sendAwayButtonPos = v, true, true);
		applyPosIfEdited(this.optRegionOrigin, this.snapshotRegionOrigin, c.villagerRegionOrigin, v -> c.villagerRegionOrigin = v, true, false);
		if (!this.snapshotRegionDelta.equals(this.optRegionDelta.getStringValue())) {
			applyDelta(this.optRegionDelta, c.villagerRegionDelta, v -> c.villagerRegionDelta = v);
		}
		c.villagerSphereRadius = this.optSphereRadius.getIntegerValue();
		c.grindstoneEnabled = this.optGrindstone.getBooleanValue();
		c.grindstoneOpenRetries = this.optGrindstoneOpenRetries.getIntegerValue();
		applyPosIfEdited(this.optGrindstonePos, this.snapshotGrindstonePos, c.grindstonePos, v -> c.grindstonePos = v, true, false);
		if (!this.snapshotCurseDropDir.equals(this.optCurseDropDir.getStringValue())) {
			String text = this.optCurseDropDir.getStringValue();
			if (text == null || text.isBlank()) {
				c.curseDropYaw = null;
				c.curseDropPitch = null;
			} else {
				float[] parsed = parseDirection(text);
				if (parsed != null) {
					c.curseDropYaw = parsed[0];
					c.curseDropPitch = parsed[1];
				}
			}
		}
		if (!this.snapshotCustomCurses.equals(this.optCustomCurses.getStringValue())) {
			c.customCurseEnchantments.clear();
			String text = this.optCustomCurses.getStringValue();
			if (text != null) {
				for (String token : text.split("[,\\s]+")) {
					if (token.isEmpty()) continue;
					Identifier id = Identifier.tryParse(token);
					// vanilla ids are forbidden: vanilla curses come from the curse tag
					if (id != null && !"minecraft".equals(id.getNamespace())) {
						c.customCurseEnchantments.add(id.toString());
					}
				}
			}
		}
		c.save();
		// push the effective values back: the coupling rules above (mutual
		// exclusion, mid-run hunt refusal, slow->legit) can override what the
		// widgets show, and the widgets must display the real model state
		refreshFromModel();
	}

	/** Applies a position option only when the user actually edited its text:
	 * unedited widgets still hold the open-time snapshot and must not overwrite
	 * positions captured meanwhile (e.g. by the picker hotkey). */
	private static void applyPosIfEdited(ConfigString option, String snapshot, @Nullable BlockPos current, java.util.function.Consumer<BlockPos> setter, boolean allowRelative, boolean required) {
		if (snapshot.equals(option.getStringValue())) return;
		applyPos(option, current, setter, allowRelative, required);
	}

	/** Empty text clears an optional position but keeps a required one (B5:
	 * blanking the lectern/buttons would brick the rig); malformed text keeps
	 * the previous value. */
	private static void applyPos(ConfigString option, @Nullable BlockPos current, java.util.function.Consumer<BlockPos> setter, boolean allowRelative, boolean required) {
		String text = option.getStringValue();
		if (text == null || text.isBlank()) {
			if (!required) setter.accept(null);
			return;
		}
		BlockPos parsed = parsePos(text, allowRelative, true);
		if (parsed != null) {
			setter.accept(parsed);
		} else if (current == null) {
			setter.accept(null);
		}
	}

	/** The delta is a size, not a position: "~" would silently turn it into an
	 * absolute player-relative coordinate and warp the staging region, and any
	 * component beyond ±64 is a typo (C5). World bounds must NOT apply: the
	 * dimension minY check would reject legitimate negative dy values (R20-3). */
	private static void applyDelta(ConfigString option, @Nullable BlockPos current, java.util.function.Consumer<BlockPos> setter) {
		String text = option.getStringValue();
		if (text == null || text.isBlank()) {
			setter.accept(null);
			return;
		}
		BlockPos parsed = parsePos(text, false, false);
		if (parsed != null
				&& Math.abs(parsed.getX()) <= 64 && Math.abs(parsed.getY()) <= 64 && Math.abs(parsed.getZ()) <= 64) {
			setter.accept(parsed);
		} else if (current == null) {
			setter.accept(null);
		}
	}

	@Override
	public void onConfigsChanged() {
		applyToModel();
	}

	@Override
	public void load() {
		// a config reload while a run is active must not silently switch the price
		// basis: hunt marks were recorded in the old basis (the screen rows already
		// refuse this, but load() is a separate maLiLib entry point)
		boolean basis = TradeMasterConfig.INSTANCE.basePriceAsSalePrice;
		boolean hunt = TradeMasterConfig.INSTANCE.huntCheaper;
		ConfigPersistence.load(TradeMasterConfig.INSTANCE);
		if (RerollController.isRunning()) {
			// run-locked settings must not change under a live needs table
			TradeMasterConfig.INSTANCE.basePriceAsSalePrice = basis;
			TradeMasterConfig.INSTANCE.huntCheaper = hunt;
		}
	}

	@Override
	public void save() {
		TradeMasterConfig.INSTANCE.save();
	}

	@Override
	public GuiBase get() {
		TradeMaster.LOGGER.info("maLiLib requested the Trade Master config screen");
		if (this.genericOptions.isEmpty()) {
			buildOptions();
		}
		refreshFromModel();
		return new ConfigGui();
	}

	public List<IConfigBase> getGenericOptions() {
		return this.genericOptions;
	}

	/** True while maLiLib's own config screen is open. maLiLib only pushes the
	 * widgets' pending edits into the model when that screen CLOSES
	 * (GuiConfigsBase.removed -> applyPendingModifications -> onSettingsChanged ->
	 * onConfigsChanged -> our applyToModel), so a run started meanwhile would
	 * snapshot the pre-edit values. Our own screen is not a GuiConfigsBase: it
	 * flushes its drafts itself. */
	public static boolean isConfigScreenOpen() {
		return Minecraft.getInstance().gui.screen() instanceof GuiConfigsBase;
	}

	private static void chatRed(String key) {
		Minecraft client = Minecraft.getInstance();
		net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.literal("[Trade Master] ")
				.append(net.minecraft.network.chat.Component.translatable(key)
						.withStyle(net.minecraft.ChatFormatting.RED));
		if (client.player != null) {
			client.player.sendSystemMessage(message);
		} else {
			// title screen: there is no chat, surface the rejection as a toast
			// instead of silently bouncing the checkbox
			net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(client.gui.toastManager(),
					new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(),
					net.minecraft.network.chat.Component.literal("Trade Master"), message);
		}
	}

	private static String posToString(@Nullable BlockPos pos) {
		return pos == null ? "" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static String directionToString(@Nullable Float yaw, @Nullable Float pitch) {
		if (yaw == null || pitch == null) return "";
		return trimAngle(yaw) + "," + trimAngle(pitch);
	}

	private static String trimAngle(float value) {
		return value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value);
	}

	/** "yaw,pitch"; yaw ±180, pitch ±90 — anything outside is a typo. */
	private static float @Nullable [] parseDirection(String text) {
		String[] parts = text.trim().split("[,\\s]+");
		if (parts.length != 2) return null;
		try {
			float yaw = Float.parseFloat(parts[0]);
			float pitch = Float.parseFloat(parts[1]);
			if (Float.isNaN(yaw) || Float.isNaN(pitch)) return null;
			if (yaw < -180f || yaw > 180f || pitch < -90f || pitch > 90f) return null;
			return new float[]{yaw, pitch};
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static @Nullable BlockPos parsePos(String s, boolean allowRelative, boolean worldBounds) {
		if (s == null || s.isBlank()) return null;
		// accept "x,y,z" and "x y z" alike — the built-in widget writes the
		// comma form with spaces, hand input often uses just spaces
		String[] parts = s.trim().split("[,\\s]+");
		if (parts.length != 3) return null;
		Minecraft client = Minecraft.getInstance();
		BlockPos base = client.player != null ? client.player.blockPosition() : null;
		int[] coords = new int[3];
		for (int i = 0; i < 3; i++) {
			String part = parts[i].trim();
			try {
				if (part.startsWith("~")) {
					if (!allowRelative || base == null) return null;
					int offset = part.length() == 1 ? 0 : Integer.parseInt(part.substring(1));
					coords[i] = i == 0 ? base.getX() + offset : i == 1 ? base.getY() + offset : base.getZ() + offset;
				} else {
					coords[i] = Integer.parseInt(part);
				}
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (worldBounds) {
			// world bounds: anything past the border or the build height is a typo
			// and would send the rig to unreachable coordinates
			if (Math.abs(coords[0]) > 30_000_000 || Math.abs(coords[2]) > 30_000_000) return null;
			int minY;
			int height;
			if (client.level != null) {
				minY = client.level.dimensionType().minY();
				height = client.level.dimensionType().height();
			} else {
				// main-menu config editing: no dimension to ask, fall back to the
				// union of all vanilla dimensions instead of skipping the check
				// (skipping would accept y values no dimension can hold)
				minY = -2048;
				height = 4096;
			}
			if (coords[1] < minY || coords[1] >= minY + height) return null;
		}
		return new BlockPos(coords[0], coords[1], coords[2]);
	}

	public static JsonObject writeHotkeys() {
		JsonObject o = new JsonObject();
		// full JSON form (keys + KeybindSettings): the bare key string would lose
		// press/release, context and cancel settings on every restart — maLiLib
		// keeps no separate store for them
		o.add("startStop", HOTKEY_START_STOP.getKeybind().getAsJsonElement());
		o.add("pickPosition", HOTKEY_PICK_POSITION.getKeybind().getAsJsonElement());
		return o;
	}

	public static void readHotkeys(@Nullable JsonObject o) {
		if (o == null) return;
		readHotkey(o, "startStop", HOTKEY_START_STOP.getKeybind());
		readHotkey(o, "pickPosition", HOTKEY_PICK_POSITION.getKeybind());
	}

	private static void readHotkey(JsonObject o, String key, IKeybind keybind) {
		JsonElement e = o.get(key);
		if (e == null) return;
		// object = full form with settings; primitive = legacy bare key string
		if (e.isJsonObject()) {
			keybind.setValueFromJsonElement(e);
		} else if (e.isJsonPrimitive()) {
			keybind.setValueFromString(e.getAsString());
		}
	}

	private class ConfigGui extends GuiConfigsBase {
		ConfigGui() {
			super(10, 50, TradeMaster.MOD_ID, null, "trademaster.malilib.title");
		}

		@Override
		public List<ConfigOptionWrapper> getConfigs() {
			List<IConfigBase> configs = new ArrayList<>(getGenericOptions());
			configs.add(HOTKEY_START_STOP);
			configs.add(HOTKEY_PICK_POSITION);
			return ConfigOptionWrapper.createFor(configs);
		}
	}
}
