package dev.trademaster.compat;

import net.minecraft.client.gui.components.events.GuiEventListener;
//? if >=26.1 {
/*import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;*/
//?}

/**
 * Version-gated GUI input forwarding.
 *
 * <p>26.1 replaced the primitive event signatures on {@code GuiEventListener} with event
 * objects ({@code KeyEvent}, {@code CharacterEvent}, {@code MouseButtonEvent}), and the
 * IME {@code preeditUpdated(PreeditEvent)} hook is 26.1+ only. Shared code keeps its
 * override BODIES version-independent by working with primitives and forwarding through
 * here; only the override signatures are gated per file.
 */
public final class McInput {

	/** Forwards a key-press to {@code target} (a focused widget). */
	public static boolean keyPressed(GuiEventListener target, int keyCode, int scanCode, int modifiers) {
		//? if >=26.1 {
		/*return target.keyPressed(new KeyEvent(keyCode, scanCode, modifiers));*/
		//?} else {
		return target.keyPressed(keyCode, scanCode, modifiers);
		//?}
	}

	/** Forwards a key-release to {@code target}. */
	public static boolean keyReleased(GuiEventListener target, int keyCode, int scanCode, int modifiers) {
		//? if >=26.1 {
		/*return target.keyReleased(new KeyEvent(keyCode, scanCode, modifiers));*/
		//?} else {
		return target.keyReleased(keyCode, scanCode, modifiers);
		//?}
	}

	/** Forwards a typed character to {@code target}. */
	public static boolean charTyped(GuiEventListener target, char c, int modifiers) {
		//? if >=26.1 {
		/*return target.charTyped(new CharacterEvent((int) c));*/
		//?} else {
		return target.charTyped(c, modifiers);
		//?}
	}

	/** Forwards a mouse click to {@code target}. {@code doubled} (26.1+: a double-click
	 * for word selection) is dropped on the older branches, whose EditBox tracks the
	 * double-click itself. */
	public static boolean mouseClicked(GuiEventListener target, double x, double y, int button, boolean doubled) {
		//? if >=26.1 {
		/*return target.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), doubled);*/
		//?} else {
		return target.mouseClicked(x, y, button);
		//?}
	}

	/** Forwards a mouse release to {@code target}. */
	public static boolean mouseReleased(GuiEventListener target, double x, double y, int button) {
		//? if >=26.1 {
		/*return target.mouseReleased(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)));*/
		//?} else {
		return target.mouseReleased(x, y, button);
		//?}
	}

	/** Forwards a mouse drag to {@code target}. */
	public static boolean mouseDragged(GuiEventListener target, double x, double y, int button,
			double dragX, double dragY) {
		//? if >=26.1 {
		/*return target.mouseDragged(new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0)), dragX, dragY);*/
		//?} else {
		return target.mouseDragged(x, y, button, dragX, dragY);
		//?}
	}

	/** Whether {@code c} is allowed in a chat/name field (the CharacterEvent filter on
	 * 26.1+; {@code SharedConstants} before it). */
	public static boolean isAllowedChatCharacter(char c) {
		//? if >=26.1 {
		/*return new CharacterEvent((int) c).isAllowedChatCharacter();*/
		//?} elif >=1.21 {
		return net.minecraft.util.StringUtil.isAllowedChatCharacter(c);
		//?} else {
		/*return net.minecraft.SharedConstants.isAllowedChatCharacter(c);*/
		//?}
	}

	/** The low-level key code of a 26.1+ KeyEvent: {@code keycode()} from 26.3, earlier
	 * {@code scancode()}. Only called from the 26.1+ branches (the fallback is unreachable). */
	public static int scancode(Object event) {
		//? if >=26.3 {
		/*return ((net.minecraft.client.input.KeyEvent) event).keycode();*/
		//?} elif >=26.1 {
		/*return ((net.minecraft.client.input.KeyEvent) event).scancode();*/
		//?} else {
		throw new IllegalStateException("no KeyEvent before 26.1");
		//?}
	}

	private McInput() {}
}
