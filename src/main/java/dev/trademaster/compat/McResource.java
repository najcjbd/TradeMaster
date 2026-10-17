package dev.trademaster.compat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;

/** Version-gated resource opening. {@code ResourceManager.open} exists from 1.19; before
 * that the {@code Resource} is fetched first and opened. */
public final class McResource {

	public static InputStream open(ResourceManager manager, ResourceLocation id) throws IOException {
		//? if >=1.19 {
		return manager.open(id);
		//?} else {
		/*return manager.getResource(id).getInputStream();*/
		//?}
	}

	private McResource() {}
}
