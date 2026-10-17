package dev.trademaster.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Version-gated text helpers.
 *
 * <p>{@code Component.translatable}/{@code literal} were added at 1.19 (before that the
 * component classes are constructed directly), and {@code filterText} moved from
 * {@code SharedConstants} to {@code StringUtil} at 1.21.
 */
public final class McText {

	public static MutableComponent translatable(String key, Object... args) {
		//? if >=1.19 {
		return Component.translatable(key, args);
		//?} else {
		/*return new net.minecraft.network.chat.TranslatableComponent(key, args);*/
		//?}
	}

	public static MutableComponent literal(String text) {
		//? if >=1.19 {
		return Component.literal(text);
		//?} else {
		/*return new net.minecraft.network.chat.TextComponent(text);*/
		//?}
	}

	/** An empty component. {@code Component.empty()} exists from 1.19. */
	public static Component empty() {
		//? if >=1.19 {
		return Component.empty();
		//?} else {
		/*return new net.minecraft.network.chat.TextComponent("");*/
		//?}
	}

	/** The translation key of a translatable component, or null for other components (the
	 * component-contents model differs completely before 1.19). */
	public static String translationKey(Component component) {
		//? if >=1.19 {
		return component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents contents
				? contents.getKey() : null;
		//?} else {
		/*return component instanceof net.minecraft.network.chat.TranslatableComponent translatable
				? translatable.getKey() : null;*/
		//?}
	}

	/** Strips {@code §} formatting codes from the text. */
	public static String filterText(String text) {
		//? if >=1.21 {
		return net.minecraft.util.StringUtil.filterText(text);
		//?} else {
		/*return net.minecraft.SharedConstants.filterText(text);*/
		//?}
	}

	private McText() {}
}
