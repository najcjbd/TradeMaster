package dev.trademaster;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.trademaster.core.RerollController;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** /trademaster (alias /tm): start | stop | status, bare command toggles. */
public final class TradeMasterCommand {

	private TradeMasterCommand() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(build("trademaster"));
			dispatcher.register(build("tm"));
		});
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name) {
		return ClientCommands.literal(name)
				.executes(ctx -> {
					RerollController.toggle();
					return 1;
				})
				.then(ClientCommands.literal("start").executes(ctx -> {
					RerollController.start();
					return 1;
				}))
				.then(ClientCommands.literal("stop").executes(ctx -> {
					RerollController.stopManual();
					return 1;
				}))
				.then(ClientCommands.literal("status").executes(ctx -> {
					Component status = RerollController.isRunning()
							? Component.literal(RerollController.statusText()).withStyle(ChatFormatting.GREEN)
							: Component.translatable("trademaster.status.idle").withStyle(ChatFormatting.RED);
					ctx.getSource().sendFeedback(Component.literal("Trade Master: ").append(status));
					return 1;
				}))
				// confirms the lectern-sphere staging fallback that a refused start
				// asked for; a no-op (with a notice) when nothing is pending
				.then(ClientCommands.literal("goon").executes(ctx -> {
					RerollController.confirmRegionFallback();
					return 1;
				}));
	}
}
