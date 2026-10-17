//? if fabric {
package dev.trademaster.loaders.fabric;

import dev.trademaster.TradeMasterClient;
import dev.trademaster.core.RerollController;
import dev.trademaster.gui.PositionPicker;
import dev.trademaster.platform.Platforms;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public class FabricClientEntrypoint implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		Platforms.install(new FabricPlatform());
		Platforms.get().registerKeybinds();
		// the client command API only exists from 1.19.2; 1.18.2 runs keys-only
		//? if >=1.19.2 {
		TradeMasterCommand.register();
		//?}
		registerClientEvents();
		TradeMasterClient.initialize();
	}

	private static void registerClientEvents() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				client.execute(TradeMasterClient.ConfigLoad::reloadEnchantments));

		// stop the pipeline on disconnect; leaving it running while the
		// integrated server tears down freezes the game; also drop any armed
		// position picker so it cannot capture a foreign block after rejoining
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				client.execute(() -> {
					PositionPicker.cancel();
					RerollController.shutdown();
				}));

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (hand == InteractionHand.OFF_HAND || hitResult == null || !world.isClientSide()) {
				return InteractionResult.PASS;
			}
			// the pipeline's own programmatic useItemOn calls (button presses,
			// lectern placement) also fire this callback; an armed picker would
			// hijack them, corrupt the stored rig positions and swallow the press
			if (RerollController.isRunning()) {
				PositionPicker.cancelBecauseRunning();
				return InteractionResult.PASS;
			}
			return PositionPicker.onBlockUsed(hitResult.getBlockPos());
		});

		UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (hand == InteractionHand.OFF_HAND || !world.isClientSide()) {
				return InteractionResult.PASS;
			}
			if (RerollController.isRunning()) {
				PositionPicker.cancelBecauseRunning();
				return InteractionResult.PASS;
			}
			return PositionPicker.onEntityUsed(entity);
		});

		// touch-reachable cancel for the position picker: punch (left-click) any
		// block or entity to abort a pending pick — the hotkey is unreachable on
		// touch-only clients, and without a cancel the very next right-click would
		// silently capture whatever the player interacts with
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (!world.isClientSide() || !PositionPicker.isActive()) return InteractionResult.PASS;
			PositionPicker.cancel();
			return InteractionResult.FAIL;
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (!world.isClientSide() || !PositionPicker.isActive()) return InteractionResult.PASS;
			PositionPicker.cancel();
			return InteractionResult.FAIL;
		});
	}
}
//?}
