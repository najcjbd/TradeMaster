package dev.trademaster.detection;

import dev.trademaster.config.DetectionMode;
import dev.trademaster.config.TradeMasterConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Combines the two detection channels:
 * - PACKET: the {@code ClientboundMerchantOffersPacket} intercepted on the
 *   wire (LibrarianTradeFinder style; the merchant screen is cancelled).
 * - EVENT: the opened {@link MerchantMenu} observed through container
 *   initialize + offers-updated events (LPCTools style, mask == 3).
 * - BOTH: both channels must deliver a consistent snapshot.
 *
 * Mixins feed data through the static methods; only one check can be armed
 * at a time.
 */
public final class DetectionHub {

	private static final int DEFAULT_TIMEOUT_TICKS = 400;
	private static final long POST_DISARM_SUPPRESS_NANOS = 2_000_000_000L;

	// volatile: armed is read on the network thread by ConnectionMixin; the
	// intercept flag lives on the hub itself so a single volatile write publishes
	// both — a flag written after the hub reference could be missed by the reader
	private static volatile @Nullable DetectionHub armed = null;
	/** B2 defence: duplicate interact packets can open a second merchant screen after
	 * the first delivery disarmed us; keep suppressing MERCHANT open packets briefly. */
	private static volatile long suppressMerchantScreensUntilNanos = 0L;
	/** Container id of the most recently suppressed merchant screen. While a new
	 * check's own suppressed screen has not bound expectedContainerId yet, a late
	 * offers packet from THIS container is a leftover of the previous check and
	 * must not be delivered as the new check's result. */
	private static volatile int lastSuppressedContainerId = -1;

	private final DetectionMode mode;
	/** The villager this check is about: the event channel's identity anchor (see
	 * {@link #eventMenuMatchesTrader()}). */
	private final @Nullable Villager expectedTrader;
	private final Consumer<OfferSnapshot> onResult;
	private final Consumer<String> onFail;
	/** PACKET mode: merchant screen opens are suppressed for this check. */
	private final boolean interceptScreens;

	private boolean gotPacket = false;
	private boolean gotEvent = false;
	private @Nullable MerchantOffers packetOffers = null;
	private @Nullable MerchantMenu eventMenu = null;
	private int eventMask = 0;
	private int ticksArmed = 0;
	/** Wall-clock arm time: late offers packets (>3s) are refused regardless of
	 * channel — an RTT-scale answer never takes that long, and ticksArmed only
	 * advances on client ticks, which stall when the client hangs. */
	private final long armedAtNanos = System.nanoTime();
	/** PACKET channel: containerId of the most recently suppressed merchant screen —
	 * our own interact's answer (latest wins: a duplicate interact can open a second
	 * screen, and its offers are the ones still in flight). Offers packets for any
	 * other container are not ours. */
	private int expectedContainerId = -1;

	private DetectionHub(DetectionMode mode, boolean interceptScreens, @Nullable Villager expectedTrader,
			Consumer<OfferSnapshot> onResult, Consumer<String> onFail) {
		this.mode = mode;
		this.interceptScreens = interceptScreens;
		this.expectedTrader = expectedTrader;
		this.onResult = onResult;
		this.onFail = onFail;
	}

	public static void arm(DetectionMode mode, boolean interceptScreen, @Nullable Villager expectedTrader,
			Consumer<OfferSnapshot> onResult, Consumer<String> onFail) {
		// single volatile write: the hub and its intercept flag are published together
		armed = new DetectionHub(mode, interceptScreen, expectedTrader, onResult, onFail);
	}

	public static void disarm() {
		DetectionHub hub = armed;
		if (hub != null && hub.interceptScreens) {
			suppressMerchantScreensUntilNanos = System.nanoTime() + POST_DISARM_SUPPRESS_NANOS;
		}
		armed = null;
	}

	/** Drops the residual suppression window left by {@link #disarm}. The LOCK
	 * phase re-opens the merchant menu on purpose right after a delivery, and a
	 * stopped pipeline must not swallow the player's own merchant screens. */
	public static void clearPostDisarmSuppression() {
		suppressMerchantScreensUntilNanos = 0L;
	}

	public static boolean shouldInterceptMerchantScreen() {
		DetectionHub hub = armed;
		if (hub != null && hub.interceptScreens) return true;
		return System.nanoTime() < suppressMerchantScreensUntilNanos;
	}

	// ---- mixin feeds -------------------------------------------------------

	public static void onOffersPacket(MerchantOffers offers, int containerId) {
		DetectionHub hub = armed;
		if (hub == null) return;
		Minecraft.getInstance().execute(() -> hub.feedPacket(offers, containerId));
	}

	/** The hub armed right now, for a caller that must bind to the hub of ITS packet
	 * and not to whatever is armed later: the feed below is marshalled onto the main
	 * thread, so an open packet delayed past a re-arm would otherwise bind its
	 * containerId to the NEXT check's hub. */
	public static @Nullable DetectionHub armedHub() {
		return armed;
	}

	/** PACKET channel: a merchant screen open was suppressed; the latest one is the
	 * answer to our own interact (a duplicate interact can open a second screen
	 * whose offers are the ones still in flight) and fixes the expected container id.
	 *
	 * @param hub the hub captured when the packet ARRIVED (see {@link #armedHub()});
	 * null means nothing was armed then, so only the static leftover marker is set. */
	public static void onMerchantScreenSuppressed(@Nullable DetectionHub hub, int containerId) {
		lastSuppressedContainerId = containerId;
		if (hub == null) return;
		hub.expectedContainerId = containerId;
	}

	public static void onMerchantMenuOpened(MerchantMenu menu) {
		DetectionHub hub = armed;
		if (hub == null) return;
		hub.eventMask |= 1;
		hub.eventMenu = menu;
		hub.tryDeliverEvent();
	}

	public static void onMerchantMenuOffersUpdated(MerchantMenu menu) {
		DetectionHub hub = armed;
		if (hub == null) return;
		hub.eventMask |= 2;
		hub.eventMenu = menu;
		hub.tryDeliverEvent();
	}

	// ---- per-check logic ---------------------------------------------------

	private void feedPacket(MerchantOffers offers, int containerId) {
		if (this.gotPacket) return;
		// an offers packet arriving >3s after arming cannot be our own interact's
		// answer (that is RTT-scale) on ANY channel: it is a different villager's
		// trade or a stale leftover, never this check's result
		if (System.nanoTime() - this.armedAtNanos > 3_000_000_000L) return;
		// a stray offers packet for a different container (leftover menu, another
		// villager) must not be consumed as this check's result
		if (this.eventMenu != null) {
			if (containerId != this.eventMenu.containerId) return;
		} else if (this.expectedContainerId >= 0) {
			if (containerId != this.expectedContainerId) return;
		} else {
			// anchor not bound yet (no suppressed screen since arm): refuse the
			// previous check's suppressed container — its offers packet can arrive
			// late under lag and would otherwise be delivered as this check's
			// result — and otherwise only accept a packet matching the merchant
			// menu the player actually has open
			if (containerId == lastSuppressedContainerId) return;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.player.containerMenu instanceof MerchantMenu open
					&& containerId != open.containerId) {
				return;
			}
		}
		this.gotPacket = true;
		this.packetOffers = offers;
		if (this.mode == DetectionMode.PACKET) {
			deliver(new OfferSnapshot(offers));
		} else {
			tryDeliverBoth();
		}
	}

	private void tryDeliverEvent() {
		if (this.gotEvent || this.eventMask != 3 || this.eventMenu == null) return;
		if (!eventMenuMatchesTrader()) {
			fail("merchant menu belongs to another villager");
			return;
		}
		this.gotEvent = true;
		if (this.mode == DetectionMode.EVENT) {
			deliver(eventSnapshot());
		} else {
			tryDeliverBoth();
		}
	}

	private void tryDeliverBoth() {
		if (this.mode != DetectionMode.BOTH) return;
		if (!this.gotPacket || !this.gotEvent) return;
		if (!eventMenuMatchesTrader()) {
			fail("merchant menu belongs to another villager");
			return;
		}
		OfferSnapshot event = eventSnapshot();
		if (event == null) {
			fail("event channel produced no merchant");
			return;
		}
		if (!offersMatch(this.packetOffers, event.offers())) {
			fail("packet and event snapshots disagree");
			return;
		}
		deliver(event);
	}

	/** The event channel opens for ANY merchant the player trades with, and its
	 * menu wraps a freshly constructed ClientSideMerchant that carries no
	 * position or identity — it cannot be compared against the session villager
	 * entity directly. It is instead tied to this check by requiring the session
	 * villager to be the NEAREST merchant to the player: a menu the player (or
	 * another mod) opened at a DIFFERENT, closer villager is refused, so its
	 * offers cannot be purchased while the tracked villager is named and sent
	 * away. Cross-villager confusion is further caught by the PACKET channel
	 * containerId correlation plus {@link #offersMatch} in BOTH mode, and as a
	 * last line by the LOCK-time fingerprint re-check (slotStillMatches). */
	private boolean eventMenuMatchesTrader() {
		if (this.eventMenu == null) return false;
		if (this.expectedTrader == null) return true;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) return true;
		double mine = player.distanceToSqr(this.expectedTrader);
		for (Villager other : mc.level.getEntitiesOfClass(Villager.class,
				player.getBoundingBox().inflate(16.0))) {
			if (other != this.expectedTrader && player.distanceToSqr(other) < mine) return false;
		}
		return true;
	}

	private @Nullable OfferSnapshot eventSnapshot() {
		if (this.eventMenu == null) return null;
		Merchant trader = ((dev.trademaster.mixin.MerchantMenuAccessor) this.eventMenu).getTrader();
		if (trader == null) return null;
		return new OfferSnapshot(trader.getOffers());
	}

	private void deliver(OfferSnapshot snapshot) {
		// a late delivery (queued via Minecraft.execute) must not wipe a freshly re-armed hub
		if (armed != this) return;
		disarm();
		this.onResult.accept(snapshot);
	}

	private void fail(String reason) {
		if (armed != this) return;
		disarm();
		this.onFail.accept(reason);
	}

	/** Called from the controller tick; enforces the timeout window. */
	public static void tick() {
		DetectionHub hub = armed;
		if (hub == null) return;
		hub.ticksArmed++;
		if (!TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled) return;
		// the SAME window the packet feed enforces (3s wall clock): a packet arriving
		// after it is discarded there, so the check can never succeed any more — end it
		// now instead of making the controller wait for its own tick counter, which
		// stalls exactly when the wall clock ran ahead (main-thread hitch)
		if (System.nanoTime() - hub.armedAtNanos > 3_000_000_000L) {
			hub.fail("detection window expired");
			return;
		}
		if (hub.ticksArmed > DEFAULT_TIMEOUT_TICKS) {
			hub.fail("detection timed out");
		}
	}

	/** Deep-compare two offer lists so a stale or foreign snapshot can never be
	 * delivered as this check's result in BOTH mode: same size, per-offer result
	 * item, every enchantment id+level on enchanted books, and both cost stacks
	 * including their counts (isSameItem ignores count, but the emerald amount
	 * is part of the trade's identity). The last line against cross-villager
	 * confusion remains the LOCK-time fingerprint re-check. */
	private static boolean offersMatch(@Nullable MerchantOffers a, MerchantOffers b) {
		if (a == null || a.size() != b.size()) return false;

		for (int i = 0; i < a.size(); i++) {
			MerchantOffer oa = a.get(i);
			MerchantOffer ob = b.get(i);

			if (!ItemStack.isSameItem(oa.getResult(), ob.getResult())) return false;

			if (oa.getResult().is(Items.ENCHANTED_BOOK)) {
				var enchantmentsA = EnchantmentHelper.getEnchantmentsForCrafting(oa.getResult());
				var enchantmentsB = EnchantmentHelper.getEnchantmentsForCrafting(ob.getResult());
				if (enchantmentsA.size() != enchantmentsB.size()) return false;

				for (var entryA : enchantmentsA.entrySet()) {
					var idA = entryA.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
					int levelA = entryA.getIntValue();

					boolean found = false;
					for (var entryB : enchantmentsB.entrySet()) {
						var idB = entryB.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
						if (idA != null && idB != null && idA.equals(idB) && levelA == entryB.getIntValue()) {
							found = true;
							break;
						}
					}
					if (!found) return false;
				}
			}

			if (!ItemStack.isSameItem(oa.getBaseCostA(), ob.getBaseCostA())
					|| oa.getBaseCostA().getCount() != ob.getBaseCostA().getCount()) return false;
			if (!ItemStack.isSameItem(oa.getCostB(), ob.getCostB())
					|| oa.getCostB().getCount() != ob.getCostB().getCount()) return false;
		}

		return true;
	}
}
