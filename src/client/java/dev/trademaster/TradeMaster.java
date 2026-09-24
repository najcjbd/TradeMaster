package dev.trademaster;

import dev.trademaster.config.MaliLibAdapter;
import dev.trademaster.config.TradeMasterConfig;
import dev.trademaster.core.RerollController;
import dev.trademaster.gui.PositionPicker;
import dev.trademaster.gui.TradeMasterScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.enchantment.Enchantment;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TradeMaster implements ClientModInitializer {
	public static final String MOD_ID = "trade-master";
	public static final Logger LOGGER = LoggerFactory.getLogger("trade-master");
	// explicit non-vanilla namespace: Identifier.parse would default to "minecraft",
	// making the category minecraft:trade-master and squatting the vanilla namespace
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("trademaster", "main"));

	private static KeyMapping openMenuKey;

	@Override
	public void onInitializeClient() {
		MaliLibAdapter.register();
		dev.trademaster.compat.ClientCommandsIntegration.probe();

		openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.trade-master.open_menu", GLFW.GLFW_KEY_V, CATEGORY));

		TradeMasterCommand.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			RerollController.tick(client);
			while (openMenuKey.consumeClick()) {
				// the screen's start button and lists assume an in-game world:
				// don't open it from the title/menu screens
				if (client.gui.screen() == null && client.player != null && client.level != null) {
					client.gui.setScreen(new TradeMasterScreen(null));
				}
			}
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(ConfigLoad::reloadEnchantments));

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

		LOGGER.info("Trade Master initialized");
	}

	public static KeyMapping getOpenMenuKey() {
		return openMenuKey;
	}

	/** Loads config from disk and merges the enchantment table with the live registry. */
	public static final class ConfigLoad {
		private static boolean loadedOnce = false;

		public static void initialLoad() {
			if (loadedOnce) return;
			loadedOnce = true;
			dev.trademaster.config.ConfigPersistence.load(TradeMasterConfig.INSTANCE);
		}

		public static void reloadEnchantments() {
			initialLoad();
			Minecraft client = Minecraft.getInstance();
			if (client.level != null) {
				var registry = client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
				TradeMasterConfig.INSTANCE.syncEnchantmentsFromRegistry((net.minecraft.core.Registry<Enchantment>) registry);
			}
		}
	}
}
