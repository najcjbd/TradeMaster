package dev.trademaster.core;

import dev.trademaster.anticheat.LookStep;
import dev.trademaster.anticheat.SmoothRotation;
import dev.trademaster.config.ConfigPersistence;
import dev.trademaster.config.DetectionMode;
import dev.trademaster.config.EnchantmentTarget;
import dev.trademaster.config.GoodSpec;
import dev.trademaster.config.RerollMechanism;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.config.TradeOfferSpec;
import dev.trademaster.detection.DetectionHub;
import dev.trademaster.detection.OfferSnapshot;
import dev.trademaster.exec.DispenserCheck;
import dev.trademaster.exec.BookDisposal;
import dev.trademaster.exec.ButtonPresser;
import dev.trademaster.exec.EnchantmentNamer;
import dev.trademaster.exec.ChestDepositor;
import dev.trademaster.exec.GrindstoneRecycler;
import dev.trademaster.exec.LockTradeExecutor;
import dev.trademaster.exec.NamingExecutor;
import dev.trademaster.gui.PositionPicker;
import dev.trademaster.matching.OfferCosts;
import dev.trademaster.matching.TradeMatcher;
import dev.trademaster.strategy.AxeRerollStrategy;
import dev.trademaster.strategy.LecternRestock;
import dev.trademaster.strategy.RigTarget;
import dev.trademaster.strategy.RerollStrategy;
import dev.trademaster.strategy.TaskChainRerollStrategy;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static orchestrator for the whole pipeline. Drives the state machine:
 * PRECHECK -> SUMMON -> AWAIT_VILLAGER -> ENSURE_LECTERN -> WAIT_PROFESSION
 * -> CHECK_TRADE -> (hit: LOCK -> NAME? -> SEND_AWAY) / (miss: REROLL).
 */
public final class RerollController {

	private static Phase phase = Phase.IDLE;
	private static @Nullable RunSession session = null;

	private static @Nullable RerollStrategy strategy = null;
	private static @Nullable LecternGuardian guardian = null;
	private static @Nullable ButtonPresser summonPresser = null;
	/** The pending dispenser check (open/scan/close), or null when idle. */
	private static @Nullable dev.trademaster.exec.DispenserCheck dispenserCheck = null;
	/** Ticks left before re-checking a dispenser whose minecart is missing. */
	private static int dispenserWaitTicks = 0;
	/** Whether the once-per-run "no dispenser configured" warning was shown. */
	private static boolean dispenserUnsetWarned = false;
	/** Whether the once-per-run "dispenser out of reach" warning was shown. */
	private static boolean dispenserOutOfReachWarned = false;
	/** Consecutive dispenser OPEN failures: a missing cart may be waited out forever, a
	 * dispenser that cannot be opened must not be. */
	private static int dispenserFailedAttempts = 0;
	private static @Nullable ButtonPresser sendAwayPresser = null;
	private static @Nullable LockTradeExecutor lockExecutor = null;
	private static @Nullable NamingExecutor namingExecutor = null;
	private static @Nullable BookDisposal depositor = null;
	/** Countdown of the stray-chest guard window after a depositor was active. */
	private static int strayChestTicks = 0;
	/** One-shot chat gate for the sneak-pause announcement. */
	private static boolean sneakPaused = false;
	/** Chat gate for the XP-based naming pause announcement; reset whenever XP is
	 * sufficient again, so each new XP-short episode announces itself once. */
	private static boolean xpNamingPaused = false;

	private static boolean detectionPending = false;
	private static boolean lockInteracted = false;
	/** The lock trade was sent to the server this LOCK attempt; never buy again. */
	private static boolean lockPurchaseInitiated = false;
	private static final LookStep checkLook = new LookStep();
	private static boolean restSummonPending = false;
	/** Set after send-away: wait until the lectern spot is empty again before the next cycle. */
	private static boolean awaitingRigClear = false;
	/** ENSURE_LECTERN is running its post-dispatch refresh: continue into AWAIT_VILLAGER when done. */
	private static boolean postDispatchRefresh = false;
	/** Break retries spent in the current post-dispatch refresh (capped by
	 * config.postDispatchBreakRetries, reset when the refresh starts). */
	private static int postDispatchBreakRetriesSpent = 0;
	/** This LOCK attempt already ran its pre-lock inventory/deposit check. */
	private static boolean lockSlotPrepared = false;
	/** Running sand-wheel mode definition captured at start() (see
	 * grindstoneConfigChanged). */
	private static boolean grindstoneSnapshotValid = false;
	private static boolean snapGrindstoneEnabled = false;
	private static @Nullable BlockPos snapGrindstonePos = null;
	/** Whether the double-coordinate staging region was complete when the run
	 * started: losing it mid-run stops the run (see the check in tick()). */
	/** Dimension the run started in: a change mid-run invalidates every stored
	 * position, so the pipeline stops instead of flailing at empty coordinates. */
	private static @Nullable Identifier sessionDimension = null;
	private static boolean snapRegionConfigured = false;
	/** The rig target (work block + target profession + non-tradeable list) the RUN started
	 * with. The rig breaks/re-places the work block and judges villagers by the profession, so
	 * a mid-run edit is a rig-definition change: stop the run rather than silently switching
	 * what the pipeline is working on (the grindstone guard mirrors this). */
	private static @Nullable String snapRigTarget = null;
	/** The lectern-sphere fallback was confirmed with /trademaster goon. Persists
	 * across runs, but only for the world it was granted in — RE-ENTERING a world
	 * asks again (decided 2026-09-25): a confirmation from elsewhere must never
	 * silently accept a sphere up to 64 blocks as the staging area, and re-prompting
	 * is one command. Enforced by the level check in start(). */
	private static boolean regionFallbackConfirmed = false;
	/** The level regionFallbackConfirmed belongs to (weak: the level is dropped on
	 * disconnect, and a stale confirmation must not survive into the next world). */
	private static java.lang.ref.WeakReference<Object> regionFallbackLevel = null;
	/** A start was refused because the region coords are incomplete and waits for the
	 * goon command; the command is invalid while this is false. */
	private static boolean regionFallbackPending = false;
	/** WAIT_PROFESSION self-heal refreshes for the current villager; capped per villager. */
	private static int professionHealCycles = 0;
	/** Consecutive ticks of rejected villager interactions in CHECK_TRADE. */
	private static int interactFailTicks = 0;
	/** Consecutive ticks the CHECK_TRADE villager read as non-librarian (re-claim grace). */
	private static int nonLibrarianTicks = 0;
	private static boolean urgentProfessionHeal = false;
	/** How often the current run already swapped villagers over a blocked lectern placement. */
	private static int placementFailRetries = 0;
	/** Consecutive ticks in LOCK with lockInteracted set but no merchant menu opening. */
	private static int lockNoMenuTicks = 0;
	/** Ticks to wait before rebuilding the lock executor after a rejected purchase. */
	private static int lockRetryDelayTicks = 0;
	/** Server-rejected lock purchase attempts for the current matched trade. */
	private static int lockAttempts = 0;
	/** Hunt-cheaper mode: locked price per enchantment this run (the user-facing
	 * "price table"). A marked target only matches a strictly cheaper copy; the
	 * table resets on start/stop like the needs table itself. */
	private static final Map<Identifier, Integer> huntMarkedPrices = new LinkedHashMap<>();
	/** Hunt-cheaper mode: temporary combo needs this run — a purchased
	 * multi-enchantment book records its qualifying-part set (id AND level) and
	 * price, and a later book with the SAME set only matches strictly cheaper
	 * (combo ratchet). Resets on start/stop together with the single-book price
	 * table. */
	private static final Map<Set<TradeMatcher.MatchPart>, Integer> comboHuntPrices = new LinkedHashMap<>();
	/** Whether the current needs table was built with the hunt overrides applied.
	 * Flips off when the user disables hunt-cheaper mid-run, triggering a plain
	 * rebuild (locked vouchers, named-villager deduction) on the next precheck. */
	private static boolean huntAppliedToNeeds = false;
	/** Hash of the live TARGET config (id/level/maxPrice of every enabled target, its spec
	 * shape, and the goods) the current needs table was built from, or null before the first
	 * refresh: any mid-run edit triggers a one-shot rebuild, same pattern as the hunt flip
	 * above. A long instead of a string because it is compared on EVERY tick — building a
	 * string cost an ArrayList plus a StringBuilder plus one Identifier.toString() per enabled
	 * target per tick (tens of objects, twice a second on 20 tps). The rig target itself (work
	 * block / profession / non-tradeable list) is NOT part of it: those stop the run instead
	 * (see snapRigTarget). */
	private static @Nullable Long needsFingerprint = null;
	/** Lock retries exhausted with a full inventory: draining chests before rerolling on. */
	private static boolean lockExhaustionDepositPending = false;
	/** Ticks to wait before the next post-dispatch break attempt (spaces out retries). */
	private static int breakRetryTicks = 0;
	/** A NEWLY scanned villager must be this close to the arrival point to count as
	 * arrived. Kept tight (1.5 blocks): the rig parks the villager on the ray point,
	 * and a looser scan would let an unrelated bystander beside the lectern be picked. */
	private static final double ARRIVE_DISTANCE_SQR = 2.25;
	/** An ALREADY chosen villager is only treated as gone beyond this (2 blocks). The
	 * dead band between the two thresholds is deliberate: without it a villager that
	 * merely shuffled out of the tight arrival radius would be dispatched and a fresh
	 * one summoned, over and over, while a genuinely departing villager is still
	 * caught (just a little later; the check-trade recovery path has its own exit). */
	private static final double AWAY_DISTANCE_SQR = 4.0;
	private static int phaseTicks = 0;
	private static int runTicks = 0;
	private static int slowInteractDelay = 0;
	private static int detectionArmedTick = 0;
	// statusText() is polled every tick (HUD + screen): cache it per (phase, tries)
	private static @Nullable String cachedStatusText = null;
	private static Phase cachedStatusPhase = Phase.IDLE;
	private static int cachedStatusTries = -1;
	private static @Nullable String cachedStatusLang = null;
	private static @Nullable String lastOverlayText = null;
	private static @Nullable Component lastOverlayComponent = null;

	private RerollController() {}

	// ---- public API ----------------------------------------------------------

	public static boolean isRunning() {
		return phase != Phase.IDLE;
	}

	/** The current pipeline phase. Package-private so a client game test in this package can
	 * watch the run advance. */
	static Phase currentPhase() {
		return phase;
	}

	/** Largest outstanding hunt budget (marked-1) across this run's marked prices.
	 * The hunt can demand more emeralds than any configured maxPrice, so the
	 * precheck's emerald floor must account for it. */
	public static int maxHuntBudget() {
		int max = 0;
		for (Map.Entry<Identifier, Integer> entry : huntMarkedPrices.entrySet()) {
			// a custom good's hunt is capped by its own price, exactly like a target's
			GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(entry.getKey());
			if (good != null) {
				int goodBudget = Math.min(entry.getValue() - 1, good.maxPrice());
				if (goodBudget > max) max = goodBudget;
				continue;
			}
			EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(entry.getKey());
			// the mark-1 budget; an unknown target (disabled/removed mid-run) is left
			// uncapped rather than pretending a 64 cap
			int budget = target == null ? entry.getValue() - 1
					: Math.min(entry.getValue() - 1, target.maxPrice());
			if (budget > max) max = budget;
		}
		return max;
	}

	public static void toggle() {
		if (isRunning()) stopManual();
		else start();
	}

	public static boolean start() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (isRunning()) {
			chat(Component.translatable("commands.trademaster.already_running").withStyle(ChatFormatting.RED));
			return false;
		}
		if (player == null || mc.level == null) return false;
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		// commit any in-flight settings draft FIRST: the start can come from the command
		// or a hotkey while the settings screen is open with an unfocused draft, and the
		// "missing position" guard below reads the config the row has not written yet —
		// without this a run is refused with a bogus "position missing"
		if (Minecraft.getInstance().gui.screen()
				instanceof dev.trademaster.gui.TradeMasterScreen settingsScreen) {
			settingsScreen.flushSettingsDrafts();
		}
		// name the position that is actually missing: always saying "lectern"
		// sends the user re-picking the wrong slot
		String missingPosKey = config.lecternPos == null ? "trademaster.settings.lecternPos"
				: config.summonButtonPos == null ? "trademaster.settings.summonButtonPos"
				: config.sendAwayButtonPos == null ? "trademaster.settings.sendAwayButtonPos" : null;
		if (missingPosKey != null) {
			chat(Component.translatable("trademaster.message.precheck_failed",
					Component.translatable(missingPosKey)).withStyle(ChatFormatting.RED));
			return false;
		}

		// a proper region supersedes an earlier acknowledgement of the sphere
		// fallback: once real coordinates are configured, a later loss of them must
		// ask for confirmation again
		if (regionConfigured()) regionFallbackConfirmed = false;
		// maLiLib's own config screen only pushes its edits to the model on CLOSE:
		// starting now would snapshot the pre-edit values, so refuse and say why
		if (dev.trademaster.config.MaliLibAdapter.isConfigScreenOpen()) {
			chat(Component.translatable("trademaster.message.config_screen_open")
					.withStyle(ChatFormatting.RED));
			return false;
		}
		// the confirmation is only valid for the world it was granted in: entering
		// another world must ask again instead of silently using that world's sphere
		Object level = Minecraft.getInstance().level;
		if (level != null && (regionFallbackLevel == null || regionFallbackLevel.get() != level)) {
			regionFallbackConfirmed = false;
		}
		// the staging area always exists: the configured region, or the lectern
		// sphere when its coords are not set. The fallback must be confirmed once
		// with /trademaster goon
		if (!regionConfigured() && !regionFallbackConfirmed) {
			regionFallbackPending = true;
			chat(Component.translatable("trademaster.message.region_fallback_prompt", sphereRadius())
					.withStyle(ChatFormatting.YELLOW));
			return false;
		}
		// a successful start consumes the pending request
		regionFallbackPending = false;
		if (!regionConfigured()) {
			chat(Component.translatable("trademaster.message.region_fallback_active", sphereRadius())
					.withStyle(ChatFormatting.YELLOW));
		}

		RunSession s = new RunSession(config.lecternPos, config.summonButtonPos,
				config.sendAwayButtonPos);
		s.movementGuard.start(player.position());
		// sand-wheel mode definition snapshot: a mid-run change to the enable flag
		// or the grindstone position stops the run (see grindstoneConfigChanged)
		snapRigTarget = rigTargetDefinition(config);
		snapGrindstoneEnabled = config.grindstoneEnabled;
		snapGrindstonePos = config.grindstonePos;
		grindstoneSnapshotValid = true;
		snapRegionConfigured = regionConfigured();
		huntMarkedPrices.clear();
		comboHuntPrices.clear();
		s.matcher.setHuntPrices(huntMarkedPrices);
		s.matcher.setComboHuntPrices(comboHuntPrices);
		refreshNeeds(mc, s);
		// the rig target is validated first (a bad work block / unknown profession would
		// make the pipeline stall with no explanation), then the demands, then the empty
		// check — a config whose demands are all dead must still explain itself
		warnRigTarget();
		warnUnmatchableDemands(mc);
		if (s.matcher.isEmpty()) {
			chat(Component.translatable("trademaster.message.all_done").withStyle(ChatFormatting.GREEN));
			return false;
		}
		// material/axe/lectern failures must reject the start right here: deferring
		// them to the first precheck tick reported "started", closed the GUI and the
		// user's in-progress pick, then stopped one tick later
		Component preconditionFailure = Preconditions.check();
		if (preconditionFailure != null) {
			chat(preconditionFailure.copy().withStyle(ChatFormatting.RED));
			return false;
		}

		// An open trade GUI would block lectern breaking/interacting; close it only
		// now that the start survived every rejection (a refused start must keep
		// the player's own merchant screen and picker alive). The mod's OWN settings
		// screen is closed here too: in singleplayer a Screen pauses the integrated
		// server, so a hotkey/command start would otherwise report "running" while the
		// pipeline never ticks (the Start button already closes it on the same terms).
		// NOTE: only these two screens are handled — a chest/anvil screen open at start
		// still pauses a singleplayer run.
		if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen
				|| mc.gui.screen() instanceof dev.trademaster.gui.TradeMasterScreen) {
			mc.gui.setScreen(null);
		}
		closeMerchantMenuIfOpen(mc);

		// second line of defence for the picker hijack: an armed pick would
		// capture the pipeline's own button presses into the rig positions —
		// cancel only now that the run actually proceeds, so a failed start
		// keeps the user's in-progress pick alive
		PositionPicker.cancel();
		loggedOnce.clear();
		session = s;
		sessionDimension = mc.level == null ? null : mc.level.dimension().identifier();
		runTicks = 0;
		professionHealCycles = 0;
		strayChestTicks = 0;
		sneakPaused = false;
		xpNamingPaused = false;
		LecternRestock.resetRestock();
		dispenserCheck = null;
		dispenserWaitTicks = 0;
		dispenserUnsetWarned = false;
		dispenserOutOfReachWarned = false;
		dispenserFailedAttempts = 0;
		s.goodBaseline.clear();
		s.lockedResults.clear();
		if (Minecraft.getInstance().player != null) {
			for (GoodSpec good : TradeMasterConfig.INSTANCE.goods) {
				Item item = good.resultItem();
				if (item != null) s.goodBaseline.put(item, Preconditions.countItem(Minecraft.getInstance().player, item));
			}
		}
		enter(Phase.PRECHECK);
		chat(Component.translatable("trademaster.message.started").withStyle(ChatFormatting.GREEN));
		return true;
	}

	public static void stopManual() {
		if (!isRunning()) {
			chat(Component.translatable("commands.trademaster.not_running").withStyle(ChatFormatting.RED));
			return;
		}
		stop(Component.translatable("trademaster.message.stopped"), ChatFormatting.YELLOW);
	}

	public static String statusText() {
		RunSession s = session;
		int tries = s == null ? 0 : s.tries;
		// the language joins the cache key: switching it mid-run (e.g. resting in
		// AWAIT_VILLAGER for minutes) must refresh the text, not serve the stale one
		String lang = Minecraft.getInstance().options.languageCode;
		if (cachedStatusText != null && cachedStatusPhase == phase && cachedStatusTries == tries
				&& lang.equals(cachedStatusLang)) {
			return cachedStatusText;
		}
		String base = switch (phase) {
			case IDLE -> Component.translatable("trademaster.status.idle").getString();
			case PRECHECK -> Component.translatable("trademaster.status.precheck").getString();
			case CHECK_TRADE -> Component.translatable("trademaster.status.check").getString();
			case REROLL -> Component.translatable("trademaster.status.break").getString();
			case SUMMON -> Component.translatable("trademaster.status.summon").getString();
			case AWAIT_VILLAGER -> Component.translatable("trademaster.status.await_villager").getString();
			case ENSURE_LECTERN -> Component.translatable("trademaster.status.place").getString();
			case WAIT_PROFESSION -> Component.translatable("trademaster.status.wait_profession").getString();
			case LOCK -> Component.translatable("trademaster.status.lock").getString();
			case NAME -> Component.translatable("trademaster.status.name").getString();
			case SEND_AWAY -> Component.translatable("trademaster.status.send_away").getString();
			case DEPOSIT -> Component.translatable("trademaster.status.deposit").getString();
		};
		// the "attempt N" suffix is about REROLLS: in LOCK/NAME/SEND_AWAY/DEPOSIT it
		// read as if those steps were being retried
		boolean rerolling = switch (phase) {
			case PRECHECK, CHECK_TRADE, REROLL, ENSURE_LECTERN, WAIT_PROFESSION -> true;
			default -> false;
		};
		cachedStatusText = !rerolling || tries <= 0
				? base
				: base + Component.translatable("trademaster.status.tries", tries).getString();
		cachedStatusPhase = phase;
		cachedStatusTries = tries;
		cachedStatusLang = lang;
		return cachedStatusText;
	}

	// ---- main tick -------------------------------------------------------------

	public static void tick(Minecraft client) {
		if (phase == Phase.IDLE) return;
		SmoothRotation.renderTick();

		LocalPlayer player = client.player;
		if (player == null || client.level == null || client.isPaused()) return;
		// a dead player cannot finish any phase; AWAIT_VILLAGER is watchdog-exempt
		// and would stall forever, and respawn would later trip the movement
		// guard with a misleading "player moved" message
		if (player.isDeadOrDying()) {
			stop(Component.translatable("trademaster.message.player_died"), ChatFormatting.RED);
			return;
		}
		// a dimension change mid-run (portal, server teleport) leaves every stored
		// position behind: the pipeline would flail at empty coordinates. Compared by
		// DIMENSION, not by level identity, so a same-dimension reconnect is untouched
		if (sessionDimension != null
				&& !client.level.dimension().identifier().equals(sessionDimension)) {
			stop(Component.translatable("trademaster.message.dimension_changed"), ChatFormatting.RED);
			return;
		}
		// vanilla skips every block interaction while sneaking with a non-empty
		// hand (and the offhand holds the lectern during runs): freeze the
		// pipeline instead of burning retries into misleading failures
		if (player.isShiftKeyDown()) {
			if (!sneakPaused) {
				sneakPaused = true;
				chat(Component.translatable("trademaster.message.sneak_paused").withStyle(ChatFormatting.YELLOW));
			}
			return;
		}
		sneakPaused = false;
		DetectionHub.tick();

		RunSession s = session;
		if (s == null) {
			phase = Phase.IDLE;
			return;
		}

		// hunt-cheaper can be turned off mid-run from ANY phase: this rebuild used
		// to live in tickPrecheck, but region layouts loop without ever re-entering
		// PRECHECK, leaving the stale hunt budget live (a cheaper copy of an
		// already-locked enchantment could match again). One-shot on the flip tick.
		if (!TradeMasterConfig.INSTANCE.huntCheaper && huntAppliedToNeeds) {
			debug("hunt-cheaper disabled mid-run, rebuilding needs without hunt overrides");
			huntMarkedPrices.clear();
			comboHuntPrices.clear();
			refreshNeeds(client, s);
			if (s.matcher.isEmpty() && !finalizingRun()) {
				stop(Component.translatable("trademaster.message.all_done"), ChatFormatting.GREEN);
				return;
			}
		}

		// live target edits (enable/disable, level, maxPrice) reshape the needs
		// table immediately, not just at the next run: non-hunt runs in region
		// layouts never re-enter PRECHECK, and a stale budget queue would keep
		// matching above a lowered maxPrice. Same one-shot pattern as the hunt flip.
		// Note: finishLock's autoDisableAfterLock writes the config itself — that
		// self-edit lands here too, so the finalizing guard below is what keeps a
		// just-locked single target running through NAME/SEND_AWAY instead of
		// stopping before the villager is named and sent away.
		long targetsNow = targetsFingerprint();
		if (needsFingerprint != null && targetsNow != needsFingerprint) {
			debug("target config edited mid-run, rebuilding needs");
			refreshNeeds(client, s);
			if (s.matcher.isEmpty() && !finalizingRun()) {
				stop(Component.translatable("trademaster.message.all_done"), ChatFormatting.GREEN);
				return;
			}
		}

		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		// the sand-wheel mode definition belongs to the running rig: the deposit
		// factory only re-reads it at deposit time, so a mid-run change would
		// silently switch how books are disposed of — stop the normal way instead
		if (grindstoneSnapshotValid && grindstoneConfigChanged(config)) {
			stop(Component.translatable("trademaster.message.grindstone_config_changed"), ChatFormatting.RED);
			return;
		}
		// the staging area is part of the running rig too: losing the region coords
		// mid-run would silently move the staging wait to the lectern sphere
		// a FINISHING run (naming / dispatching / a lock in flight) keeps its own definition:
		// stopping here would strand the locked villager, and the edit only matters to the next
		// run anyway (the rename/needs paths exempt finalizingRun the same way)
		if (!finalizingRun() && snapRigTarget != null
				&& !snapRigTarget.equals(rigTargetDefinition(config))) {
			// the rig definition (work block / target profession / non-tradeable list) belongs
			// to the running rig: switching any of them mid-run makes the pipeline work the old
			// spot with the new rules (or break/place a block that is not there). Stop the
			// normal way instead of switching under the pipeline.
			stop(Component.translatable("trademaster.message.rig_target_config_changed"), ChatFormatting.RED);
			return;
		}
		if (snapRegionConfigured && !regionConfigured()) {
			stop(Component.translatable("trademaster.message.region_broken"), ChatFormatting.RED);
			return;
		}
		if (!snapRegionConfigured && regionConfigured()) {
			// region coords were completed mid-run: the staging search already uses
			// them again. Treat this run as region-based from here on, so losing them
			// again stops the run (as documented) instead of silently reverting to
			// the sphere. NOTE: regionFallbackConfirmed is deliberately NOT touched —
			// it is a persistent acknowledgement and stop() does not reset it, so
			// setting it here would let a later start skip the /tm goon gate.
			snapRegionConfigured = true;
		}
		// resting in AWAIT_VILLAGER is exempt: the player may walk around freely
		if (phase != Phase.AWAIT_VILLAGER && s.movementGuard.hasMoved(player)) {
			stop(Component.translatable("trademaster.message.player_moved"), ChatFormatting.RED);
			return;
		}
		if (config.timeoutEnabled && phase != Phase.AWAIT_VILLAGER && ++runTicks > config.timeoutTicks) {
			stop(Component.translatable("trademaster.message.timeout"), ChatFormatting.RED);
			return;
		}
		phaseTicks++;
		if (overlayNoticeTicks > 0) {
			// a notice owns the action bar for a few seconds; the status line resumes
			// afterwards (lastOverlayText is cleared so it is re-posted even if the
			// text itself has not changed meanwhile)
			if (--overlayNoticeTicks == 0) {
				overlayNotice = null;
				lastOverlayText = null;
			}
		} else if (config.displayRolls) {
			String text = statusText();
			if (!text.equals(lastOverlayText)) {
				lastOverlayText = text;
				lastOverlayComponent = Component.literal(text);
			}
			client.gui.hud.setOverlayMessage(lastOverlayComponent, false);
		} else if (lastOverlayText != null) {
			// turned off mid-run: drop the stale line instead of leaving it on screen
			// until the next stop
			lastOverlayText = null;
			lastOverlayComponent = null;
			client.gui.hud.setOverlayMessage(Component.empty(), false);
		}

		// a dropped depositor can have a chest-open click in flight: the menu
		// arrives after it was abandoned and would block inventory SWAPs (axe
		// retrieval) in phases like REROLL. Only guard for a short window after
		// a depositor was active — otherwise this would force-close any chest
		// the user opens manually mid-run
		if (depositor != null) {
			strayChestTicks = 40;
		} else if (strayChestTicks > 0) {
			strayChestTicks--;
			if (player.containerMenu instanceof ChestMenu) {
				player.closeContainer();
			}
		}

		switch (phase) {
			case PRECHECK -> tickPrecheck(client, player, s);
			case SUMMON -> tickSummon(s);
			case AWAIT_VILLAGER -> tickAwaitVillager(client, s);
			case ENSURE_LECTERN -> tickEnsureLectern(client, s);
			case WAIT_PROFESSION -> tickWaitProfession(client, s);
			case CHECK_TRADE -> tickCheckTrade(client, player, s);
			case REROLL -> tickReroll(client, s);
			case LOCK -> tickLock(client, player, s);
			case NAME -> tickName(client, player, s);
			case SEND_AWAY -> tickSendAway(client, s);
			case DEPOSIT -> tickDeposit(player);
		}
	}

	private static void enter(Phase next) {
		if (phase == Phase.AWAIT_VILLAGER && next != Phase.AWAIT_VILLAGER && session != null) {
			// re-baseline after resting: the player may have moved while exempt
			Minecraft client = Minecraft.getInstance();
			if (client.player != null) {
				session.movementGuard.start(client.player.position());
			}
		}
		if (next != phase) {
			debug("phase " + phase + " -> " + next);
		}
		phase = next;
		phaseTicks = 0;
	}

	/** Phase safety timeouts are optional; the global watchdog is separate. */
	private static boolean phaseTimedOut(int limit) {
		return TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && phaseTicks > limit;
	}

	// ---- phase ticks ---------------------------------------------------------

	/**
	 * Chooses the book-disposal flow. Grindstone mode replaces EVERY deposit
	 * step, but only when it is fully armed (toggle + position + both drop
	 * angles — the angles are the curse-book escape hatch, and dropping
	 * without a configured direction is never allowed); any missing piece
	 * falls back to the classic chest flow, and null means "no disposal
	 * available at all".
	 */
	private static @Nullable BookDisposal createBookDisposal() {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		// a bought GOOD cannot be disenchanted: while one sits in the inventory the
		// chest/drop path must run, whatever the book-disposal mode says. Only the
		// EXCESS over the run's baseline is touched, so the player's own items that
		// match a good's result are never stored or dropped
		LocalPlayer disposerHolder = Minecraft.getInstance().player;
		Map<Item, Integer> produced = producedGoods(disposerHolder);
		if (!produced.isEmpty()) {
			if (!config.chestPositions.isEmpty()
					|| config.nonBookDisposal == dev.trademaster.config.NonBookDisposal.CHEST_THEN_DROP) {
				// in grindstone mode the books are still the recycler's business, so this
				// pass takes the produced goods only — but only when the grindstone path
				// will really be available for them (it needs its drop direction)
				boolean goodsOnly = config.grindstoneEnabled && config.grindstonePos != null
						&& config.curseDropYaw != null && config.curseDropPitch != null;
				// the BASELINE goes in, not the produced snapshot: the depositor derives
				// the allowance from the live inventory every tick, so a click the server
				// refuses can never spend it. The locked RESULTS go in too, so only a
				// stack that IS a locked trade's output (item + components) may be taken.
				RunSession locked = session;
				Map<Item, java.util.List<net.minecraft.world.item.ItemStack>> results =
						locked == null ? Map.of() : Map.copyOf(locked.lockedResults);
				return new ChestDepositor(config.chestPositions, disposalBaseline(disposerHolder), results, goodsOnly);
			}
			// no chest and no drop option: the produced goods cannot leave the inventory
			// here, but the ordinary path may still free space (grinding books), so fall
			// through instead of refusing to dispose anything at all
		}
		if (config.grindstoneEnabled && config.grindstonePos != null
				&& config.curseDropYaw != null && config.curseDropPitch != null) {
			return new GrindstoneRecycler();
		}
		if (config.chestPositions.isEmpty()) return null;
		return new ChestDepositor(config.chestPositions);
	}

	/** How many of each good's result item this run PRODUCED (carried now minus the
	 * start-of-run baseline), for goods that were actually matched this run. Books are
	 * not part of it: they are the recycler's/depositor's usual business. */
	private static Map<Item, Integer> producedGoods(@Nullable LocalPlayer player) {
		Map<Item, Integer> produced = new LinkedHashMap<>();
		RunSession s = session;
		if (player == null || s == null) return produced;
		for (Identifier id : s.lockedIds) {
			GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(id);
			if (good == null) continue;
			Item item = good.resultItem();
			if (item == null) continue;
			int now = Preconditions.countItem(player, item);
			int baseline = s.goodBaseline.getOrDefault(item, 0);
			int excess = now - baseline;
			if (excess > 0) produced.merge(item, excess, Integer::sum);
		}
		return produced;
	}

	/** The start-of-run count of every locked good's result item (0 when the player
	 * carried none). The depositor is handed this and re-derives the allowance as
	 * "count now minus baseline" from the live inventory each tick, so a click the
	 * server refuses is corrected back and can never spend the allowance. */
	private static Map<Item, Integer> disposalBaseline(@Nullable LocalPlayer player) {
		Map<Item, Integer> baseline = new LinkedHashMap<>();
		RunSession s = session;
		if (player == null || s == null) return baseline;
		for (Identifier id : s.lockedIds) {
			GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(id);
			if (good == null) continue;
			Item item = good.resultItem();
			if (item == null) continue;
			baseline.putIfAbsent(item, s.goodBaseline.getOrDefault(item, 0));
		}
		return baseline;
	}

	private static void tickPrecheck(Minecraft client, LocalPlayer player, RunSession s) {
		// hunt-cheaper honors live config edits: a target toggled off mid-run
		// pauses its hunt, toggled back on resumes it — resync every precheck.
		// Enabling hunt mid-run is refused at the config surfaces, so only the
		// OFF transition can happen here: rebuild the needs table without the
		// hunt overrides (locked vouchers and named-villager deduction return)
		if (TradeMasterConfig.INSTANCE.huntCheaper) {
			s.matcher.resyncTargets(s.lockedIds);
		}
		// the precheck walks the whole inventory and builds four collections: it only
		// needs to run periodically, not on every tick of the PRECHECK phase
		int precheckInterval = Math.max(1, TradeMasterConfig.INSTANCE.villagerCheckInterval);
		if (phaseTicks % precheckInterval == 0) {
			Component failure = Preconditions.check();
			if (failure != null) {
				stop(failure.copy().withStyle(ChatFormatting.RED), null);
				return;
			}
		}
		if (!Preconditions.hasFreeSlot(player)) {
			depositor = createBookDisposal();
			if (depositor == null) {
				stop(Component.translatable("trademaster.message.inventory_full"), ChatFormatting.RED);
			} else {
				enter(Phase.DEPOSIT);
			}
			return;
		}
		// Baby freeze: while ANY baby occupies the arrival point, nothing else
		// happens — no picking (even of a usable adult), no dispatching, no
		// summoning. A baby grows into a usable villager on its own; once no
		// babies remain, the normal flow resumes exactly where it left off.
		// one scan for both the baby and the blocker question below: this runs every
		// PRECHECK tick, and a second query would repeat the same entity lookup
		List<Villager> atArrival = villagersAtArrivalPoint(client, s.lecternPos);
		boolean babyAtArrival = atArrival.stream().anyMatch(Villager::isBaby);
		if (babyAtArrival) {
			debug("precheck: baby at detection point, waiting until no babies remain");
			guardian = null;
			summonPresser = null;
			// keep the staging wait armed: with the arrival point merged into the
			// staging scan a baby there still freezes the wait, and the moment no
			// baby is left the summon can be pressed again. (false would strand this
			// phase: it only scans the arrival point, so a baby that wanders away
			// left nothing that could ever press summon.)
			restSummonPending = true;
			enter(Phase.AWAIT_VILLAGER);
			return;
		}
		// A librarian can be rolled directly; an unemployed villager already at the
		// arrival point is just as usable — place the lectern and let it take the
		// profession instead of summoning another villager.
		s.villager = findArrivedVillager(client, s.lecternPos);
		if (s.villager == null) {
			// a nitwit (or a villager employed in any non-librarian profession)
			// already squatting at the detection point would block later arrivals
			// and can never become a librarian: send it away first
			boolean blockerAtArrival = atArrival.stream()
					.anyMatch(v -> !v.isBaby() && unusableProfession(v.getVillagerData().profession()));
			if (blockerAtArrival) {
				debug("precheck: unusable villager at detection point, sending away first");
				sendAwayPresser = null;
				restSummonPending = true;
				enter(Phase.SEND_AWAY);
				return;
			}
			guardian = null;
			summonPresser = null;
			// the minecart can only grab a villager standing in the staging area: wait
			// for one to appear there before pressing summon (the area is the
			// configured region, or the lectern sphere when it is not set)
			restSummonPending = true;
			enter(Phase.AWAIT_VILLAGER);
			return;
		}
		guardian = new LecternGuardian();
		professionHealCycles = 0;
		enter(Phase.ENSURE_LECTERN);
	}

	private static void tickDeposit(LocalPlayer player) {
		if (depositor == null) {
			enter(Phase.PRECHECK);
			return;
		}
		RerollStrategy.StepResult result = depositor.tick();
		if (result.step() == RerollStrategy.Step.DONE) {
			depositor = null;
			if (!Preconditions.hasFreeSlot(player)) {
				// everything depositable is gone but the inventory is still full
				// (non-book items): re-running PRECHECK would bounce straight back
				// here and open/close the chests forever
				stop(Component.translatable("trademaster.message.inventory_full"), ChatFormatting.RED);
				return;
			}
			enter(Phase.PRECHECK);
		} else if (result.step() == RerollStrategy.Step.FAILED
				|| result.step() == RerollStrategy.Step.FAILED_FULL) {
			stop(result.reason(), ChatFormatting.RED);
		}
	}

	/** Whether the summon may proceed: true when no dispenser is configured (with a
	 * once-per-run warning), or when the configured one currently holds a minecart.
	 *
	 * <p>A MISSING CART is waited out a minute at a time (the rig recycles carts, so it is
	 * normally transient). An unreachable position is treated as unset. A position that holds
	 * no dispenser, or one that repeatedly refuses to open, is a rig/config error: it is
	 * reported and the run stops instead of waiting for ever.
	 *
	 * <p>Package-private so a client game test in this package can drive the gate against a
	 * real dispenser. */
	/** Drops an in-flight dispenser check (closing any container it opened) and its pending
	 * wait: the gate is being turned off, and neither may linger into a later check. */
	private static void releaseCheck() {
		if (dispenserCheck != null) {
			// the player can already be gone here (stop() runs from the disconnect hook), and
			// abort() is null-safe: a half-stopped pipeline would otherwise NPE on every stop
			dispenserCheck.abort(Minecraft.getInstance().player);
			dispenserCheck = null;
		}
		dispenserWaitTicks = 0;
	}

	static boolean dispenserReady() {
		var pos = TradeMasterConfig.INSTANCE.dispenserPos;
		if (pos == null) {
			// the gate is off: drop any in-flight check (its open container would swallow the
			// button press) and the pending wait, so neither lingers into a later check
			releaseCheck();
			if (!dispenserUnsetWarned) {
				dispenserUnsetWarned = true;
				notice(Component.translatable("trademaster.message.dispenser_unset"));
			}
			return true;
		}
		LocalPlayer dispenserPlayer = Minecraft.getInstance().player;
		// a coordinate the player cannot REACH is as good as unset: the open would just
		// fail and we would wait a minute for nothing, so treat it like a parse failure
		if (dispenserPlayer == null || !dispenserPlayer.isWithinBlockInteractionRange(pos, 1.0)) {
			// as above: an unreachable position must not leave a wait (or an open menu) behind
			releaseCheck();
			if (!dispenserOutOfReachWarned) {
				dispenserOutOfReachWarned = true;
				chat(Component.translatable("trademaster.message.dispenser_out_of_reach")
						.withStyle(ChatFormatting.YELLOW));
			}
			return true;
		}
		if (dispenserWaitTicks > 0) {
			dispenserWaitTicks--;
			return false;
		}
		if (dispenserCheck == null) dispenserCheck = new dev.trademaster.exec.DispenserCheck(pos);
		switch (dispenserCheck.tick()) {
			case RUNNING -> {
				return false;
			}
			case HAS_CART -> {
				dispenserCheck = null;
				dispenserFailedAttempts = 0;
				return true;
			}
			case NO_CART -> {
				// transient by design: the clone command refills the dispenser. The open
				// itself worked, so the consecutive-failure counter starts over.
				dispenserCheck = null;
				dispenserFailedAttempts = 0;
				dispenserWaitTicks = 20 * 60;
				debug("summon: dispenser has no minecart yet, retrying in a minute");
				return false;
			}
			case NO_DISPENSER -> {
				// the position holds no dispenser (wrong coordinate, or the block was
				// replaced): the open can never succeed, so say so and stop
				dispenserCheck = null;
				stop(Component.translatable("trademaster.message.dispenser_not_a_dispenser"),
						ChatFormatting.RED);
				return false;
			}
			case FAILED -> {
				// the open was refused or timed out: a few times may be lag, for ever is a
				// walled-off / broken dispenser
				dispenserCheck = null;
				if (++dispenserFailedAttempts >= 3) {
					stop(Component.translatable("trademaster.message.dispenser_open_failed"),
							ChatFormatting.RED);
					return false;
				}
				dispenserWaitTicks = 20 * 5;
				debug("summon: could not open the dispenser, retrying shortly (attempt "
						+ dispenserFailedAttempts + ")");
				return false;
			}
		}
		return false;
	}

	private static void tickSummon(RunSession s) {
		// the minecart gate: with a dispenser configured the summon must not fire without
		// a cart (the villager could not be sent away afterwards and the rig jams). The
		// check opens the dispenser — the only client-side way to see its slots — and a
		// missing cart is WAITED OUT (the rig recycles carts; the return path is long).
		// the gate is consulted ONCE per summon attempt: re-opening the dispenser on every
		// press tick would starve the button presser (its confirm window is counted in press
		// ticks) and open/close the container while the button is being pressed
		if (summonPresser == null && !dispenserReady()) return;
		if (summonPresser == null) summonPresser = new ButtonPresser(s.summonButtonPos);
		RerollStrategy.StepResult result = summonPresser.tick();
		if (result.step() == RerollStrategy.Step.DONE) {
			summonPresser = null;
			enter(Phase.AWAIT_VILLAGER);
		} else if (result.step() == RerollStrategy.Step.FAILED) {
			stop(result.reason(), ChatFormatting.RED);
		}
	}

	private static void tickAwaitVillager(Minecraft client, RunSession s) {
		int interval = Math.max(1, TradeMasterConfig.INSTANCE.villagerCheckInterval);
		if (phaseTicks % interval != 0) return;
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		if (awaitingRigClear) {
			// the send-away button was pressed; wait until the ray detection point
			// is empty again, otherwise the departing (falling) entity gets
			// re-targeted and the whole run loops on it. Uses the LOOSE threshold:
			// a villager still in the dead band has not left yet — reading it as
			// gone lets the staging scan pick it straight back up
			if (!villagersAtArrivalPoint(client, arrivalPoint(client, s.lecternPos), AWAY_DISTANCE_SQR)
					.isEmpty()) {
				return;
			}
			awaitingRigClear = false;
			// keep resting here until a villager enters the staging area (the region
			// box, or the lectern sphere when the region coords are not set)
		}

		List<Villager> villagers;
		int blockerCount = 0;
		if (restSummonPending) {
			// Staging wait: the minecart can only grab a villager standing in the
			// staging area (the configured region, or the lectern sphere when the
			// region coords are not set), so only look there before summoning.
			villagers = stagingVillagers(client, s);
		} else {
			// Arrival wait: the rig lines player -> lectern -> villager up on one
			// ray; only villagers standing on the ray detection point count.
			villagers = villagersAtArrivalPoint(client, s.lecternPos);
		}
		// Baby freeze (arrival wait): while ANY baby stands at the detection
		// point, nothing else happens — not even picking a usable adult or
		// dispatching a nitwit. Babies grow up on their own; the normal flow
		// resumes once none are left.
		if (!restSummonPending && villagers.stream().anyMatch(Villager::isBaby)) {
			debugOnce("await-baby-detection", "await-villager: baby at detection point, waiting until no babies remain");
			return;
		}
		if (restSummonPending) {
			// a baby can never claim the lectern: wait until it is gone from the
			// staging region instead of summoning it into the rig, where it would
			// trigger the endless profession-heal loop
			for (Villager villager : villagers) {
				if (villager.isBaby()) {
					debugOnce("await-baby-staging", "await-villager: baby villager in the staging region, waiting");
					return;
				}
			}
			// the region box need not contain the arrival point, so check it too: a
			// baby standing there blocks the rig exactly like one in the staging area
			if (villagersAtArrivalPoint(client, s.lecternPos).stream().anyMatch(Villager::isBaby)) {
				debugOnce("await-baby-arrival", "await-villager: baby at the arrival point, waiting");
				return;
			}
		}
		for (Villager villager : villagers) {
			// a baby at the arrival point grows into a usable villager on its
			// own: skip it and keep waiting (same trap as findArrivedVillager —
			// it could never claim the lectern)
			if (villager.isBaby()) continue;
			var profession = villager.getVillagerData().profession();
			if (unusableProfession(profession)) {
				// a nitwit can never take a profession, and an employed
				// non-librarian will never switch to one at this lectern: never
				// treat either as rollable, just count it so the blockage gets
				// cleared below
				blockerCount++;
				continue;
			}
			// PRECHECK only runs at start; materials (books/emeralds, axe,
			// lectern, name tags) can run out after several locks. Re-check before
			// spending another villager + rerolls on a lock that cannot happen.
			Component materials = Preconditions.check();
			if (materials != null) {
				stop(materials.copy().withStyle(ChatFormatting.RED), null);
				return;
			}
			if (restSummonPending) {
				// a villager is back in the zone: dispatch it with the summon button
				debug("await-villager: villager in staging zone, pressing summon");
				restSummonPending = false;
				summonPresser = null;
				enter(Phase.SUMMON);
				return;
			}
			debug("await-villager: villager arrived at detection point (profession=" + professionName(profession) + ")");
			s.villager = villager;
			professionHealCycles = 0;
			guardian = new LecternGuardian();
			enter(Phase.ENSURE_LECTERN);
			return;
		}
		// No usable villager found but blocker(s) present: clear the blockage.
		if (blockerCount > 0) {
			if (restSummonPending) {
				// staging zone: dispatch the blocker through the normal summon path;
				// it gets sent away once it reaches the arrival point
				debug("await-villager: " + blockerCount + " unusable villager(s) in staging zone, pressing summon");
				restSummonPending = false;
				summonPresser = null;
				enter(Phase.SUMMON);
			} else {
				// arrival point: the blocker can never become a librarian — press the
				// send-away button instead of waiting on it forever
				debug("await-villager: " + blockerCount + " unusable villager(s) at detection point, sending away");
				sendAwayPresser = null;
				restSummonPending = true;
				enter(Phase.SEND_AWAY);
			}
		}
	}

	/**
	 * No villager available: pause the loop, like a cashier resting between
	 * customers. The run keeps going and the matcher's needs list is preserved,
	 * so already-found/auto-disabled/re-cheaper logic stays intact for the
	 * session. The send-away button is pressed once to clear the rig, then the
	 * run rests in AWAIT_VILLAGER; once a villager reappears in the detection
	 * zone, the summon button is pressed to dispatch it (restSummonPending).
	 */
	private static void awaitNextVillager(Minecraft client, RunSession s) {
		s.villager = null;
		if (s.matchedTrade != null) {
			// a locked target is out of the needs (dropLocked); restoring its budget
			// would resurrect it via computeIfAbsent for a duplicate purchase (e.g.
			// hunt turned off after a cheaper copy matched). Hunt-marked targets stay
			// live through applyHuntOverrides and keep their restore path
			if (!matchLockedDone(s)) {
				s.matcher.restoreBudget(s.matchedTrade);
			}
		}
		s.matchedTrade = null;
		lockExecutor = null;
		if (namingExecutor != null) {
			namingExecutor.abortCleanly();
			namingExecutor = null;
		}
		if (guardian != null) {
			guardian.abort();
			guardian = null;
		}
		if (strategy != null) {
			strategy.abort();
			strategy = null;
		}
		detectionPending = false;
		DetectionHub.disarm();
		// a non-delivery disarm leaves no screen in flight: drop the residual
		// suppression so it cannot swallow a later legitimate merchant screen
		DetectionHub.clearPostDisarmSuppression();
		lockInteracted = false;
		lockPurchaseInitiated = false;
		checkLook.reset();
		lockSlotPrepared = false;
		professionHealCycles = 0;
		interactFailTicks = 0;
		nonLibrarianTicks = 0;
		urgentProfessionHeal = false;
		slowInteractDelay = 0;
		if (client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen) {
			client.gui.setScreen(null);
		}
		closeMerchantMenuIfOpen(client);
		sendAwayPresser = null;
		restSummonPending = true;
		enter(Phase.SEND_AWAY);
	}

	private static void tickEnsureLectern(Minecraft client, RunSession s) {
		if (breakRetryTicks > 0) {
			breakRetryTicks--;
			return;
		}
		if (guardian == null) guardian = new LecternGuardian();
		RerollStrategy.StepResult result = guardian.tick(s);
		if (result.step() == RerollStrategy.Step.DONE) {
			guardian = null;
			placementFailRetries = 0;
			if (postDispatchRefresh) {
				// the post-dispatch break is done and the lectern spot is empty; now
				// rest until the dispatched villager is gone and a new one arrives —
				// the lectern is re-placed on the arrival path, not here
				postDispatchRefresh = false;
				awaitingRigClear = true;
				restSummonPending = true;
				enter(Phase.AWAIT_VILLAGER);
			} else {
				enter(Phase.WAIT_PROFESSION);
			}
		} else if (result.step() == RerollStrategy.Step.FAILED) {
			if (postDispatchRefresh) {
				if (isPermanentAxeFailure(result.reason())) {
					// no usable axe left: retrying can never succeed, and neither the
					// global watchdog (off by default) nor a phase timeout bounds this
					// loop — surface the reason and stop instead of hanging silently
					stop(result.reason(), ChatFormatting.RED);
					return;
				}
				// the post-dispatch break releases the old profession claim and must
				// eventually succeed, but a lectern that can never be broken (swapped
				// out, out of reach, gamemode stuck) must not hang the run silently:
				// the retries are capped by config.postDispatchBreakRetries
				// (-1 = forever; 2s between attempts so instant failures do not busy-loop)
				int maxBreakRetries = TradeMasterConfig.INSTANCE.postDispatchBreakRetries;
				if (maxBreakRetries >= 0 && postDispatchBreakRetriesSpent >= maxBreakRetries) {
					debug("ensure-lectern: post-dispatch break retries exhausted ("
							+ postDispatchBreakRetriesSpent + "), stopping");
					stop(result.reason(), ChatFormatting.RED);
					return;
				}
				postDispatchBreakRetriesSpent++;
				debug("ensure-lectern: post-dispatch break failed, retrying ("
						+ postDispatchBreakRetriesSpent + "/"
						+ (maxBreakRetries < 0 ? "inf" : maxBreakRetries) + ")");
				guardian.abort();
				guardian = LecternGuardian.breakOnly();
				breakRetryTicks = 40;
				return;
			}
			// !postDispatchRefresh is always true here (audited 2026-09-19): both exits
			// of the postDispatchRefresh branch above return.
			if (!postDispatchRefresh && guardian.placementBlocked() && placementFailRetries < 2) {
				// placement keeps failing with this villager around (it can sit on
				// the spot and never move out of the way); dispatch it and try
				// again with the next one instead of stopping the run
				placementFailRetries++;
				debug("ensure-lectern: placement blocked, dispatching villager (retry "
						+ placementFailRetries + ")");
				awaitNextVillager(client, s);
				return;
			}
			stop(result.reason(), ChatFormatting.RED);
		}
	}

	private static void tickWaitProfession(Minecraft client, RunSession s) {
		if (s.villager == null || !s.villager.isAlive()) {
			s.villager = findArrivedVillager(client, s.lecternPos);
			if (s.villager == null) {
				awaitNextVillager(client, s);
			} else {
				professionHealCycles = 0;
				urgentProfessionHeal = false;
			}
			return;
		}
		if (s.villager.getVillagerData().profession().is(RigTarget.professionKey())) {
			urgentProfessionHeal = false;
			checkLook.reset();
			enter(Phase.CHECK_TRADE);
			return;
		}
		// Not a librarian yet. Under heavy lag the stored reference can point at a
		// stale/recreated entity whose profession never updates: re-scan the arrival
		// point for a librarian and switch to it if one is there. Throttled like the
		// other waits — this ran an entity query EVERY tick for the whole wait
		int scanInterval = Math.max(1, TradeMasterConfig.INSTANCE.villagerCheckInterval);
		if (phaseTicks % scanInterval != 0) return;
		Villager found = findArrivedVillager(client, s.lecternPos);
		if (found != null && found != s.villager
				&& found.getVillagerData().profession().is(RigTarget.professionKey())) {
			debug("wait-profession: stale reference, switched to librarian at arrival point");
			s.villager = found;
			professionHealCycles = 0;
			return;
		}
		// Self-heal after 10s (or immediately when CHECK_TRADE falls back with
		// urgentProfessionHeal): if the lectern is missing re-place it; if it is
		// present but the villager still has no profession, break+re-place it to
		// drop any stale claim and force the villager to re-take the profession.
		// This is recovery, not a timeout, so it always runs regardless of
		// phaseTimeoutsEnabled.
		if (urgentProfessionHeal || phaseTicks > Math.max(20, TradeMasterConfig.INSTANCE.lecternHealGrace)) {
			urgentProfessionHeal = false;
			TradeMasterConfig healConfig = TradeMasterConfig.INSTANCE;
			if (healConfig.professionHealCapEnabled
					&& professionHealCycles >= Math.max(1, healConfig.professionHealCap)) {
				// repeated refreshes never produced a profession: this villager is
				// unusable (stuck out of lectern range, claimed elsewhere...), so
				// dispatch it and work with the next one instead of idling here
				debug("wait-profession: no profession after " + professionHealCycles
						+ " refresh cycles, dispatching villager");
				awaitNextVillager(client, s);
				return;
			}
			professionHealCycles++;
			boolean lecternThere = LecternRestock.isWorkBlockAt(client.level, s.lecternPos);
			debug("wait-profession: no profession (read="
					+ professionName(s.villager.getVillagerData().profession()) + ", lectern="
					+ (lecternThere ? "present" : "missing") + ", cycle " + professionHealCycles + ") -> "
					+ (lecternThere ? "refresh lectern" : "re-place lectern"));
			guardian = lecternThere ? LecternGuardian.refresh() : new LecternGuardian();
			enter(Phase.ENSURE_LECTERN);
		}
	}

	private static void tickCheckTrade(Minecraft client, LocalPlayer player, RunSession s) {
		// NOTE: every budget in this pipeline counts CLIENT TICKS, on purpose: the run
		// can only act on a tick, so a client-side stall freezes both. The one window
		// that must be wall-clock is the detection hub's (a packet arriving 3s after
		// the interact is not this check's answer) and it enforces that itself.
		if (phaseTimedOut(20 * 60)) {
			stop(Component.translatable("trademaster.message.timeout"), ChatFormatting.RED);
			return;
		}
		if (s.villager == null || !s.villager.isAlive()) {
			awaitNextVillager(client, s);
			return;
		}
		if (detectionPending) {
			// wait for the armed detection; if no offers arrive in time the
			// attempt is stale (villager not ready, menu didn't open...) —
			// disarm and re-interact instead of sitting on one click. This must
			// run before the profession check below: a transiently non-librarian
			// read would otherwise leave the hub armed until its own timeout.
			if (phaseTicks - detectionArmedTick > 3 * 20) {
				DetectionHub.disarm();
				DetectionHub.clearPostDisarmSuppression();
				detectionPending = false;
				closeMerchantMenuIfOpen(client);
			} else {
				return;
			}
		}
		if (!s.villager.getVillagerData().profession().is(RigTarget.professionKey())) {
			// After a reroll the villager has to re-claim the lectern; under lag
			// that can take a long time or fail outright. Waiting here used to
			// stall until the phase timeout killed the run — instead, allow a
			// grace period and then hand over to WAIT_PROFESSION, which heals
			// the lectern (break+re-place) and forces the villager to re-bind.
			int healGrace = Math.max(20, TradeMasterConfig.INSTANCE.lecternHealGrace);
			if (++nonLibrarianTicks > healGrace) {
				debug("check-trade: villager lost the librarian profession for "
						+ (healGrace / 20) + "s"
						+ " (read=" + professionName(s.villager.getVillagerData().profession())
						+ "), falling back to wait-profession");
				nonLibrarianTicks = 0;
				urgentProfessionHeal = true;
				interactFailTicks = 0;
				checkLook.reset();
				enter(Phase.WAIT_PROFESSION);
			}
			return;
		}
		nonLibrarianTicks = 0;

		// A villager that wanders away from the arrival point can no longer be
		// interacted with reliably; the phase used to retry silently until the
		// watchdog killed the run. Recover: take over another LIBRARIAN at the
		// arrival point if there is one, otherwise dispatch this villager and
		// summon a replacement.
		Vec3 arrival = arrivalPoint(client, s.lecternPos);
		if (s.villager.position().distanceToSqr(arrival) > AWAY_DISTANCE_SQR) {
			debug("check-trade: villager left the arrival point ("
					+ String.format("%.1f", Math.sqrt(s.villager.position().distanceToSqr(arrival)))
					+ " blocks away), recovering");
			DetectionHub.disarm();
			DetectionHub.clearPostDisarmSuppression();
			detectionPending = false;
			Villager replacement = findArrivedVillager(client, s.lecternPos);
			if (replacement != null && replacement != s.villager
					&& replacement.getVillagerData().profession().is(RigTarget.professionKey())) {
				// an EVENT-mode detection can have left the merchant menu open; the
				// replacement villager needs a fresh interact, so clear it
				closeMerchantMenuIfOpen(client);
				s.villager = replacement;
				professionHealCycles = 0;
				interactFailTicks = 0;
				checkLook.reset();
			} else {
				awaitNextVillager(client, s);
			}
			return;
		}

		// Like LTF: keep interacting until the trade offers were actually detected.
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		Vec3 eyes = s.villager.getEyePosition();
		if (!checkLook.tick(player, eyes)) return;

		if (config.slowMode) {
			if (slowInteractDelay > 0) {
				slowInteractDelay--;
				return;
			}
			slowInteractDelay = config.interactDelay;
		}

		if (interactVillager(client, s.villager)) {
			interactFailTicks = 0;
			detectionPending = true;
			detectionArmedTick = phaseTicks;
			DetectionMode mode = config.detectionMode;
			DetectionHub.arm(mode, mode == DetectionMode.PACKET, s.villager,
					RerollController::handleOffers, RerollController::handleDetectionFail);
		} else if (++interactFailTicks > 20 * 5) {
			// 5s of rejected interactions: the client thinks the villager is in
			// range but the server disagrees (stale position under lag) — stop
			// staring at it and get a fresh villager
			debug("check-trade: interactions rejected for 5s, recovering");
			interactFailTicks = 0;
			DetectionHub.disarm();
			DetectionHub.clearPostDisarmSuppression();
			detectionPending = false;
			awaitNextVillager(client, s);
		}
		// a single failed interact is normal mid-movement: keep retrying
	}

	private static void tickReroll(Minecraft client, RunSession s) {
		if (strategy == null) {
			strategy = TradeMasterConfig.INSTANCE.rerollMechanism == RerollMechanism.TASK_CHAIN
					? new TaskChainRerollStrategy()
					: new AxeRerollStrategy();
			strategy.begin(s);
			return;
		}
		RerollStrategy.StepResult result = strategy.tick();
		if (result.step() == RerollStrategy.Step.DONE) {
			strategy.abort();
			strategy = null;
			checkLook.reset();
			slowInteractDelay = 0;
			enter(Phase.CHECK_TRADE);
		} else if (result.step() == RerollStrategy.Step.FAILED
				|| result.step() == RerollStrategy.Step.FAILED_FULL) {
			if (s.villager == null || !s.villager.isAlive()) {
				awaitNextVillager(client, s);
			} else if (strategy.placementBlocked() && placementFailRetries < 2) {
				// placement keeps failing with this villager around (it can sit on
				// the lectern spot); dispatch it and retry with the next one,
				// same recovery as the guardian ENSURE path
				placementFailRetries++;
				debug("reroll: placement blocked, dispatching villager (retry "
						+ placementFailRetries + ")");
				awaitNextVillager(client, s);
			} else {
				stop(result.reason(), ChatFormatting.RED);
			}
		}
	}

	private static void tickLock(Minecraft client, LocalPlayer player, RunSession s) {
		if (lockExhaustionDepositPending) {
			finishLockExhaustion(client, player);
			return;
		}
		if (s.matchedTrade != null) {
			// a mid-run maxPrice drop or level raise leaves the pending match outside
			// the edited budget. Measured with the SAME ruler tryMatch admitted with:
			// hunt-marked targets contribute mark-1 (not maxPrice), and a match
			// admitted via a recorded combo need is only measured against that
			// record — comparing either against the raw config sum would
			// revert-reloop against a match tryMatch keeps re-admitting
			boolean editedOut = s.matchedTrade.anyTargetGone()
					|| s.matchedTrade.anyPartBelowConfigLevel()
					|| priceOutOfAdmissionBudget(s.matchedTrade);
			if (editedOut) {
				if (lockPurchaseInitiated && lockExecutor != null && lockExecutor.purchaseSent()) {
					// the click is already out, so the villager may be locked even though we
					// cannot see it yet. The executor's remaining stages decide the outcome
					// from the INVENTORY (not the menu), so tick it to a verdict: a menu the
					// server closed is NOT a rejection, and reverting here would re-demand
					// the target and then reroll a villager that can no longer reroll.
					switch (lockExecutor.tick()) {
						case DONE -> {
							debug("lock: matched target was disabled mid-run, but the purchase succeeded; finishing it");
							finishLock(s);
						}
						case FAILED -> {
							if (lockExecutor.uncertain() || lockExecutor.drifted()) {
								// a book may exist even though we could not prove it: do not
								// undo the match (that would re-demand the target) — dispatch
								// this villager instead, like the main failure path
								boolean unconfirmed = lockExecutor.uncertain();
								debug("lock: matched target disabled mid-run, click sent, outcome "
										+ (unconfirmed ? "unconfirmed" : "drifted") + " — dispatching");
								notice(Component.translatable(unconfirmed
										? "trademaster.message.lock_unconfirmed"
										: "trademaster.message.lock_drift_purchased"));
								// voucher the attempted target first: the rebuild re-enqueues
								// every enabled target, and re-demanding this one would buy a
								// SECOND copy (see handleLockFailure)
								voucherUnprovenPurchase(s);
								// the edit may have emptied the matcher, and the tick-top check
								// is a one-shot on the fingerprint (suppressed while
								// finalizing): finish the run here instead of parking in
								// AWAIT_VILLAGER with nothing left to roll for
								refreshNeeds(client, s);
								if (s.matcher.isEmpty()) {
									stop(Component.translatable("trademaster.message.all_done"), ChatFormatting.GREEN);
									return;
								}
								awaitNextVillager(client, s);
							} else {
								// nothing was bought (the cursor check refused the click): the
								// config edit stands — give the budget back and reroll
								revertDisabledMatch(client, s);
							}
						}
						case RUNNING -> {}
					}
					return;
				}
				// disabled before the purchase actually went out: discard the pending
				// executor (it would otherwise buy on the next tick), give the budget
				// back and reroll — the villager is still unlocked
				revertDisabledMatch(client, s);
				return;
			}
		}
		if (!lockSlotPrepared) {
			if (!Preconditions.hasFreeSlot(player) || depositor != null) {
				if (depositor == null) {
					depositor = createBookDisposal();
				}
				if (depositor != null) {
					// keep ticking to a terminal state even after the first book frees
					// a slot: abandoning a half-spent depositor (open chest menu,
					// switched hotbar slot) leaks it into later phases
					RerollStrategy.StepResult result = depositor.tick();
					if (result.step() == RerollStrategy.Step.FAILED) {
						stop(result.reason(), ChatFormatting.RED);
						return;
					}
					if (result.step() == RerollStrategy.Step.RUNNING) {
						// draining several chests can exceed the 20s LOCK timeout;
						// keep the clock fresh so a half-spent depositor is not abandoned
						phaseTicks = 0;
						return;
					}
					// DONE or FAILED_FULL: either way the depositor is spent. FAILED_FULL
					// skips its own end-of-run restore, so clean it up here or the item
					// a staging swap displaced stays in the offhand
					depositor.abortCleanly();
					depositor = null;
					phaseTicks = 0;
				}
				if (!Preconditions.hasFreeSlot(player)) {
					// no room for the book the lock purchase would yield: it would
					// be dropped on the ground (possibly into the void). Stop before
					// locking and leave the villager in place — once the player frees
					// a slot the run resumes and re-locks the same villager
					stop(Component.translatable("trademaster.message.inventory_full"), ChatFormatting.RED);
					return;
				}
			}
			lockSlotPrepared = true;
		}
		if (phaseTimedOut(20 * 20)) {
			stop(Component.translatable("trademaster.message.timeout"), ChatFormatting.RED);
			return;
		}
		if (s.villager == null || !s.villager.isAlive()) {
			// judge by the executor's actual state: lockPurchaseInitiated is set at
			// executor construction, before any click went out. Once the click IS out
			// the villager vanishing must not end the judging — the server still
			// completes (or refuses) the trade, and only its verdict may decide.
			// Finishing on the click alone could celebrate a trade that never happened;
			// before the click, the villager simply vanished: restore and wait
			if (lockExecutor != null && lockExecutor.purchaseSent()) {
				switch (lockExecutor.tick()) {
					case DONE -> finishLock(s);
					case FAILED -> {
						// the click is out and the outcome is unknown: voucher the attempted
						// target, or the next villager buys a SECOND copy (same rule as
						// handleLockFailure). The voucher only takes effect once the needs are
						// rebuilt (dropLocked), and awaitNextVillager does NOT rebuild — so do it
						// here. Without the rebuild the target stays matchable (with
						// reserveCheaperTraders it even keeps a price-1 slot) and it is re-bought.
						boolean unproven = lockExecutor.uncertain() || lockExecutor.drifted();
						if (unproven) voucherUnprovenPurchase(s);
						if (unproven) refreshNeeds(client, s);
						awaitNextVillager(client, s);
					}
					case RUNNING -> {}
				}
			} else {
				awaitNextVillager(client, s);
			}
			return;
		}
		if (!(player.containerMenu instanceof MerchantMenu menu)) {
			if (lockExecutor != null) {
				// the executor closes the menu in its second-to-last stage and reports
				// DONE one tick later; keep ticking it here or the lock never completes
				switch (lockExecutor.tick()) {
					case DONE -> finishLock(s);
					case FAILED -> handleLockFailure(client, s);
					case RUNNING -> {}
				}
				return;
			}
			if (lockPurchaseInitiated) {
				finishLock(s);
				return;
			}
			// a leftover non-merchant menu (e.g. anvil from naming) blocks the
			// villager interaction; clear it before retrying
			if (player.containerMenu != player.inventoryMenu) {
				player.closeContainer();
				return;
			}
			if (!lockInteracted) {
				// same pacing as CHECK_TRADE: slow mode spaces interacts by the
				// configured delay, normal mode retries every 5 ticks
				boolean tryInteract = true;
				TradeMasterConfig paceConfig = TradeMasterConfig.INSTANCE;
				if (paceConfig.slowMode) {
					if (slowInteractDelay > 0) {
						slowInteractDelay--;
						return;
					}
					slowInteractDelay = paceConfig.interactDelay;
				} else {
					tryInteract = phaseTicks % 5 == 1;
				}
				if (tryInteract && interactVillager(client, s.villager)) {
					lockInteracted = true;
					lockNoMenuTicks = 0;
				}
			} else {
				// the interact landed client-side but no menu opened (position desync
				// under lag): keep retrying forever, 5s apart — the only bounds are
				// the global watchdog and the freshness check once the menu opens
				if (++lockNoMenuTicks > 20 * 5) {
					lockNoMenuTicks = 0;
					lockInteracted = false;
				}
			}
			return;
		}

		if (lockExecutor == null) {
			// Defensive, unreachable today (audited 2026-09-19): the flag is set in the
			// same statement pair that builds the executor (below), and every site
			// that drops the executor also clears the flag.
			if (lockPurchaseInitiated) {
				finishLock(s);
				return;
			}
			MerchantOffers offers = menu.getOffers();
			if (offers.isEmpty()) return; // offers packet not applied yet
			if (s.matchedTrade != null && !matchedOfferUnchanged(offers, s.matchedTrade)) {
				// the offer recorded at detection is gone or drifted (profession
				// auto-reroll regenerated the menu: same enchantment at a higher
				// price, a different book at the old slot). The villager is NOT
				// locked, so this counts as a free reroll — put the budget back
				// and re-detect the fresh offers on the same villager under the
				// proper budget instead of locking blindly or sending it away
				// (a mid-run target disable is already handled at the top of this
				// method before we ever get here)
				freeReroll(client, s);
				return;
			}
			int slot = resolveLockSlot(offers, s);
			if (slot < 0) {
				// nothing on the list can be paid for at all: name what the matched offer
				// asks for when we can point at it, otherwise stay generic
				Component reason = Component.translatable("trademaster.message.material_short");
				if (s.matchedTrade != null && s.matchedTrade.offerIndex() < offers.size()) {
					Component lack = Preconditions.shortfall(player,
							OfferCosts.slots(offers.get(s.matchedTrade.offerIndex())));
					if (lack != null) reason = lack;
				}
				stop(reason, ChatFormatting.RED);
				return;
			}
			MerchantOffer offer = offers.get(slot);
			if (!Preconditions.checkLockMaterials(player, OfferCosts.slots(offer))) {
				// the matched offer became unaffordable (emeralds spent manually):
				// the villager is still unlocked, so treat it like a drift — give the
				// budget back and re-detect. The affordability gate in handleOffers
				// keeps this same book from matching again; either another qualifying
				// book takes over or the villager gets rerolled. (The old fallback of
				// buying some other book could never pass the purchase fingerprint and
				// only reignited the re-detect loop.)
				freeReroll(client, s);
				return;
			}
			if (lockRetryDelayTicks > 0) {
				// spacing after a rejected purchase: do not hammer the server
				lockRetryDelayTicks--;
				return;
			}
			// the fingerprint must describe the offer this slot actually holds: for an
			// emergency buy (the matched book is unaffordable) it is built from that
			// offer, otherwise a stale matched-trade fingerprint drifts and livelocks
			boolean buysMatched = s.matchedTrade != null && slot == s.matchedTrade.offerIndex();
			TradeMatcher.MatchedTrade expectation = buysMatched ? s.matchedTrade
					: new TradeMatcher.MatchedTrade(List.of(bookFingerprint(offer, slot)), List.of(),
							s.matchedTrade == null ? 0 : s.matchedTrade.needsVersion());
			lockExecutor = new LockTradeExecutor(menu, slot, expectation);
			lockPurchaseInitiated = true;
		}

		// closing() = WE closed it, and the stage-3 server confirmation is still
		// running: only a menu that vanished before that is the server's doing
		if (lockExecutor != null && lockExecutor.menu() != player.containerMenu && !lockExecutor.closing()) {
			// the server closed or replaced the menu mid-execution. A click already sent
			// must NOT be re-clicked (a librarian offer still has uses, so a retry buys a
			// SECOND book), and the executor's later stages prove the purchase from the
			// INVENTORY, not the menu — so keep ticking it to a verdict instead of
			// discarding it as a rejection.
			if (!lockExecutor.purchaseSent()) {
				lockExecutor = null;
				lockInteracted = false;
				lockRejected(client, s);
				return;
			}
			// purchaseSent(): fall through and let the switch below tick it
		}

		switch (lockExecutor.tick()) {
			case DONE -> finishLock(s);
			case FAILED -> handleLockFailure(client, s);
			case RUNNING -> {}
		}
	}

	/** Routes a failed lock executor by WHY it failed. Only a failure with NOTHING
	 * clicked (or a drift detected before the click) may retry: a click is applied
	 * server-side the moment it is sent, so re-clicking a sent-but-unconfirmed purchase
	 * buys a SECOND book. Unsent-but-drifted -> free reroll; sent -> dispatch the villager
	 * without re-buying. */
	private static void handleLockFailure(Minecraft client, RunSession s) {
		if (lockExecutor == null) return;
		if (!lockExecutor.drifted() && !lockExecutor.uncertain()) {
			// nothing was clicked, or the click was cleanly refused at the cursor check
			lockRejected(client, s);
		} else if (lockExecutor.purchaseSent()) {
			boolean unconfirmed = lockExecutor.uncertain();
			debug("lock: click sent, outcome " + (unconfirmed ? "unconfirmed" : "drifted")
					+ " — dispatching the villager instead of re-buying");
			notice(Component.translatable(unconfirmed
					? "trademaster.message.lock_unconfirmed"
					: "trademaster.message.lock_drift_purchased"));
			// the click is out and the outcome is unknown: the target must not be re-demanded,
			// or the next villager buys a SECOND copy. The needs rebuild re-enqueues every
			// still-enabled target, so voucher the attempted ones as obtained for this run first
			// (no auto-disable, no hunt mark: the purchase is not proven).
			voucherUnprovenPurchase(s);
			// the rebuild also finishes a run whose targets were all disabled in the meantime,
			// like the edited-out path does
			refreshNeeds(client, s);
			if (s.matcher.isEmpty()) {
				stop(Component.translatable("trademaster.message.all_done"), ChatFormatting.GREEN);
				return;
			}
			awaitNextVillager(client, s);
		} else {
			// a drift detected BEFORE the click: nothing was bought, still rerollable
			freeReroll(client, s);
		}
	}

	/**
	 * The matched target was disabled, removed or edited out of budget mid-run
	 * and the purchase is not (or no longer) viable: give the budget back and
	 * reroll. Needs are rebuilt from the live config — the matcher snapshot was
	 * taken at start(), and without the rebuild the stale enchantment would
	 * keep matching and the run would loop revert-reroll forever.
	 */
	private static void revertDisabledMatch(Minecraft client, RunSession s) {
		debug("lock: matched target was disabled or edited mid-run, reverting match and rerolling");
		lockExecutor = null;
		lockPurchaseInitiated = false;
		lockInteracted = false;
		// a deposit may be mid-flight to free a slot for a lock that is now
		// cancelled: drop it here, otherwise the half-spent depositor leaks
		// into the next LOCK/naming run — but let it close its open chest and
		// restore the hotbar selection first
		if (depositor != null) {
			depositor.abortCleanly();
			depositor = null;
		}
		s.matcher.undoMatch(s.matchedTrade);
		s.matchedTrade = null;
		closeMerchantMenuIfOpen(client);
		refreshNeeds(client, s);
		if (s.matcher.isEmpty()) {
			stop(Component.translatable("trademaster.message.all_done"), ChatFormatting.GREEN);
			return;
		}
		enter(Phase.REROLL);
	}

	/**
	 * The lock purchase did not complete (server rejected it or the menu went
	 * stale mid-execution): the villager stays unlocked and the trade intact.
	 * Retry after a delay; once the configured retry cap (lockMaxRetries,
	 * -1 = unlimited) is exceeded, undo the match-time state changes and keep
	 * rerolling the same villager — it was never locked, so its trades can
	 * still be rerolled.
	 */
	private static void lockRejected(Minecraft client, RunSession s) {
		debug("lock: purchase rejected by the server, retrying");
		lockExecutor = null;
		lockPurchaseInitiated = false;
		// the server often closes the menu on rejection; stale interact state would
		// send the menu-less branch into its 100-tick wait counter before retrying
		lockInteracted = false;
		lockSlotPrepared = false;
		lockNoMenuTicks = 0;
		lockAttempts++;
		int maxRetries = TradeMasterConfig.INSTANCE.lockMaxRetries;
		if (maxRetries >= 0 && lockAttempts > maxRetries) {
			notice(Component.translatable("trademaster.message.lock_retry_exhausted", lockAttempts));
			if (s.matchedTrade != null) {
				// same guard as awaitNextVillager: undoMatch's computeIfAbsent would
				// resurrect a locked target that dropLocked removed after hunt was
				// turned off mid-run; hunt-marked targets stay live and keep the undo
				if (!matchLockedDone(s)) {
					s.matcher.undoMatch(s.matchedTrade);
				}
				s.matchedTrade = null;
			}
			closeMerchantMenuIfOpen(client);
			sendAwayPresser = null;
			if (client.player != null && !Preconditions.hasFreeSlot(client.player)) {
				// stash enchanted books to free a slot before rerolling on
				depositor = createBookDisposal();
				if (depositor == null) {
					stop(Component.translatable("trademaster.message.inventory_full"), ChatFormatting.RED);
					return;
				}
				lockExhaustionDepositPending = true;
				return;
			}
			resumeAfterLockExhaustion(client);
			return;
		}
		lockRetryDelayTicks = 40;
		phaseTicks = 0;
	}

	/**
	 * Completes the post-exhaustion flow when the inventory was full: the
	 * depositor stashes enchanted books one per tick, then the freed slot is
	 * rechecked and rerolling resumes. Runs at the top of tickLock so the
	 * pending state can never fall through into villager interaction.
	 */
	private static void finishLockExhaustion(Minecraft client, LocalPlayer player) {
		// Defensive, unreachable today (audited 2026-09-19): the flag is only set
		// together with the depositor in lockRejected.
		if (depositor != null) {
			RerollStrategy.StepResult result = depositor.tick();
			if (result.step() == RerollStrategy.Step.FAILED) {
				stop(result.reason(), ChatFormatting.RED);
				return;
			}
			if (result.step() == RerollStrategy.Step.RUNNING) return;
			// DONE or FAILED_FULL: the recheck below decides whether to resume
			depositor.abortCleanly();
			depositor = null;
		}
		lockExhaustionDepositPending = false;
		if (!Preconditions.hasFreeSlot(player)) {
			stop(Component.translatable("trademaster.message.inventory_full"), ChatFormatting.RED);
			return;
		}
		resumeAfterLockExhaustion(client);
	}

	private static void resumeAfterLockExhaustion(Minecraft client) {
		Component materials = Preconditions.check();
		if (materials != null) {
			stop(materials.copy().withStyle(ChatFormatting.RED), null);
			return;
		}
		lockAttempts = 0;
		enter(Phase.REROLL);
	}

	/** axe_required / axe_breaking / tool_* never self-resolve: retrying them is a silent
	 * hang. tool_cannot_harvest is included: no retry can conjure the missing tool. */
	private static boolean isPermanentAxeFailure(@Nullable Component reason) {
		if (reason == null) return false;
		if (!(reason.getContents() instanceof TranslatableContents contents)) return false;
		String key = contents.getKey();
		return key.equals("trademaster.message.axe_required") || key.equals("trademaster.message.axe_breaking")
				|| key.equals("trademaster.message.tool_required") || key.equals("trademaster.message.tool_breaking")
				|| key.equals("trademaster.message.tool_cannot_harvest");
	}

	/** Vouchers an UNPROVEN purchase (a click was sent, the outcome is unknown) for the rest of
	 * this run: the attempted targets must not be re-demanded, or the next villager buys a
	 * SECOND copy. No auto-disable and no hunt mark (the purchase is not proven), but the
	 * expected result IS remembered so an actually-delivered item can still be disposed of. */
	private static void voucherUnprovenPurchase(RunSession s) {
		if (s.matchedTrade == null) return;
		s.lockedIds.addAll(s.matchedTrade.matchedIds());
		rememberProduced(s.lockedResults, s.matchedTrade.purchase().result());
		voucherEmergencyBook(s, lockExecutor == null ? null : lockExecutor.expected());
	}

	/** An EMERGENCY buy purchases a DIFFERENT book than the matched one (the matched book
	 * was unaffordable). That book's own target(s) are physically satisfied by the purchase,
	 * but they are not in {@code matchedTrade.matchedIds()} — without vouching them the needs
	 * rebuild re-demands that target and a later villager sells a SECOND copy. Only targets
	 * the bought book actually FULFILS are vouched (its level must meet the configured level),
	 * so a below-level copy cannot mask a genuinely-needed higher-level one. */
	private static void voucherEmergencyBook(RunSession s, TradeMatcher.@Nullable MatchedTrade bought) {
		if (bought == null || bought == s.matchedTrade) return;
		s.lockedIds.addAll(fulfilledTargetIds(bought));
		rememberProduced(s.lockedResults, bought.purchase().result());
	}

	/** Ids of a bought book that its OWN purchase fulfils: an enabled enchantment target
	 * whose configured level the bought part meets. */
	static List<Identifier> fulfilledTargetIds(TradeMatcher.MatchedTrade bought) {
		List<Identifier> ids = new ArrayList<>();
		for (TradeMatcher.MatchedBook book : bought.books()) {
			for (TradeMatcher.MatchPart part : book.parts()) {
				if (ids.contains(part.id())) continue;
				EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(part.id());
				if (target != null && target.enabled() && part.level() >= target.level()) {
					ids.add(part.id());
				}
			}
		}
		return ids;
	}

	/** Remembers a distinct produced stack (item AND components) so the disposal only ever
	 * takes a stack that IS one of this run's outputs. */
	private static void rememberProduced(
			Map<Item, java.util.List<net.minecraft.world.item.ItemStack>> into,
			@Nullable net.minecraft.world.item.ItemStack produced) {
		if (produced == null || produced.isEmpty()) return;
		java.util.List<net.minecraft.world.item.ItemStack> known =
				into.computeIfAbsent(produced.getItem(), k -> new java.util.ArrayList<>());
		for (net.minecraft.world.item.ItemStack prev : known) {
			if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(prev, produced)) return;
		}
		known.add(produced.copy());
	}

	private static void finishLock(RunSession s) {
		// capture the actually-bought trade BEFORE the executor is dropped: an emergency buy
		// fingers a different, affordable book and its targets need vouching too (below)
		TradeMatcher.MatchedTrade bought = lockExecutor == null ? null : lockExecutor.expected();
		lockExecutor = null;
		lockInteracted = false;
		// session-level voucher: survives the needs rebuild even when the villager
		// leaves unnamed and auto-disable is off (otherwise the same target would
		// be re-demanded and locked endlessly). Every matched target of the combo
		// is covered by this one villager, so all of them count as locked
		// Defensive, unreachable today (audited 2026-09-19): finishLock only runs in
		// LOCK, which is entered once with a non-null match. Guard kept against NPE.
		if (s.matchedTrade != null) {
			s.lockedIds.addAll(s.matchedTrade.matchedIds());
			// remember every distinct stack this trade yields (item AND components), so the
			// disposal only ever takes a stack that IS one of this run's outputs
			rememberProduced(s.lockedResults, s.matchedTrade.purchase().result());
			// hunt-cheaper: the locked enchantment stays live; mark its price so
			// future matches only accept a strictly cheaper copy. Only a purchase
			// with exactly one matched part has a price attributable to one target —
			// a combo's price cannot seed a per-target ratchet
			if (TradeMasterConfig.INSTANCE.huntCheaper && s.matchedTrade.purchase().parts().size() == 1) {
				// The hunt mark only ever moves DOWN: it records the price actually
				// paid, and only when that is strictly cheaper than the current mark.
				// (The old rule fell back to the CONFIG price when that had been
				// lowered below the mark, leaving the "cheaper only" slot one above
				// the price just paid — so after a mid-run maxPrice drop the very same
				// price could match again and be bought twice.)
				int paid = s.matchedTrade.price();
				Integer prevMark = huntMarkedPrices.get(s.matchedTrade.id());
				if (prevMark == null || paid < prevMark) {
					huntMarkedPrices.put(s.matchedTrade.id(), paid);
				}
			} else if (TradeMasterConfig.INSTANCE.huntCheaper && s.matchedTrade.purchase().parts().size() >= 2) {
				// the purchased book carries several matched enchantments: its price
				// cannot be attributed, so record the combo itself as a temporary
				// need (qualifying set + price). An identical set only matches
				// strictly cheaper afterwards (combo ratchet); a book with a
				// different-but-qualifying set records its own entry when purchased
				TradeMatcher.MatchedBook purchased = s.matchedTrade.purchase();
				Set<TradeMatcher.MatchPart> key = TradeMatcher.comboKey(purchased.parts());
				Integer prev = comboHuntPrices.get(key);
				if (prev == null || purchased.price() < prev) {
					comboHuntPrices.put(key, purchased.price());
					debug("hunt: combo need recorded " + key + " at " + purchased.price());
					chat(Component.translatable("trademaster.message.combo_need_recorded",
									describeBook(purchased), purchased.price())
							.withStyle(ChatFormatting.YELLOW));
				}
			}
			// every OTHER qualifying book of this villager counts as obtained too
			// (one purchase locks it), so each additional single-target book must
			// contribute its own offered price — otherwise a second qualifying book's
			// cheaper-hunt was silently dropped. Combos are skipped: their price
			// cannot seed a per-target ratchet (they go through comboHuntPrices).
			if (TradeMasterConfig.INSTANCE.huntCheaper) {
				int purchasedIndex = s.matchedTrade.offerIndex();
				for (TradeMatcher.MatchedBook book : s.matchedTrade.books()) {
					if (book.parts().size() != 1 || book.offerIndex() == purchasedIndex) continue;
					Identifier id = book.parts().get(0).id();
					Integer prevMark = huntMarkedPrices.get(id);
					if (prevMark == null || book.price() < prevMark) {
						huntMarkedPrices.put(id, book.price());
					}
				}
			}
			// an emergency buy bought a DIFFERENT (affordable) book: voucher the targets THAT
			// book fulfils too, or the needs rebuild re-demands them and a later villager sells
			// a second copy (see voucherEmergencyBook)
			voucherEmergencyBook(s, bought);
		}
		// an EVENT-mode detection can leave the merchant menu open; naming needs to
		// open an anvil, so clear it out first
		closeMerchantMenuIfOpen(Minecraft.getInstance());
		TradeMasterConfig lockConfig = TradeMasterConfig.INSTANCE;
		if (lockConfig.autoDisableAfterLock && s.matchedTrade != null) {
			boolean changed = false;
			// the matched targets, plus (for an emergency buy) the DIFFERENT book's fulfilled
			// targets: both were vouched into lockedIds, so both must be disabled or the next
			// run re-farms the emergency book's target (see voucherEmergencyBook)
			List<Identifier> toDisable = new ArrayList<>(s.matchedTrade.matchedIds());
			if (bought != null && bought != s.matchedTrade) {
				toDisable.addAll(fulfilledTargetIds(bought));
			}
			for (Identifier id : toDisable) {
				EnchantmentTarget target = lockConfig.enchantments.get(id);
				if (target != null && target.enabled()) {
					lockConfig.enchantments.put(id, target.withEnabled(false));
					changed = true;
					continue;
				}
				// a custom good is switched off in its own list
				GoodSpec good = lockConfig.goodFor(id);
				if (good != null && good.enabled()) {
					lockConfig.goods.set(lockConfig.goods.indexOf(good), good.withEnabled(false));
					changed = true;
				}
			}
			if (changed) lockConfig.save();
		}
		// the tail obeys the same guard as the body: with no matched trade there is
		// nothing to name, and NamingExecutor would NPE on trade.books()
		if (s.matchedTrade != null && lockConfig.namingEnabled) {
			namingExecutor = null;
			enter(Phase.NAME);
		} else {
			sendAwayPresser = null;
			enter(Phase.SEND_AWAY);
		}
	}

	private static void tickName(Minecraft client, LocalPlayer player, RunSession s) {
		if (namingExecutor == null) {
			// a fresh tag costs one level: below that, naming is temporarily turned
			// off — the book stays locked, the villager goes away unnamed, and the
			// next villager is named again once XP is back (no manual switch needed)
			if (!player.hasInfiniteMaterials() && player.experienceLevel < 1) {
				if (!xpNamingPaused) {
					xpNamingPaused = true;
					chat(Component.translatable("trademaster.message.xp_short").withStyle(ChatFormatting.YELLOW));
				}
				closeMerchantMenuIfOpen(client);
				sendAwayPresser = null;
				enter(Phase.SEND_AWAY);
				return;
			}
			xpNamingPaused = false;
			// The renamed name tag needs a free slot, but right after buying the book
			// the inventory is often full. Deposit books into the bound chests first to
			// make room, instead of failing at the anvil take step (take-tag).
			if (!Preconditions.hasFreeSlot(player) || depositor != null) {
				TradeMasterConfig config = TradeMasterConfig.INSTANCE;
				if (depositor == null) {
					debug("naming: inventory full, disposing books to free a slot");
					depositor = createBookDisposal();
				}
				if (depositor != null) {
					// as in tickLock: run to a terminal state, never abandon mid-run
					RerollStrategy.StepResult result = depositor.tick();
					if (result.step() == RerollStrategy.Step.RUNNING) return;
					if (result.step() == RerollStrategy.Step.FAILED) {
						// a hard deposit failure (swap rejected after its retries,
						// phase timeout): stop like the other deposit sites do.
						// FAILED_FULL stays lenient — the hasFreeSlot recheck below
						// decides whether naming can still proceed
						stop(result.reason(), ChatFormatting.RED);
						return;
					}
					if (result.step() != RerollStrategy.Step.DONE) {
						debug("naming: deposit ended early: " + result.reason().getString());
					}
					depositor.abortCleanly();
					depositor = null;
					phaseTicks = 0;
				}
				if (!Preconditions.hasFreeSlot(player)) {
					// could not free a slot (no chests / chests full / nothing to deposit):
					// naming would fail, so skip it and keep the already-locked book
					debug("naming: could not free a slot (chests bound="
							+ config.chestPositions.size() + "), skipping naming");
					closeMerchantMenuIfOpen(client);
					notice(Component.translatable("trademaster.message.naming_skipped",
							Component.translatable("trademaster.naming_skip.inventory_full")));
					sendAwayPresser = null;
					enter(Phase.SEND_AWAY);
					return;
				}
				debug("naming: slot freed, proceeding");
			}
			namingExecutor = new NamingExecutor(s, s.matchedTrade);
			phaseTicks = 0; // the naming timeout starts when the executor actually begins
			return;
		}
		switch (namingExecutor.tick()) {
			case DONE -> {
				recordNameHistory(namingExecutor.nameRecords());
				namingExecutor = null;
				SmoothRotation.reset();
				closeMerchantMenuIfOpen(Minecraft.getInstance());
				sendAwayPresser = null;
				enter(Phase.SEND_AWAY);
			}
			case FAILED -> {
				String reason = namingExecutor.failReason();
				// the APPLY interact was already sent: the villager may carry the name
				// despite the failure (verify-timeout and the like). Record it, or the
				// next session finds no ledger entry and buys the same enchantment again
				if (namingExecutor.hasSentTag()) recordNameHistory(namingExecutor.nameRecords());
				// same cleanup as the timeout backstop: fire the pending EQUIP
				// swap-back and close the anvil, or a failed rename strands the
				// displaced hand item AND leaves the anvil open, wedging every
				// later phase (bringToolToHand never leaves WORKING)
				namingExecutor.abortCleanly();
				namingExecutor = null;
				SmoothRotation.reset();
				closeMerchantMenuIfOpen(Minecraft.getInstance());
				debug("naming failed: " + reason);
				notice(Component.translatable("trademaster.message.naming_skipped",
						Component.translatable("trademaster.naming_skip.failed",
								NamingExecutor.describeFailure(reason))));
				sendAwayPresser = null;
				enter(Phase.SEND_AWAY);
			}
			case RUNNING -> {}
		}
		// Checked AFTER the tick: a failure landing exactly on the timeout tick must
		// get its chance to divert into RESTORE before being discarded. This is a
		// phase-timeout backstop, so it honours phaseTimeoutsEnabled — with the switch
		// OFF the user wants no timeouts at all, wedging included
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled
				&& namingExecutor != null && phaseTicks > 20 * 30 && !namingExecutor.isRestoring()) {
			// the interact may already have been sent (the executor wedged in APPLY or
			// VERIFY): record the names before the discard, same reason as FAILED
			if (namingExecutor.hasSentTag()) recordNameHistory(namingExecutor.nameRecords());
			// abortCleanly fires the pending EQUIP swap-back if one is outstanding;
			// a bare discard would strand the displaced hand item in the tag slot
			namingExecutor.abortCleanly();
			namingExecutor = null;
			closeMerchantMenuIfOpen(Minecraft.getInstance());
			notice(Component.translatable("trademaster.message.naming_skipped",
					Component.translatable("trademaster.naming_skip.timeout")));
			sendAwayPresser = null;
			enter(Phase.SEND_AWAY);
		}
	}

	private static void tickSendAway(Minecraft client, RunSession s) {
		if (sendAwayPresser == null) sendAwayPresser = new ButtonPresser(s.sendAwayButtonPos);
		RerollStrategy.StepResult result = sendAwayPresser.tick();
		if (result.step() == RerollStrategy.Step.DONE) {
			sendAwayPresser = null;
			s.villager = null;
			s.matchedTrade = null;
			lockExecutor = null;
			lockPurchaseInitiated = false;
			namingExecutor = null;
			guardian = null;
			if (restSummonPending) {
				// villager-gone cleanup: rest now, summon once a villager reappears
				debug("send-away: rig cleared with no lock, resting for next villager");
				awaitingRigClear = true;
				enter(Phase.AWAIT_VILLAGER);
				return;
			}
			// a locked villager was just dispatched: refresh the needs list the
			// LPCTools way (named villagers count as already obtained), then rest
			// until the rig is clear instead of immediately re-scanning — the
			// departing villager is still a client entity while it falls and would
			// otherwise be re-targeted, looping the run forever
			refreshNeeds(client, s);
			if (s.matcher.isEmpty()) {
				stop(Component.translatable("trademaster.message.all_done"), ChatFormatting.GREEN);
				return;
			}
			s.tries = 0;
			// the sent-away villager keeps the lectern claimed until it is broken, so
			// break it right now to release the claim. Do NOT re-place it yet: leaving
			// the spot empty until the next villager actually arrives prevents the
			// still-falling/passing villager from grabbing it and failing to re-roll.
			// The arrival path (ENSURE_LECTERN) re-places it when a villager shows up.
			debug("send-away: locked villager dispatched, breaking lectern (no re-place yet)");
			guardian = LecternGuardian.breakOnly();
			postDispatchRefresh = true;
			postDispatchBreakRetriesSpent = 0;
			enter(Phase.ENSURE_LECTERN);
		} else if (result.step() == RerollStrategy.Step.FAILED) {
			stop(result.reason(), ChatFormatting.RED);
		}
	}

	// ---- detection callbacks ---------------------------------------------------

	private static void handleOffers(OfferSnapshot snapshot) {
		detectionPending = false;
		RunSession s = session;
		if (s == null || phase != Phase.CHECK_TRADE) return;

		TradeMatcher.MatchedTrade hit = s.matcher.tryMatch(snapshot.offers());

		// the match budget is the config's, not the player's: a combo's price can
		// exceed the carried emeralds even though every single target fits its own
		// max price. An unaffordable match must not enter LOCK — the purchase would
		// fail the material check and livelock against the reroll loop — so treat
		// it as a miss and reroll; the miss branch's own material precheck stops
		// the run once the emeralds fall below the floor
		if (hit != null) {
			LocalPlayer player = Minecraft.getInstance().player;
			MerchantOffer matchedOffer = snapshot.offers().get(hit.offerIndex());
			boolean payable = player != null
					&& Preconditions.checkLockMaterials(player, OfferCosts.slots(matchedOffer));
			if (!payable) {
				// The matched book cannot be paid for. Locking the villager is what the
				// run is after and a single purchase does it, so buy the cheapest OTHER
				// book that IS affordable; when nothing on the list is affordable the
				// run stops instead of rerolling into the same unaffordable match.
				int alt = player == null ? -1 : cheapestAffordableBookSlot(snapshot.offers(), player);
				if (alt >= 0) {
					MerchantOffer altOffer = snapshot.offers().get(alt);
					debug("offers: matched " + hit.price() + " emeralds, unaffordable; emergency buy at slot " + alt);
					chat(Component.translatable("trademaster.message.emergency_purchase",
									describeBook(bookFingerprint(altOffer, alt)), altOffer.getBaseCostA().getCount())
							.withStyle(ChatFormatting.YELLOW));
					// keep the match: LOCK re-picks the affordable slot and fingerprints the
					// offer it actually buys, while locked ids / auto-disable / hunt marks still
					// follow the matched targets. That is deliberate: the run's goal is a
					// LOCKED villager, and a locked villager keeps selling what the matched
					// target wanted (its offers do not disappear), so locking it via another of
					// its books reaches the same end state — the matched book stays buyable
				} else {
					debug("offers: matched " + hit.price() + " emeralds and nothing is affordable, stopping");
					// a combo's price is the sum of its parts and can exceed an emerald
					// floor that only knows single-target max prices — only seeing the
					// offer reveals it, so name the switch that would have covered it
					if (hit.purchase().parts().size() >= 2
							&& !TradeMasterConfig.INSTANCE.multiEnchantProtection) {
						chat(Component.translatable("trademaster.message.combo_shortage_hint")
								.withStyle(ChatFormatting.YELLOW));
					}
					// tryMatch already dequeued the matched targets' budget slots: return
					// them, then stop. Rerolling would only re-match the same unaffordable
					// book — the miss branch's material precheck cannot catch a combo
					// priced above the emerald floor — so this must not fall through.
					s.matcher.undoMatch(hit);
					closeMerchantMenuIfOpen(Minecraft.getInstance());
					// name the slot that cannot be paid; the emerald wording stays as the
					// fallback when the offer vanished before we could look at it
					Component lack = hit.offerIndex() < snapshot.offers().size()
							? Preconditions.shortfall(player, OfferCosts.slots(snapshot.offers().get(hit.offerIndex())))
							: null;
					stop(lack != null ? lack
							: Component.translatable("trademaster.message.material_short"),
							ChatFormatting.RED);
					return;
				}
			}
		}

		if (hit != null) {
			s.matchedTrade = hit;
			debug("offers: matched " + hit.id() + " " + hit.level()
					+ " for " + hit.price() + " on roll " + (s.tries + 1));
			chat(Component.translatable("trademaster.message.found",
							describeEnchantment(hit), hit.price(), s.tries + 1)
					.withStyle(ChatFormatting.GREEN));
			lockInteracted = false;
			lockPurchaseInitiated = false;
			lockExecutor = null;
			lockSlotPrepared = false;
			lockNoMenuTicks = 0;
			lockRetryDelayTicks = 0;
			lockAttempts = 0;
			// the delivery's disarm left a merchant-screen suppression window; LOCK
			// re-opens the menu on purpose within a few ticks, so drop it or the
			// first interact's screen gets swallowed and the lock stalls ~5s
			DetectionHub.clearPostDisarmSuppression();
			enter(Phase.LOCK);
		} else {
			// materials can run out mid-run while one villager keeps being rerolled
			// (the pre-summon recheck only runs between villagers); re-check before
			// every new roll so the run stops instead of burning axe durability
			Component materials = Preconditions.check();
			if (materials != null) {
				closeMerchantMenuIfOpen(Minecraft.getInstance());
				stop(materials.copy().withStyle(ChatFormatting.RED), null);
				return;
			}
			s.tries++;
			debug("offers: " + snapshot.offers().size() + " offers, no match (roll " + s.tries + ") -> reroll");
			closeMerchantMenuIfOpen(Minecraft.getInstance());
			// same reason as the hit path: the delivery's disarm left a suppression
			// window, and if the detection mode is switched before the next check,
			// that check's own merchant screen would be swallowed by the leftover
			DetectionHub.clearPostDisarmSuppression();
			enter(Phase.REROLL);
		}
	}

	private static void handleDetectionFail(String reason) {
		detectionPending = false;
		RunSession s = session;
		if (s == null) return;
		debug("detection failed: " + reason);
		// the hub disarmed on its way in; drop the suppression window it left behind
		// like every other disarm site, or the next 2s of merchant screens are swallowed
		DetectionHub.clearPostDisarmSuppression();
		// retry the interaction; the CHECK_TRADE phase timeout is the guard
		closeMerchantMenuIfOpen(Minecraft.getInstance());
	}

	// ---- helpers ----------------------------------------------------------------

	private static boolean interactVillager(Minecraft client, Villager villager) {
		if (client.gameMode == null || client.player == null) return false;
		return Interactions.interactEntity(client, client.player, villager) == InteractionResult.SUCCESS;
	}

	/** Rebuilds the matcher's needs list, counting named villagers as obtained. */
	private static void refreshNeeds(Minecraft client, RunSession s) {
		if (client.level == null) return;
		List<Villager> villagers = client.level.getEntitiesOfClass(Villager.class,
				AABB.ofSize(Vec3.atCenterOf(s.lecternPos), 256, 128, 256));
		s.matcher.buildNeeds();
		// hunt-cheaper automatically cancels the named-villager deduction: a
		// named villager must never pause the hunt for a cheaper copy
		if (!TradeMasterConfig.INSTANCE.huntCheaper) {
			s.matcher.deductNamedVillagers(villagers);
		}
		s.matcher.dropLocked(s.lockedIds);
		// hunt-cheaper runs last: it re-adds locked enchantments at marked-1 budget
		s.matcher.setHuntPrices(huntMarkedPrices);
		s.matcher.setComboHuntPrices(comboHuntPrices);
		if (TradeMasterConfig.INSTANCE.huntCheaper) {
			s.matcher.applyHuntOverrides();
		}
		huntAppliedToNeeds = TradeMasterConfig.INSTANCE.huntCheaper;
		needsFingerprint = targetsFingerprint();
	}

	/** Stable hash of the live target config: any enabled/disabled toggle or
	 * level/maxPrice edit changes it, so the tick-top detector rebuilds once.
	 * Allocation-free on purpose (iterates the map directly, no target list):
	 * this runs every tick. */
	private static long targetsFingerprint() {
		long h = 1125899906842597L;
		// the air list decides whether a spec result / cost item counts, so it changes
		// which demands are live: editing it mid-run must rebuild the needs too
		for (String item : TradeMasterConfig.INSTANCE.airItems) {
			h = h * 31 + (item == null ? 0 : item.hashCode());
		}
		for (EnchantmentTarget target : TradeMasterConfig.INSTANCE.enchantments.values()) {
			h = h * 31 + (target.enabled() ? 1 : 0);
			if (!target.enabled()) continue;
			h = h * 31 + target.id().hashCode();
			h = h * 31 + target.level();
			h = h * 31 + target.maxPrice();
			// the cost shape and the (air) result decide whether the row can be
			// offered at all, so editing them must rebuild the needs too
			TradeOfferSpec spec = TradeMasterConfig.INSTANCE.specFor(target.id());
			h = h * 31 + (spec.result() == null ? 0 : spec.result().hashCode());
			for (TradeOfferSpec.TradeCost cost : spec.costs()) {
				h = h * 31 + (cost == null || cost.item() == null ? 0 : cost.item().hashCode());
				h = h * 31 + (cost == null ? 0 : cost.count());
			}
		}
		// custom goods shape the needs too (enabling one, or editing its price/amount,
		// must rebuild the table just like an enchantment edit)
		for (GoodSpec good : TradeMasterConfig.INSTANCE.goods) {
			h = h * 31 + (good.enabled() ? 1 : 0);
			if (!good.enabled()) continue;
			h = h * 31 + good.id().hashCode();
			h = h * 31 + good.maxPrice();
			h = h * 31 + good.resultCount();
			for (TradeOfferSpec.TradeCost cost : good.costs()) {
				h = h * 31 + (cost == null || cost.item() == null ? 0 : cost.item().hashCode());
				h = h * 31 + (cost == null ? 0 : cost.count());
			}
		}
		return h;
	}

	/** A rebuild drained the needs while the run is already committed: a LOCK
	 * purchase was sent (the server may have locked the villager) or the locked
	 * villager is being named/sent away. Stopping now would strand it unnamed
	 * and undispatched; these paths all end in their own refreshNeeds, which
	 * stops the run once the finish is actually complete. */
	private static boolean finalizingRun() {
		return phase == Phase.NAME || phase == Phase.SEND_AWAY
				|| (phase == Phase.LOCK && lockExecutor != null && lockExecutor.purchaseSent());
	}

	/** Warns (yellow, non-blocking) when the configured rig target cannot work: the work
	 * block is not a job site any villager profession can use, or the profession id is
	 * unknown. Both would otherwise stall the pipeline silently (no villager ever takes
	 * the job at the block). */
	private static void warnRigTarget() {
		for (Component warning : rigTargetWarnings()) {
			chat(warning.copy().withStyle(ChatFormatting.YELLOW));
		}
	}

	/** The rig-target warnings for the current config, in order (empty when the rig target
	 * is fine). Pure so the decisions and their message keys can be tested without a client;
	 * {@link #warnRigTarget} only styles and sends them. */
	static List<Component> rigTargetWarnings() {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		List<Component> warnings = new ArrayList<>(4);
		// an UNKNOWN block id silently falls back to the lectern, so report the config
		// itself (the job-site check below would pass on the fallback and stay quiet)
		Identifier blockId = config.workBlockIdentifier();
		net.minecraft.world.level.block.Block resolved = blockId == null ? null
				: BuiltInRegistries.BLOCK.getValue(blockId);
		boolean blockUsable = blockId != null && resolved != null
				&& resolved != net.minecraft.world.level.block.Blocks.AIR;
		if (!blockUsable) {
			warnings.add(Component.translatable("trademaster.message.workblock_unknown", config.workBlockId));
		} else if (!RigTarget.isJobSiteBlock(resolved)) {
			warnings.add(Component.translatable("trademaster.message.workblock_invalid", resolved.getName()));
		} else if (RigTarget.professionKnown()
				&& !RigTarget.professionKey().equals(VillagerProfession.NONE)
				&& !RigTarget.isJobSiteBlockFor(resolved, RigTarget.professionKey())) {
			// a valid job site, but not for THIS profession: the villager can never take the
			// job, so waiting for a profession would never end (a composter is never a
			// librarian's job site). Skipped for the unemployed target, which needs no job.
			warnings.add(Component.translatable("trademaster.message.workblock_not_for_profession",
					resolved.getName()));
		}
		if (config.professionIdentifier() == null) {
			// blank or unparsable. A BLANK id is the documented default (the runtime uses the
			// librarian), so only a real typo is worth a warning.
			if (config.professionId != null && !config.professionId.isBlank()) {
				warnings.add(Component.translatable("trademaster.message.profession_invalid",
						config.professionId));
			}
		} else if (!RigTarget.professionKnown()) {
			warnings.add(Component.translatable("trademaster.message.profession_invalid", config.professionId));
		} else if (RigTarget.professionConfiguredNonTradeable()) {
			// the target degrades to the librarian: say which id was ignored
			warnings.add(Component.translatable("trademaster.message.profession_not_tradeable",
					config.professionId));
		} else if (RigTarget.professionKey().equals(VillagerProfession.NONE)) {
			// unemployed sells no trade: as a target the run can never lock, so say so even
			// though the pairing check above deliberately skips it
			warnings.add(Component.translatable("trademaster.message.profession_unemployed",
					config.professionId));
		}
		// a non-librarian profession may sell GOODS instead of books: an enchantment
		// target only matches an enchanted-book result, so an enabled one would never
		// match and the run would spin. Point at the goods table instead. Only for a KNOWN,
		// tradeable profession — an unknown one already has its own warning.
		// the EFFECTIVE key: a non-tradeable target degrades to the librarian, and warning
		// about the (ignored) raw id would be wrong
		Identifier profession = config.professionIdentifier();
		if (RigTarget.professionKnown() && profession != null
				&& !RigTarget.professionKey().equals(VillagerProfession.LIBRARIAN)
				// unemployed sells nothing at all, so the goods-table hint cannot apply to it
				// (only the profession_unemployed warning above is right for it)
				&& !RigTarget.professionKey().equals(VillagerProfession.NONE)
				&& !config.enabledTargets().isEmpty()) {
			warnings.add(Component.translatable("trademaster.message.profession_not_book"));
		}
		return warnings;
	}

	/** Enabled demands that buildNeeds leaves out because they can never match, so the
	 * user sees why nothing is rolled for them: enchantment targets whose vanilla
	 * minimum price exceeds the configured max, targets no longer in the registry /
	 * not tradeable, and goods that cannot be offered (result item gone or air, price
	 * cap 0, or no payable price slot). */
	private static void warnUnmatchableDemands(Minecraft mc) {
		List<String> dead = new ArrayList<>();
		List<String> unavailable = new ArrayList<>();
		List<String> uncosted = new ArrayList<>();
		List<String> blankPrice = new ArrayList<>();
		List<String> goods = new ArrayList<>();
		for (EnchantmentTarget target : TradeMasterConfig.INSTANCE.enabledTargets()) {
			if (!TradeMatcher.registryTradeable(target.id())) {
				unavailable.add(target.id().toString());
				continue;
			}
			// a spec with no payable slot declares "pays nothing": no offer can satisfy it
			// ALONE (it may still ride a combo), so warn instead of spinning silently
			if (!TradeMatcher.targetHasPayableCost(target)) {
				uncosted.add(target.id().toString());
				continue;
			}
			// a BLANK price slot (slot 0) declares no item, so only a trade that charges
			// nothing there can match: a normal librarian trade always charges emeralds, so
			// the demand silently never matches (it may still ride a combo). The GUI lets the
			// row be left empty (= "not required"), so this is reachable without hand-editing.
			if (blankPriceSlot(TradeMasterConfig.INSTANCE.specFor(target.id()))) {
				blankPrice.add(target.id().toString());
				continue;
			}
			if (TradeMasterConfig.minPrice(target.level(), target.id()) > target.maxPrice()) {
				Enchantment enchantment = mc.level.registryAccess()
						.lookupOrThrow(Registries.ENCHANTMENT).getValue(target.id());
				dead.add(enchantment != null
						? Enchantment.getFullname(Holder.direct(enchantment), target.level()).getString()
						: target.id().toString());
			}
		}
		for (GoodSpec good : TradeMasterConfig.INSTANCE.enabledGoods()) {
			if (!TradeMatcher.goodMatchable(good)) {
				goods.add(good.id().toString());
				continue;
			}
			if (blankPriceSlot(good.spec())) blankPrice.add(good.id().toString());
		}
		if (!dead.isEmpty()) {
			chat(Component.translatable("trademaster.message.unmatchable_targets", String.join(", ", dead))
					.withStyle(ChatFormatting.YELLOW));
		}
		if (!unavailable.isEmpty()) {
			chat(Component.translatable("trademaster.message.unavailable_targets", String.join(", ", unavailable))
					.withStyle(ChatFormatting.YELLOW));
		}
		if (!goods.isEmpty()) {
			chat(Component.translatable("trademaster.message.unmatchable_goods", String.join(", ", goods))
					.withStyle(ChatFormatting.YELLOW));
		}
		if (!uncosted.isEmpty()) {
			chat(Component.translatable("trademaster.message.uncosted_targets", String.join(", ", uncosted))
					.withStyle(ChatFormatting.YELLOW));
		}
		if (!blankPrice.isEmpty()) {
			chat(Component.translatable("trademaster.message.blank_price_slot", String.join(", ", blankPrice))
					.withStyle(ChatFormatting.YELLOW));
		}
	}

	/** Whether a spec's PRICE slot (slot 0) declares no item, so only a trade charging nothing
	 * there can match it. */
	static boolean blankPriceSlot(TradeOfferSpec spec) {
		TradeOfferSpec.TradeCost first = spec.costAt(0);
		return first == null || first.item() == null || TradeMasterConfig.INSTANCE.isAirItem(first.item());
	}

	/** Closes a leftover merchant menu (EVENT/BOTH detection leaves it open).
	 * Merchant menus only: closing ANY container would also tear down a chest
	 * menu a depositor may be using or an anvil mid-naming. */
	private static void closeMerchantMenuIfOpen(Minecraft client) {
		if (client.player != null && client.player.containerMenu instanceof MerchantMenu) {
			client.player.closeContainer();
		}
	}

	/** Remembers the display names actually used at naming time: if the game language
	 * changes later, deductNamedVillagers can still resolve the villagers. A multi-book
	 * name covers every matched enchantment, so each one is recorded. Called on success
	 * and on a failure that happened AFTER the tag interact was sent (the villager may
	 * already carry the name). */
	private static void recordNameHistory(List<NamingExecutor.NameRecord> records) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		for (NamingExecutor.NameRecord record : records) {
			String displayName = record.displayName();
			Identifier id = record.id();
			Identifier previous = config.nameHistory.get(displayName);
			if (previous != null && !previous.equals(id)) {
				// same display name, different enchantment (custom-name collision):
				// the entry is ambiguous — drop it instead of silently overwriting
				config.nameHistory.remove(displayName);
			} else {
				// remove+put moves the entry to the tail so recency survives:
				// a plain put on an existing key keeps its old insertion order,
				// and the cap trim would evict still-recent names first
				config.nameHistory.remove(displayName);
				config.nameHistory.put(displayName, id);
			}
		}
		while (config.nameHistory.size() > 256) {
			config.nameHistory.remove(config.nameHistory.keySet().iterator().next());
		}
		ConfigPersistence.save(config);
	}

	/**
	 * The villager to work with at the arrival point: a librarian if there is
	 * one, otherwise the nearest unemployed villager (still usable — it can take
	 * the lectern and become a librarian). Babies are skipped: they cannot trade
	 * or take a profession, so picking one would stall the run.
	 */
	private static @Nullable Villager findArrivedVillager(Minecraft client, BlockPos lecternPos) {
		if (client.level == null || client.player == null) return null;
		Vec3 arrival = arrivalPoint(client, lecternPos);
		Villager bestLibrarian = null;
		Villager bestUnemployed = null;
		double bestLibrarianDistance = Double.POSITIVE_INFINITY;
		double bestUnemployedDistance = Double.POSITIVE_INFINITY;
		for (Villager villager : villagersAtArrivalPoint(client, arrival)) {
			if (villager.isBaby()) continue;
			double distance = villager.position().distanceToSqr(arrival);
			if (villager.getVillagerData().profession().is(RigTarget.professionKey())) {
				if (distance < bestLibrarianDistance) {
					bestLibrarianDistance = distance;
					bestLibrarian = villager;
				}
			} else if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) {
				if (distance < bestUnemployedDistance) {
					bestUnemployedDistance = distance;
					bestUnemployed = villager;
				}
			}
		}
		return bestLibrarian != null ? bestLibrarian : bestUnemployed;
	}

	/**
	 * The ray detection point: player position as origin, lectern as direction,
	 * extended one block past the lectern (two blocks along the ray in the rig
	 * layout player -> lectern -> villager). This is the ONLY place that decides
	 * whether a villager has arrived and is the one to roll.
	 */
	private static Vec3 arrivalPoint(Minecraft client, BlockPos lecternPos) {
		Vec3 lecternCenter = Vec3.atCenterOf(lecternPos);
		Vec3 dir = lecternCenter.subtract(client.player.position()).normalize();
		return lecternCenter.add(dir);
	}

	private static List<Villager> villagersAtArrivalPoint(Minecraft client, BlockPos lecternPos) {
		return villagersAtArrivalPoint(client, arrivalPoint(client, lecternPos));
	}

	private static List<Villager> villagersAtArrivalPoint(Minecraft client, Vec3 arrival) {
		return villagersAtArrivalPoint(client, arrival, ARRIVE_DISTANCE_SQR);
	}

	/** Same scan with an explicit radius. The rig-clear wait passes the LOOSE
	 * threshold: "has the dispatched villager left" must not read someone in the
	 * dead band as gone, or the staging scan re-grabs them and the run re-summons. */
	private static List<Villager> villagersAtArrivalPoint(Minecraft client, Vec3 arrival, double maxDistSqr) {
		// getEntitiesOfClass already returns a fresh mutable list: the extra copy
		// this used to make was pure garbage on every scan
		List<Villager> villagers = client.level.getEntitiesOfClass(Villager.class,
				AABB.ofSize(arrival, 4, 4, 4));
		villagers.removeIf(v -> v.position().distanceToSqr(arrival) > maxDistSqr);
		return villagers;
	}

	/** Whether the double-coordinate staging region is fully configured. */
	private static boolean regionConfigured() {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		return config.villagerRegionOrigin != null && config.villagerRegionDelta != null;
	}

	/** Configured staging-sphere radius, clamped defensively. */
	private static int sphereRadius() {
		return Math.max(1, Math.min(64, TradeMasterConfig.INSTANCE.villagerSphereRadius));
	}

	/** /trademaster goon: confirms the lectern-sphere staging fallback that the last
	 * refused start asked for, then starts. Invalid while nothing is pending. */
	public static void confirmRegionFallback() {
		if (!regionFallbackPending) {
			chat(Component.translatable("trademaster.message.goon_nothing_pending")
					.withStyle(ChatFormatting.RED));
			return;
		}
		regionFallbackPending = false;
		regionFallbackConfirmed = true;
		regionFallbackLevel = new java.lang.ref.WeakReference<>(Minecraft.getInstance().level);
		chat(Component.translatable("trademaster.message.region_fallback_active", sphereRadius())
				.withStyle(ChatFormatting.YELLOW));
		start();
	}

	/** Villagers the minecart can grab: the configured region box when it is set,
	 * otherwise a sphere around the lectern (the region spec's fallback). Read live,
	 * so completing the region coords mid-run switches back to them automatically. */
	private static List<Villager> stagingVillagers(Minecraft client, RunSession s) {
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		BlockPos origin = config.villagerRegionOrigin;
		BlockPos delta = config.villagerRegionDelta;
		if (origin != null && delta != null) {
			BlockPos far = origin.offset(delta);
			AABB box = new AABB(
					Math.min(origin.getX(), far.getX()), Math.min(origin.getY(), far.getY()), Math.min(origin.getZ(), far.getZ()),
					Math.max(origin.getX(), far.getX()) + 1, Math.max(origin.getY(), far.getY()) + 1, Math.max(origin.getZ(), far.getZ()) + 1);
			List<Villager> region = new ArrayList<>(client.level.getEntitiesOfClass(Villager.class, box));
			// the arrival point need not lie inside the configured region (see
			// tickAwaitVillager's baby check): a villager parked there is grabbable, so
			// include it — otherwise rest mode waits forever for one that never enters
			// the box, checking only the box here but the arrival point for babies
			for (Villager arrived : villagersAtArrivalPoint(client, s.lecternPos)) {
				if (!region.contains(arrived)) region.add(arrived);
			}
			return region;
		}
		Vec3 center = Vec3.atCenterOf(s.lecternPos);
		double radius = sphereRadius();
		List<Villager> result = new ArrayList<>(client.level.getEntitiesOfClass(Villager.class,
						AABB.ofSize(center, radius * 2, radius * 2, radius * 2)).stream()
				.filter(v -> v.position().distanceToSqr(center) <= radius * radius)
				.toList());
		// the rig parks the villager at the arrival point, which can lie outside a
		// small radius: one standing there is certainly grabbable, so include it —
		// otherwise a radius below ~3 would wait for a villager that never counts
		for (Villager arrived : villagersAtArrivalPoint(client, s.lecternPos)) {
			if (!result.contains(arrived)) result.add(arrived);
		}
		return result;
	}

	/** The matched offer drifted or vanished while the villager was still
	 * unlocked: give the budget back and re-detect the fresh offers on the same
	 * villager (a free reroll — nothing was bought). */
	private static void freeReroll(Minecraft client, RunSession s) {
		debug("lock: offers changed before locking, treating as free reroll");
		// same guard as awaitNextVillager: a locked target is out of the
		// needs (dropLocked); resetting its budget would resurrect it for
		// a duplicate purchase after hunt was turned off mid-run
		if (!matchLockedDone(s)) {
			s.matcher.resetBudget(s.matchedTrade);
		}
		s.matchedTrade = null;
		// same cleanup as lockRejected: a leftover executor/interact state would be
		// read by the re-entered CHECK_TRADE as if this lock attempt were still live
		lockExecutor = null;
		lockPurchaseInitiated = false;
		lockInteracted = false;
		lockSlotPrepared = false;
		checkLook.reset();
		closeMerchantMenuIfOpen(client);
		enter(Phase.CHECK_TRADE);
	}

	/** Every matched target of the pending match is locked this run and none is
	 * hunt-marked: the combo is fully covered, and returning its budget would
	 * resurrect locked targets via computeIfAbsent for a duplicate purchase
	 * (e.g. hunt turned off after a cheaper copy matched). Hunt-marked targets
	 * stay live through applyHuntOverrides and keep their restore path. */
	private static boolean matchLockedDone(RunSession s) {
		if (s.matchedTrade == null) return true;
		for (Identifier id : s.matchedTrade.matchedIds()) {
			if (!s.lockedIds.contains(id) || huntMarkedPrices.containsKey(id)) return false;
		}
		return true;
	}

	/** Whether the pending purchase no longer fits the budget tryMatch admitted it
	 * with. A combo admitted via its recorded need is measured against that record
	 * alone; otherwise each part of the purchased book contributes its hunt mark-1
	 * (when marked) or its live configured max price, capped at 64 like admission. */
	private static boolean priceOutOfAdmissionBudget(TradeMatcher.MatchedTrade trade) {
		TradeMatcher.MatchedBook purchase = trade.purchase();
		if (purchase.parts().size() >= 2) {
			Integer comboMark = comboHuntPrices.get(TradeMatcher.comboKey(purchase.parts()));
			if (comboMark != null) return purchase.price() >= comboMark;
		}
		int budget = 0;
		for (TradeMatcher.MatchPart part : purchase.parts()) {
			Integer marked = huntMarkedPrices.get(part.id());
			if (marked != null) {
				// same rule as TradeMatcher.budgetFor: the hunt lowers the budget and
				// the configured max price caps it, so a mid-run maxPrice drop also
				// invalidates an already-pending match. An unknown target is left
				// uncapped (it was disabled/removed mid-run).
				GoodSpec markedGood = TradeMasterConfig.INSTANCE.goodFor(part.id());
				if (markedGood != null) {
					budget += Math.min(marked - 1, markedGood.maxPrice());
					continue;
				}
				EnchantmentTarget markedTarget = TradeMasterConfig.INSTANCE.enchantments.get(part.id());
				budget += markedTarget == null ? marked - 1
						: Math.min(marked - 1, markedTarget.maxPrice());
				continue;
			}
			GoodSpec good = TradeMasterConfig.INSTANCE.goodFor(part.id());
			if (good != null) {
				budget += good.maxPrice();
				continue;
			}
			EnchantmentTarget target = TradeMasterConfig.INSTANCE.enchantments.get(part.id());
			if (target != null) budget += target.maxPrice();
		}
		return purchase.price() > Math.min(budget, 64);
	}

	/** Whether the exact offer that matched at detection is still sitting at the
	 * recorded slot, unchanged: same enchantment, level and price. An unlocked
	 * villager's profession can auto-reroll during the LOCK window (deposit
	 * detours make it long), regenerating the offers — the same enchantment may
	 * come back at a higher price, or a different book may take the old slot.
	 * Any drift must go through the free-reroll re-match instead of locking
	 * blindly at a price the budget never accepted. */
	private static boolean matchedOfferUnchanged(MerchantOffers offers, TradeMatcher.MatchedTrade trade) {
		if (trade.offerIndex() < 0 || trade.offerIndex() >= offers.size()) return false;
		MerchantOffer offer = offers.get(trade.offerIndex());
		if (offer.isOutOfStock()) return false;
		// the WHOLE cost shape and the result, not a hardcoded "emerald + book +
		// enchanted book": a custom spec (or a custom good, whose result is any item)
		// must be judged against what was actually matched
		if (!trade.purchase().costsMatch(OfferCosts.slots(offer))) return false;
		return trade.purchase().resultMatches(offer.getResult());
	}

	private static int resolveLockSlot(MerchantOffers offers, RunSession s) {
		TradeMatcher.MatchedTrade hit = s.matchedTrade;
		LocalPlayer player = Minecraft.getInstance().player;
		boolean matchedPayable = hit != null && player != null && hit.offerIndex() < offers.size()
				&& Preconditions.checkLockMaterials(player, OfferCosts.slots(offers.get(hit.offerIndex())));
		if (!matchedPayable && player != null) {
			// the matched book is unaffordable (emeralds spent, or a combo priced
			// above what the player carries while every single target still fits):
			// the cheapest affordable book locks the villager just as well
			int alt = cheapestAffordableBookSlot(offers, player);
			if (alt >= 0) return alt;
		}
		if (hit != null && hit.offerIndex() < offers.size()
				&& hit.purchase().resultMatches(offers.get(hit.offerIndex()).getResult())) {
			return hit.offerIndex();
		}
		// Defensive tail, unreachable today (audited 2026-09-19): LOCK is only entered
		// from a detected match, and every branch above returns. Kept so a future
		// caller without a matched trade still gets a sane slot instead of -1.
		for (int i = 0; i < offers.size(); i++) {
			if (offers.get(i).getResult().is(Items.ENCHANTED_BOOK)) return i;
		}
		return -1;
	}

	/** Cheapest enchanted-book offer the player can actually pay for, or -1. */
	private static int cheapestAffordableBookSlot(MerchantOffers offers, LocalPlayer player) {
		int bestSlot = -1;
		int bestPrice = Integer.MAX_VALUE;
		for (int i = 0; i < offers.size(); i++) {
			MerchantOffer offer = offers.get(i);
			if (offer.isOutOfStock()) continue;
			if (offer.getBaseCostA().getItem() != Items.EMERALD || offer.getCostB().getItem() != Items.BOOK
					|| !offer.getResult().is(Items.ENCHANTED_BOOK)) continue;
			int price = offer.getBaseCostA().getCount();
			if (price >= bestPrice) continue;
			if (!Preconditions.checkLockMaterials(player, OfferCosts.slots(offer))) continue;
			bestSlot = i;
			bestPrice = price;
		}
		return bestSlot;
	}

	/** Fingerprint of one offer, for buying a book that is NOT the matched one: the
	 * executor then verifies the slot against the very offer it was chosen from. A
	 * matched-trade fingerprint would fail the first stage, drift, and free-reroll
	 * into matching the same unaffordable book again — the old livelock. */
	private static TradeMatcher.MatchedBook bookFingerprint(MerchantOffer offer, int slot) {
		java.util.ArrayList<TradeMatcher.MatchPart> parts = new java.util.ArrayList<>();
		var enchantments = EnchantmentHelper.getEnchantmentsForCrafting(offer.getResult());
		for (var entry : enchantments.entrySet()) {
			Identifier id = entry.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
			if (id != null) parts.add(new TradeMatcher.MatchPart(id, entry.getIntValue()));
		}
		return new TradeMatcher.MatchedBook(List.copyOf(parts), List.copyOf(parts),
				offer.getBaseCostA().getCount(), slot,
				TradeMatcher.MatchedBook.fingerprintCosts(OfferCosts.slots(offer)),
				offer.getResult().copy());
	}

	/** One book's matched parts joined with "+" (vanilla fullname per part). */
	private static MutableComponent describeBook(TradeMatcher.MatchedBook book) {
		Minecraft mc = Minecraft.getInstance();
		MutableComponent bookText = null;
		for (TradeMatcher.MatchPart part : book.parts()) {
			MutableComponent partText = null;
			if (part.level() <= 0) {
				// a custom good: the part is an item, not an enchantment, so the
				// enchantment registry lookup below would print its raw id
				partText = Component.literal(EnchantmentNamer.demandDisplayName(part.id()));
			} else if (mc.level != null) {
				Enchantment enchantment = mc.level.registryAccess()
						.lookupOrThrow(Registries.ENCHANTMENT).getValue(part.id());
				if (enchantment != null) {
					partText = Enchantment.getFullname(Holder.direct(enchantment), part.level()).copy();
				}
			}
			if (partText == null) partText = Component.literal(part.id() + " " + part.level());
			bookText = bookText == null ? partText : bookText.append(Component.literal("+")).append(partText);
		}
		return bookText == null ? Component.literal("?") : bookText;
	}

	private static Component describeEnchantment(TradeMatcher.MatchedTrade trade) {
		List<TradeMatcher.MatchedBook> books = new java.util.ArrayList<>(trade.books());
		books.sort(java.util.Comparator.comparingInt(TradeMatcher.MatchedBook::offerIndex));
		MutableComponent result = null;
		for (TradeMatcher.MatchedBook book : books) {
			MutableComponent bookText = describeBook(book);
			result = result == null ? bookText : result.append(Component.literal(" ")).append(bookText);
		}
		return result == null ? Component.literal("?") : result;
	}

	/** Readable registry id of a profession for debug output (e.g. "minecraft:librarian"). */
	private static String professionName(Holder<VillagerProfession> profession) {
		return String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession.value()));
	}

	/** Nitwits can never take a profession, and villagers already employed in any
	 * other non-librarian profession will not switch at this lectern: neither is
	 * rollable, they must be dispatched. */
	private static boolean unusableProfession(Holder<VillagerProfession> profession) {
		return RigTarget.isUnusable(profession);
	}

	/** Whether the sand-wheel mode definition changed while the run was going. The
	 * enable flag and the grindstone position define the mode itself, and the
	 * deposit factory only re-reads them at deposit time — switching mid-run would
	 * silently change how books are disposed of. The drop DIRECTION is deliberately
	 * not checked: editing it mid-run is allowed, DROP_AIM re-reads it live. */
	/** The rig-target definition as one string: the mid-run change guard compares this. */
	private static String rigTargetDefinition(TradeMasterConfig config) {
		return config.workBlockId + "\u0000" + config.professionId + "\u0000"
				+ String.join(",", config.nonTradeableProfessions);
	}

	private static boolean grindstoneConfigChanged(TradeMasterConfig config) {
		if (config.grindstoneEnabled != snapGrindstoneEnabled) return true;
		if (config.grindstonePos == null) return snapGrindstonePos != null;
		return !config.grindstonePos.equals(snapGrindstonePos);
	}

	/** The lectern the RUN started with, or the configured one when idle. Runtime
	 * prechecks must judge this, not the live config: the settings rows commit per
	 * keystroke, so typing a new lectern coordinate mid-run otherwise reported a
	 * bogus lectern_lost and stopped a healthy run. */
	public static @Nullable BlockPos runningLecternPos() {
		RunSession s = session;
		return s == null ? TradeMasterConfig.INSTANCE.lecternPos : s.lecternPos;
	}

	private static void stop(@Nullable Component message, @Nullable ChatFormatting color) {
		releaseCheck();
		dispenserUnsetWarned = false;
		dispenserOutOfReachWarned = false;
		dispenserFailedAttempts = 0;
		DetectionHub.disarm();
		grindstoneSnapshotValid = false;
		snapRegionConfigured = false;
		snapRigTarget = null;
		sessionDimension = null;
		regionFallbackPending = false;
		// a stopped pipeline sends no more interacts: the residual suppression
		// window would only swallow the player's own merchant screens
		DetectionHub.clearPostDisarmSuppression();
		if (strategy != null) {
			strategy.abort();
			strategy = null;
		}
		if (guardian != null) {
			guardian.abort();
			guardian = null;
		}
		SmoothRotation.reset();
		strayChestTicks = 0;
		// a press can be interrupted mid-way with the hotbar selection parked on its
		// temporary empty slot: hand it back instead of just dropping the reference
		if (summonPresser != null) {
			summonPresser.abortCleanly();
			summonPresser = null;
		}
		if (sendAwayPresser != null) {
			sendAwayPresser.abortCleanly();
			sendAwayPresser = null;
		}
		// AFTER the pressers: their abortCleanly restores the slot captured when the
		// press started, so running this first would be overwritten
		LecternRestock.restoreOriginalSelection();
		lockExecutor = null;
		if (namingExecutor != null) {
			namingExecutor.abortCleanly();
			namingExecutor = null;
		}
		if (depositor != null) {
			depositor.abortCleanly();
			depositor = null;
		}
		// a manual stop can land mid-naming (anvil open) or mid-deposit (chest
		// open): unlike the pipeline paths (which only clear merchant menus), a
		// stop must tear down whatever container is open
		{
			Minecraft closeClient = Minecraft.getInstance();
			if (closeClient.player != null && closeClient.player.containerMenu != closeClient.player.inventoryMenu) {
				closeClient.player.closeContainer();
			}
		}
		detectionPending = false;
		lockInteracted = false;
		lockPurchaseInitiated = false;
		checkLook.reset();
		restSummonPending = false;
		awaitingRigClear = false;
		postDispatchRefresh = false;
		postDispatchBreakRetriesSpent = 0;
		lockSlotPrepared = false;
		lockExhaustionDepositPending = false;
		professionHealCycles = 0;
		interactFailTicks = 0;
		nonLibrarianTicks = 0;
		urgentProfessionHeal = false;
		placementFailRetries = 0;
		lockNoMenuTicks = 0;
		lockRetryDelayTicks = 0;
		lockAttempts = 0;
		breakRetryTicks = 0;
		slowInteractDelay = 0;
		// hunt-cheaper price table is per-run: a stop (/trademaster stop etc.)
		// wipes the marked prices, so the next start hunts from scratch
		huntMarkedPrices.clear();
		comboHuntPrices.clear();
		huntAppliedToNeeds = false;
		needsFingerprint = null;
		cachedStatusText = null;
		lastOverlayText = null;
		session = null;
		phase = Phase.IDLE;
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			mc.gui.hud.setOverlayMessage(Component.literal(""), false);
			overlayNotice = null;
			overlayNoticeTicks = 0;
		}
		if (message != null) {
			chat(color == null ? message : message.copy().withStyle(color));
		}
	}

	/** Silent stop used on disconnect: no chat, no overlay writes from foreign threads. */
	public static void shutdown() {
		stop(null, null);
	}

	/** Repeating notices (per villager / per retry) go to the ACTION BAR instead of
	 * the chat: they used to push everything else out of the small Android chat box.
	 * While one is showing it takes precedence over the status line. */
	private static int overlayNoticeTicks = 0;
	private static @Nullable Component overlayNotice = null;

	private static void notice(Component component) {
		Minecraft mc = Minecraft.getInstance();
		overlayNotice = component;
		overlayNoticeTicks = 20 * 4;
		if (mc.gui != null) mc.gui.hud.setOverlayMessage(component, false);
	}

	/** Diagnostics go to the game log file, never to the chat bar. */
	private static final org.apache.logging.log4j.Logger LOGGER =
			org.apache.logging.log4j.LogManager.getLogger("TradeMaster");

	public static void debug(String message) {
		if (!TradeMasterConfig.INSTANCE.debugLog) return;
		LOGGER.info("[TradeMaster] {}", message);
	}

	/** True once per debug-logging session per key: keeps a repeating wait from
	 * filling the log with the same line. */
	private static final java.util.Set<String> loggedOnce = new java.util.HashSet<>();

	public static boolean debugOnce(String key, String message) {
		if (!TradeMasterConfig.INSTANCE.debugLog) return false;
		if (!loggedOnce.add(key)) return false;
		LOGGER.info("[TradeMaster] {}", message);
		return true;
	}

	private static void chat(Component component) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (mc.isSameThread()) {
			mc.player.sendSystemMessage(component);
		} else {
			// never touch the chat list from a foreign thread: quitting the world
			// crashed with a ConcurrentModificationException in ChatComponent
			mc.execute(() -> {
				if (mc.player != null) mc.player.sendSystemMessage(component);
			});
		}
	}
}
