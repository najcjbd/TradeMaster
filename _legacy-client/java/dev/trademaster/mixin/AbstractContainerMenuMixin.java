package dev.trademaster.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.trademaster.detection.DetectionHub;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Event detection channel, part 1: the merchant menu contents arrived. */
@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuMixin {

	@Inject(method = "initializeContents", at = @At("TAIL"))
	private void tradeMaster$onInitializeContents(CallbackInfo ci) {
		if (!RenderSystem.isOnRenderThread()) return;
		if ((Object) this instanceof MerchantMenu menu) {
			DetectionHub.onMerchantMenuOpened(menu);
		}
	}
}
