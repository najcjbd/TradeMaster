package dev.trademaster.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Render-context compatibility wrapper.
 *
 * <p>1.20 introduced {@code GuiGraphics}; before that the render methods thread a
 * {@code PoseStack}. The shared render bodies work against this wrapper on every version;
 * each version-gated override wraps the context it receives. The transform push/pop lives
 * here too, because the method names and stack type differ across eras.
 */
public final class McGraphics {

	//? if >=1.20 {
	private final net.minecraft.client.gui.GuiGraphics delegate;

	public McGraphics(net.minecraft.client.gui.GuiGraphics delegate) {
		this.delegate = delegate;
	}
	//?} else {
	/*private final com.mojang.blaze3d.vertex.PoseStack pose;

	public McGraphics(com.mojang.blaze3d.vertex.PoseStack pose) {
		this.pose = pose;
	}*/
	//?}

	public void fill(int x0, int y0, int x1, int y1, int color) {
		//? if >=1.20 {
		delegate.fill(x0, y0, x1, y1, color);
		//?} else {
		/*net.minecraft.client.gui.GuiComponent.fill(this.pose, x0, y0, x1, y1, color);*/
		//?}
	}

	public void drawString(Font font, Component text, int x, int y, int color) {
		//? if >=1.20 {
		delegate.drawString(font, text, x, y, color);
		//?} else {
		/*font.drawShadow(this.pose, text, x, y, color);*/
		//?}
	}

	public void drawString(Font font, String text, int x, int y, int color) {
		//? if >=1.20 {
		delegate.drawString(font, text, x, y, color);
		//?} else {
		/*font.drawShadow(this.pose, text, x, y, color);*/
		//?}
	}

	public void drawString(Font font, FormattedCharSequence text, int x, int y, int color) {
		//? if >=1.20 {
		delegate.drawString(font, text, x, y, color);
		//?} else {
		/*font.drawShadow(this.pose, text, x, y, color);*/
		//?}
	}

	/** Renders a child widget/drawable with this context's underlying render state. */
	public void renderWidget(Object widget, int mouseX, int mouseY, float delta) {
		//? if >=1.20 {
		((net.minecraft.client.gui.components.Renderable) widget).render(delegate, mouseX, mouseY, delta);
		//?} else {
		/*((net.minecraft.client.gui.components.Widget) widget).render(this.pose, mouseX, mouseY, delta);*/
		//?}
	}

	public void pushPose() {
		//? if >=1.21.8 {
		/*delegate.pose().pushMatrix();*/
		//?} elif >=1.20 {
		delegate.pose().pushPose();
		//?} else {
		/*this.pose.pushPose();*/
		//?}
	}

	public void popPose() {
		//? if >=1.21.8 {
		/*delegate.pose().popMatrix();*/
		//?} elif >=1.20 {
		delegate.pose().popPose();
		//?} else {
		/*this.pose.popPose();*/
		//?}
	}
}
