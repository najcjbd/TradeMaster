package dev.trademaster.exec;

import dev.trademaster.anticheat.SmoothRotation;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.Interactions;
import dev.trademaster.core.RunSession;
import dev.trademaster.matching.TradeMatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.AnvilBlock;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Names the matched villager with an anvil + name tag, then applies it.
 * Chain (ported conceptually from LPCTools, LGPLv3): open anvil -> insert
 * name tag -> rename -> take result -> equip -> interact with villager.
 * Any failure (no anvil, no XP, full inventory...) degrades gracefully:
 * the controller skips naming and sends the villager away. Every failure
 * records a machine-readable reason via {@link #failReason()} so the chat
 * message says which stage broke.
 */
public class NamingExecutor {

	public enum Result { RUNNING, DONE, FAILED }

	private enum Stage { FIND_ANVIL, OPEN_ANVIL, INSERT_TAG, RENAME, TAKE, CLOSE, EQUIP, APPLY, VERIFY, RESTORE }

	private final RunSession session;
	/** Composed name tag text, or null when it exceeds the anvil limit / is unsafe:
	 * the first tick then fails fast with the recorded failReason. */
	private final @Nullable String name;
	/** One record per matched enchantment in the composed name (display name
	 * resolved at construction time, no roman level); recorded into nameHistory so
	 * deductNamedVillagers can resolve them even after language/custom-name changes. */
	public record NameRecord(String displayName, Identifier id) {}
	private final List<NameRecord> nameRecords;
	private Stage stage = Stage.FIND_ANVIL;
	private int stageTicks = 0;
	private @Nullable BlockPos anvilPos = null;
	private int tagInvSlot = -1;
	private int resultInvSlot = -1;
	/** Inventory slot swapped against the selected hotbar slot during EQUIP, if any. */
	private int equipSwapSlot = -1;
	/** Hotbar slot selected before EQUIP switched to a hotbar name tag; restored afterwards. */
	private int equipOriginalSlot = -1;
	/** Whether the RESTORE swap-back click has been sent. */
	private boolean restoreSwapped = false;
	/** Failure reason held while RESTORE runs before reporting FAILED. */
	private @Nullable String pendingFail = null;
	private int insertAttempts = 0;
	private String failReason = "unknown";

	public NamingExecutor(RunSession session, TradeMatcher.MatchedTrade trade) {
		this.session = session;
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		List<NameRecord> records = new ArrayList<>();
		StringBuilder composed = new StringBuilder();
		// one segment per qualifying book: its matched enchantments joined by "+",
		// then a space and the book's price; books separated by spaces, listed in
		// trade-list order (the match sorts by price, the name follows the menu)
		List<TradeMatcher.MatchedBook> namedBooks = new ArrayList<>(trade.books());
		namedBooks.sort(Comparator.comparingInt(TradeMatcher.MatchedBook::offerIndex));
		for (TradeMatcher.MatchedBook book : namedBooks) {
			if (!composed.isEmpty()) composed.append(' ');
			boolean firstPart = true;
			for (TradeMatcher.MatchPart part : book.parts()) {
				String translationKey = "enchantment." + part.id().getNamespace() + "." + part.id().getPath();
				String displayName = EnchantmentNamer.displayName(part.id(), translationKey);
				if (records.stream().noneMatch(r -> r.id().equals(part.id()))) {
					records.add(new NameRecord(displayName, part.id()));
				}
				if (!firstPart) composed.append('+');
				composed.append(displayName).append(' ').append(EnchantmentNamer.romanNumerals(part.level()));
				firstPart = false;
			}
			composed.append(' ').append(config.forceNamePriceOne ? 1 : book.price());
		}
		this.nameRecords = List.copyOf(records);
		// the anvil caps names at 50 chars: a long multi-book combo cannot be named,
		// skip naming instead of wedging on a result slot that never fills
		if (!isAnvilSafeName(composed.toString())) {
			this.name = null;
			this.failReason = "name-too-long";
		} else {
			this.name = composed.toString();
		}
	}

	/** The matched enchantments named onto the tag (see field javadoc). */
	public List<NameRecord> nameRecords() {
		return this.nameRecords;
	}

	public Result tick() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) return failed("player-null");
		// null only when construction rejected the name (anvil length cap): fail fast
		// instead of waiting 40 ticks on a never-filling result slot
		if (this.name == null) return failed(this.failReason);
		// the anvil rejects (or silently strips) names the server does not accept
		// verbatim: fail fast instead of waiting 40 ticks on a never-filling result
		// slot and reporting a misleading "rename-cost"
		if (!isAnvilSafeName(this.name)) return failed("invalid-name");
		this.stageTicks++;
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.stageTicks > 200) return failed("timeout:" + this.stage.name());

		return switch (this.stage) {
			case FIND_ANVIL -> tickFindAnvil(mc, player);
			case OPEN_ANVIL -> tickOpenAnvil(mc, player);
			case INSERT_TAG -> tickInsertTag(mc, player);
			case RENAME -> tickRename(mc, player);
			case TAKE -> tickTake(mc, player);
			case CLOSE -> tickClose(mc, player);
			case EQUIP -> tickEquip(mc, player);
			case APPLY -> tickApply(mc, player);
			case VERIFY -> tickVerify(player);
			case RESTORE -> tickRestore(mc, player);
		};
	}

	private Result failed(String reason) {
		this.failReason = reason;
		// once the EQUIP swap packet is out, a moved item (e.g. the axe) sits in
		// the tag's old slot: cover EQUIP too, because equip-hand fails right after
		// that packet on a slow sync — swap it back before reporting failure
		if ((this.equipSwapSlot >= 0 || this.equipOriginalSlot >= 0)
				&& (this.stage == Stage.EQUIP || this.stage == Stage.APPLY || this.stage == Stage.VERIFY)
				&& this.pendingFail == null) {
			this.pendingFail = reason;
			next(Stage.RESTORE);
			return Result.RUNNING;
		}
		return Result.FAILED;
	}

	/** Machine-readable reason for the last FAILED result (e.g. "tag-insert"). */
	public String failReason() {
		return this.failReason;
	}

	/** True while the swap-back runs; the phase timeout must not discard it mid-flight. */
	public boolean isRestoring() {
		return this.stage == Stage.RESTORE;
	}

	/**
	 * Stop-time cleanup: the EQUIP swap already moved an item out of the selected
	 * hotbar slot but RESTORE never got to send the swap-back — fire it now so
	 * the displaced item (e.g. the axe) is not stranded.
	 */
	public void abortCleanly() {
		// naming is the only SmoothRotation producer; a stop-time discard must not
		// leak the stale "rotated" flag into the next phase's LookStep
		SmoothRotation.reset();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) return;
		if (this.equipOriginalSlot >= 0) {
			Interactions.selectHotbarSlot(player, this.equipOriginalSlot);
			this.equipOriginalSlot = -1;
		}
		if (mc.gameMode == null || this.equipSwapSlot < 0 || this.restoreSwapped) return;
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		mc.gameMode.handleContainerInput(0, this.equipSwapSlot,
				player.getInventory().getSelectedSlot(), ContainerInput.SWAP, player);
	}

	private void next(Stage stage) {
		this.stage = stage;
		this.stageTicks = 0;
	}

	private Result tickFindAnvil(Minecraft mc, LocalPlayer player) {
		this.anvilPos = findAnvil(mc, player.blockPosition(), player.getEyePosition());
		if (this.anvilPos == null) return failed("anvil-missing");
		next(Stage.OPEN_ANVIL);
		return Result.RUNNING;
	}

	private static @Nullable BlockPos findAnvil(Minecraft mc, BlockPos center, Vec3 eye) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		// ±5 vertically: an anvil up to ~3 blocks ABOVE the eye is still openable
		// (eye sits 1.62 into the player's block), the old ±3 missed those
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -5, -5), center.offset(5, 5, 5))) {
			if (!(mc.level.getBlockState(pos).getBlock() instanceof AnvilBlock)) continue;
			Vec3 anvilCenter = Vec3.atCenterOf(pos);
			// the server's reach check runs eye→hit point (4.5 in survival),
			// and the open click hits exactly the block center: eye→center
			// must satisfy the 4.5 reach itself, no margin. A wrong pick here
			// is silent — every open attempt gets rejected until the stage
			// times out
			if (eye.distanceToSqr(anvilCenter) > 4.5 * 4.5) continue;
			double eyeDistance = eye.distanceToSqr(anvilCenter);
			if (eyeDistance < bestDistance) {
				bestDistance = eyeDistance;
				best = pos.immutable();
			}
		}
		return best;
	}

	private Result tickOpenAnvil(Minecraft mc, LocalPlayer player) {
		if (player.containerMenu instanceof AnvilMenu) {
			next(Stage.INSERT_TAG);
			return Result.RUNNING;
		}
		if (TradeMasterConfig.INSTANCE.phaseTimeoutsEnabled && this.stageTicks > 40) return failed("anvil-open");
		if (this.stageTicks % 5 != 1) return Result.RUNNING;

		BlockPos anvil = this.anvilPos;
		Vec3 aim = Vec3.atCenterOf(anvil);
		TradeMasterConfig config = TradeMasterConfig.INSTANCE;
		if (config.legitMode && config.slowMode) {
			SmoothRotation.snapLookAt(aim);
		} else if (config.legitMode) {
			player.lookAt(EntityAnchorArgument.Anchor.EYES, aim);
		}
		BlockHitResult hit = new BlockHitResult(aim, Direction.UP, anvil, false);
		mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		return Result.RUNNING;
	}

	private Result tickInsertTag(Minecraft mc, LocalPlayer player) {
		if (!(player.containerMenu instanceof AnvilMenu menu)) return failed("tag-menu");
		if (this.tagInvSlot < 0) {
			this.tagInvSlot = findNameTag(player);
			if (this.tagInvSlot < 0) return failed("tag-none");
		}
		int menuSlot = containerSlot(3, this.tagInvSlot);
		// Insert exactly one tag (LPCTools-style): right-click picks up half the
		// stack, right-clicking slot 0 deposits a single item, leftovers go back.
		// Container clicks race with the server's state id, so space them three
		// ticks apart and verify generously; retry the whole sequence a few
		// times before giving up.
		if (this.stageTicks == 1) {
			mc.gameMode.handleContainerInput(menu.containerId, menuSlot, 1, ContainerInput.PICKUP, player);
			return Result.RUNNING;
		}
		if (this.stageTicks == 4) {
			mc.gameMode.handleContainerInput(menu.containerId, 0, 1, ContainerInput.PICKUP, player);
			return Result.RUNNING;
		}
		if (this.stageTicks == 7) {
			if (!menu.getCarried().isEmpty()) {
				mc.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.PICKUP, player);
			}
			return Result.RUNNING;
		}
		if (this.stageTicks < 12) return Result.RUNNING;
		ItemStack inSlot = menu.getSlot(0).getItem();
		if (inSlot.is(Items.NAME_TAG) && inSlot.getCount() == 1 && menu.getCarried().isEmpty()) {
			next(Stage.RENAME);
			return Result.RUNNING;
		}
		if (this.insertAttempts >= 3) return failed("tag-insert");
		this.insertAttempts++;
		this.tagInvSlot = -1;
		// a failed attempt can leave residue behind (state id race): carried tags
		// go back to their inventory slot, a polluted slot 0 gets quick-moved out —
		// otherwise the next attempt stacks onto the residue and the count==1
		// check below fails forever
		if (!menu.getCarried().isEmpty()) {
			mc.gameMode.handleContainerInput(menu.containerId, menuSlot, 0, ContainerInput.PICKUP, player);
		} else if (!menu.getSlot(0).getItem().isEmpty()) {
			mc.gameMode.handleContainerInput(menu.containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
		}
		this.stageTicks = -2; // let the cleanup click settle before the retry starts
		return Result.RUNNING;
	}

	/** {@code mc} is unused (kept for call-site parity) — audited 2026-09-19. */
	private Result tickRename(Minecraft mc, LocalPlayer player) {
		if (!(player.containerMenu instanceof AnvilMenu menu)) return failed("rename-menu");
		if (this.stageTicks == 1 || this.stageTicks == 20) {
			menu.setItemName(this.name);
			player.connection.send(new ServerboundRenameItemPacket(this.name));
			return Result.RUNNING;
		}
		// Result slot is filled by a server roundtrip; accept it early, but only
		// declare failure (no XP / rename rejected) after a generous wait with a
		// second rename packet sent.
		if (this.stageTicks >= 5 && !menu.getSlot(2).getItem().isEmpty()) {
			next(Stage.TAKE);
			return Result.RUNNING;
		}
		if (this.stageTicks < 40) return Result.RUNNING;
		return failed("rename-cost");
	}

	private Result tickTake(Minecraft mc, LocalPlayer player) {
		if (!(player.containerMenu instanceof AnvilMenu menu)) return failed("take-menu");
		// the result slot fills even without enough XP, but mayPickup would reject
		// the take on both sides: report the real reason instead of a dead 20-tick
		// wait ending in a misleading "take-tag"
		if (!player.hasInfiniteMaterials() && player.experienceLevel < menu.getCost()) return failed("xp-cost");
		if (this.stageTicks == 1) {
			mc.gameMode.handleContainerInput(menu.containerId, 2, 0, ContainerInput.QUICK_MOVE, player);
			return Result.RUNNING;
		}
		if (this.stageTicks < 4) return Result.RUNNING;
		this.resultInvSlot = findNamedTag(player);
		if (this.resultInvSlot >= 0) {
			next(Stage.CLOSE);
			return Result.RUNNING;
		}
		// the slot sync can lag a few ticks behind the local click
		if (this.stageTicks < 20) return Result.RUNNING;
		return failed("take-tag");
	}

	/** {@code mc} is unused (kept for call-site parity) — audited 2026-09-19. */
	private Result tickClose(Minecraft mc, LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
			return Result.RUNNING;
		}
		this.resultInvSlot = findNamedTag(player);
		if (this.resultInvSlot >= 0) {
			next(Stage.EQUIP);
			return Result.RUNNING;
		}
		if (this.stageTicks < 20) return Result.RUNNING;
		return failed("close-tag");
	}

	private Result tickEquip(Minecraft mc, LocalPlayer player) {
		if (player.containerMenu != player.inventoryMenu) return failed("equip-menu");
		if (this.resultInvSlot < 9) {
			// Already in the hotbar: just select it.
			if (player.getInventory().getSelectedSlot() != this.resultInvSlot) {
				this.equipOriginalSlot = player.getInventory().getSelectedSlot();
				Interactions.selectHotbarSlot(player, this.resultInvSlot);
			}
			this.equipSwapSlot = -1;
			next(Stage.APPLY);
			return Result.RUNNING;
		}
		if (this.stageTicks == 1) {
			// SWAP the renamed tag straight into the selected hotbar slot so it
			// ends up in the main hand. Unlike the old PICKUP approach this works
			// with a full hotbar and never leaves the tag stuck on the cursor;
			// the previous item lands in the tag's old slot and is swapped back
			// after naming.
			this.equipSwapSlot = this.resultInvSlot;
			mc.gameMode.handleContainerInput(0, this.resultInvSlot,
					player.getInventory().getSelectedSlot(), ContainerInput.SWAP, player);
			return Result.RUNNING;
		}
		if (this.stageTicks < 4) return Result.RUNNING;
		if (!player.getMainHandItem().is(Items.NAME_TAG)) return failed("equip-hand");
		next(Stage.APPLY);
		return Result.RUNNING;
	}

	private Result tickApply(Minecraft mc, LocalPlayer player) {
		if (this.session.villager == null || !this.session.villager.isAlive()) return failed("apply-villager");
		if (this.stageTicks == 1) {
			if (!player.getMainHandItem().is(Items.NAME_TAG)) return failed("apply-hand");
			if (TradeMasterConfig.INSTANCE.legitMode) {
				Vec3 eyes = this.session.villager.getEyePosition();
				if (TradeMasterConfig.INSTANCE.slowMode) SmoothRotation.snapLookAt(eyes);
				else player.lookAt(EntityAnchorArgument.Anchor.EYES, eyes);
			}
			Interactions.interactEntity(mc, player, this.session.villager);
			return Result.RUNNING;
		}
		// The interact consumes the tag server-side; once the inventory sync lands
		// the main hand is no longer a name tag. That is success, not failure —
		// VERIFY confirms via the villager's custom name.
		if (!player.getMainHandItem().is(Items.NAME_TAG) || this.stageTicks >= 6) {
			next(Stage.VERIFY);
		}
		return Result.RUNNING;
	}

	private Result tickVerify(LocalPlayer player) {
		// strict on purpose: only the matching custom name proves the interact
		// landed. A name tag missing from the main hand has other explanations
		// (slot switch, desync) and must not count as success.
		if (this.session.villager != null && this.session.villager.getCustomName() != null
				&& this.name.equals(this.session.villager.getCustomName().getString())) {
			next(Stage.RESTORE);
			return Result.RUNNING;
		}
		// unconditional on purpose: with phaseTimeoutsEnabled off a rejected
		// interact would otherwise loop here forever
		if (this.stageTicks > 40) return failed("verify-timeout");
		return Result.RUNNING;
	}

	private Result tickRestore(Minecraft mc, LocalPlayer player) {
		if (this.equipSwapSlot < 0) {
			if (this.equipOriginalSlot >= 0) {
				Interactions.selectHotbarSlot(player, this.equipOriginalSlot);
				this.equipOriginalSlot = -1;
			}
			return this.pendingFail != null ? Result.FAILED : Result.DONE;
		}
		// a leftover open menu would reject the swap click; close it first so the
		// previously swapped item always makes it back to the selected hotbar slot
		if (player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
			return Result.RUNNING;
		}
		if (!this.restoreSwapped) {
			this.restoreSwapped = true;
			// the tag was consumed by the naming interact; swap the previous
			// item (e.g. the axe) back into the selected hotbar slot
			mc.gameMode.handleContainerInput(0, this.equipSwapSlot,
					player.getInventory().getSelectedSlot(), ContainerInput.SWAP, player);
			return Result.RUNNING;
		}
		if (this.stageTicks < 4) return Result.RUNNING;
		this.equipSwapSlot = -1;
		return this.pendingFail != null ? Result.FAILED : Result.DONE;
	}

	// ---- helpers -------------------------------------------------------------

	/** Maps an inventory slot to a container slot given the container's own slot count. */
	private static int containerSlot(int containerSlots, int invSlot) {
		return containerSlots + (invSlot < 9 ? 27 + invSlot : invSlot - 9);
	}

	private static int findNameTag(LocalPlayer player) {
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			// skip tags that already carry a name: renaming a renamed tag accrues
			// anvil penalty and the cost climbs with every failed cycle
			if (stack.is(Items.NAME_TAG) && stack.getCustomName() == null) return i;
		}
		return -1;
	}

	/**
	 * Match by exact expected name: leftovers from a failed earlier cycle stay in
	 * the inventory with their old name, and grabbing "the first named tag" would
	 * equip the stale one and crown the villager with the wrong enchantment.
	 */
	private int findNamedTag(LocalPlayer player) {
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.is(Items.NAME_TAG) && stack.getCustomName() != null
					&& this.name.equals(stack.getCustomName().getString())) return i;
		}
		return -1;
	}

	/** Mirrors vanilla AnvilMenu.validateName: filterText strips format codes, 50 char cap. */
	private static boolean isAnvilSafeName(String name) {
		String filtered = StringUtil.filterText(name);
		return !filtered.isEmpty() && filtered.length() <= 50 && filtered.equals(name);
	}
}
