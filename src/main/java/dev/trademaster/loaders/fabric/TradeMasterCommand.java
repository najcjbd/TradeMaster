//? if fabric && >=1.19.2 {
package dev.trademaster.loaders.fabric;


import dev.trademaster.compat.McText;import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.trademaster.core.RerollController;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommands;*/
//?} else {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
//?}
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** /trademaster (alias /tm): start | stop | status, bare command toggles. Fabric-only (the
 * command API is a loader API), so it lives under loaders/fabric. */
public final class TradeMasterCommand {

	private TradeMasterCommand() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(build("trademaster"));
			dispatcher.register(build("tm"));
		});
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = literal(name);
		root.executes(ctx -> {
			RerollController.toggle();
			return 1;
		});
		root.then(literal("start").executes(ctx -> {
			RerollController.start();
			return 1;
		}));
		root.then(literal("stop").executes(ctx -> {
			RerollController.stopManual();
			return 1;
		}));
		root.then(literal("status").executes(ctx -> {
			Component status = RerollController.isRunning()
					? McText.literal(RerollController.statusText()).withStyle(ChatFormatting.GREEN)
					: McText.translatable("trademaster.status.idle").withStyle(ChatFormatting.RED);
			ctx.getSource().sendFeedback(McText.literal("Trade Master: ").append(status));
			return 1;
		}));
		// confirms the lectern-sphere staging fallback that a refused start
		// asked for; a no-op (with a notice) when nothing is pending
		root.then(literal("goon").executes(ctx -> {
			RerollController.confirmRegionFallback();
			return 1;
		}));
		return root;
	}

	/** A command literal. 26.1 renamed {@code ClientCommandManager.literal} to
	 * {@code ClientCommands.literal}. */
	private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		//? if >=26.1 {
		/*return ClientCommands.literal(name);*/
		//?} else {
		return ClientCommandManager.literal(name);
		//?}
	}
}
//?}
