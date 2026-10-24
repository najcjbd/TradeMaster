package dev.trademaster.gui;

import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RerollController;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * In-game position capture. Two ways:
 * - from the settings tab: begin(slot, setter), then right-click a block;
 * - via the maLiLib hotkey: captures the looked-at block into the first
 *   unset slot (LECTERN -> SUMMON_BUTTON -> SEND_AWAY_BUTTON).
 */
public final class PositionPicker {

	public enum Slot { LECTERN, SUMMON_BUTTON, SEND_AWAY_BUTTON, DISPENSER, CHEST, GRINDSTONE }

	private static @Nullable Slot pendingSlot = null;
	private static @Nullable Consumer<BlockPos> pendingSetter = null;
	/** Optional check run before a captured position is accepted (duplicate chest);
	 * a rejected capture keeps the pick armed. */
	private static @Nullable java.util.function.Predicate<BlockPos> pendingValidator = null;
	/** Dimension the pick was armed in: coordinates captured in another dimension
	 * would point the rig at unreachable blocks. */
	private static @Nullable net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> armedDimension = null;

	private PositionPicker() {}

	/** Arms the picker. @return true when armed — false when refused (a run is
	 * in progress), in which case the caller must NOT leave the settings screen. */
	public static boolean begin(Slot slot, Consumer<BlockPos> setter) {
		return begin(slot, setter, null);
	}

	public static boolean begin(Slot slot, Consumer<BlockPos> setter,
			@Nullable java.util.function.Predicate<BlockPos> validator) {
		// same PASS-callback problem as the hotkey path: an armed pick during a
		// run would silently never capture, so refuse to arm it
		if (RerollController.isRunning()) {
			message(Component.translatable("trademaster.pick.busy").withStyle(ChatFormatting.RED));
			return false;
		}
		boolean replacing = pendingSlot != null;
		pendingSlot = slot;
		pendingSetter = setter;
		pendingValidator = validator;
		armedDimension = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.dimension() : null;
		message(Component.translatable(replacing ? "trademaster.pick.replaced" : "trademaster.pick.prompt")
				.withStyle(ChatFormatting.YELLOW));
		return true;
	}

	public static void onHotkey() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		// cancelling an armed pick must work even mid-run: the capture callbacks
		// PASS while running, so otherwise the pick could never be dismissed
		if (pendingSlot != null) {
			cancel();
			return;
		}
		// while running the use callbacks PASS, so an armed pick would silently
		// never capture; a capture mid-run could also corrupt the rig positions
		if (RerollController.isRunning()) {
			message(Component.translatable("trademaster.pick.busy").withStyle(ChatFormatting.RED));
			return;
		}
		HitResult hit = client.player.pick(5.0, 0.0F, false);
		BlockPos pos = null;
		if (hit instanceof BlockHitResult blockHit) {
			pos = blockHit.getBlockPos();
		} else if (hit instanceof EntityHitResult entityHit) {
			pos = entityHit.getEntity().blockPosition();
		}
		if (pos == null) {
			message(Component.translatable("trademaster.pick.prompt").withStyle(ChatFormatting.RED));
			return;
		}
		Slot slot = nextSlotWithNullPosition();
		if (slot == null) {
			message(Component.translatable("trademaster.pick.all_set").withStyle(ChatFormatting.RED));
			return;
		}
		applyTo(slot, pos);
	}

	public static InteractionResult onBlockUsed(BlockPos pos) {
		if (pendingSlot == null || pendingSetter == null) return InteractionResult.PASS;
		Minecraft client = Minecraft.getInstance();
		if (client.level != null && armedDimension != null && client.level.dimension() != armedDimension) {
			// coordinates from another dimension would brick the rig: refuse and
			// keep the pick armed so the user can return and capture properly
			message(Component.translatable("trademaster.pick.wrong_dimension").withStyle(ChatFormatting.RED));
			return InteractionResult.PASS;
		}
		if (pendingValidator != null && !pendingValidator.test(pos.immutable())) {
			message(Component.translatable("trademaster.pick.rejected").withStyle(ChatFormatting.RED));
			return InteractionResult.FAIL;
		}
		pendingSetter.accept(pos.immutable());
		TradeMasterConfig.INSTANCE.save();
		message(Component.translatable("trademaster.pick.captured", pos.toShortString()).withStyle(ChatFormatting.GREEN));
		pendingSlot = null;
		pendingSetter = null;
		pendingValidator = null;
		armedDimension = null;
		return InteractionResult.FAIL;
	}

	public static InteractionResult onEntityUsed(Entity entity) {
		if (pendingSlot == null || pendingSetter == null) return InteractionResult.PASS;
		return onBlockUsed(entity.blockPosition());
	}

	public static boolean isActive() {
		return pendingSlot != null;
	}

	public static void cancel() {
		if (pendingSlot == null) return;
		pendingSlot = null;
		pendingSetter = null;
		pendingValidator = null;
		armedDimension = null;
		message(Component.translatable("trademaster.pick.cancelled").withStyle(ChatFormatting.RED));
	}

	/** An armed pick met the running pipeline: drop it and say why — the use
	 * callbacks PASS while running, so without this the user would arm picks
	 * that silently never capture. */
	public static void cancelBecauseRunning() {
		if (pendingSlot == null) return;
		pendingSlot = null;
		pendingSetter = null;
		pendingValidator = null;
		armedDimension = null;
		message(Component.translatable("trademaster.pick.cancelled_running").withStyle(ChatFormatting.RED));
	}

	private static @Nullable Slot nextSlotWithNullPosition() {
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		// the hotkey only offers the three REQUIRED positions: silently capturing whatever
		// block the user happens to be looking at into an OPTIONAL slot (dispenser /
		// grindstone / chest) would surprise them and can stop the next run. Those are set
		// from their own rows' pick buttons; applyTo's cases for them are the slot -> config
		// mapping and are simply not reachable from the hotkey.
		if (c.lecternPos == null) return Slot.LECTERN;
		if (c.summonButtonPos == null) return Slot.SUMMON_BUTTON;
		if (c.sendAwayButtonPos == null) return Slot.SEND_AWAY_BUTTON;
		return null;
	}

	private static void applyTo(Slot slot, BlockPos pos) {
		if (pendingValidator != null && !pendingValidator.test(pos)) {
			message(Component.translatable("trademaster.pick.rejected").withStyle(ChatFormatting.RED));
			return;
		}
		TradeMasterConfig c = TradeMasterConfig.INSTANCE;
		switch (slot) {
			case LECTERN -> c.lecternPos = pos;
			case SUMMON_BUTTON -> c.summonButtonPos = pos;
			case SEND_AWAY_BUTTON -> c.sendAwayButtonPos = pos;
			case DISPENSER -> c.dispenserPos = pos;
			case CHEST -> {
				// unreachable from the hotkey today (it only yields the three required
				// positions), but this mapping must keep the [+] picker's invariants:
				// the MAX_CHESTS cap and no duplicates
				if (!c.addChest(pos.immutable())) {
					message(Component.translatable("trademaster.pick.rejected").withStyle(ChatFormatting.RED));
					return;
				}
			}
			case GRINDSTONE -> c.grindstonePos = pos;
		}
		c.save();
		message(Component.translatable("trademaster.pick.captured", pos.toShortString()).withStyle(ChatFormatting.GREEN));
	}

	private static void message(Component text) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal("[Trade Master] ").append(text));
		}
	}
}
