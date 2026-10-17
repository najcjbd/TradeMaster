package dev.trademaster.compat;

//? if <26.3 {
import net.minecraft.world.item.AxeItem;
//?}
import net.minecraft.world.item.ItemStack;

/** Version-gated tool classification. */
public final class McTools {

	/** Whether {@code stack} is an axe. 26.3 removed the {@code AxeItem} class (tools became
	 * data-driven) and classifies axes by the vanilla {@code ItemTags.AXES} tag instead. */
	public static boolean isAxe(ItemStack stack) {
		//? if >=26.3 {
		/*return stack.is(net.minecraft.tags.ItemTags.AXES);*/
		//?} else {
		return stack.getItem() instanceof AxeItem;
		//?}
	}

	private McTools() {}
}
