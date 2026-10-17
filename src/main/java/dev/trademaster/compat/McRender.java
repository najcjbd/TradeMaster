package dev.trademaster.compat;

/** Version-gated GUI transform helpers, delegating to {@link McGraphics}, which hides the
 * change from PoseStack+pushPose to Matrix3x2fStack+pushMatrix (1.21.8). */
public final class McRender {

	/** Pushes the GUI transform. */
	public static void pushPose(McGraphics context) {
		context.pushPose();
	}

	/** Pops the GUI transform. */
	public static void popPose(McGraphics context) {
		context.popPose();
	}

	private McRender() {}
}
