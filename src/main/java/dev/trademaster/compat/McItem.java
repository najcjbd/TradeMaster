package dev.trademaster.compat;


import dev.trademaster.compat.McItem;//? if >=1.20.5 {
import net.minecraft.core.component.DataComponents;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Version-gated item queries. 1.21.2 added {@code ItemStack.getCustomName()}; before
 * that the custom name is read from its data component. */
public final class McItem {

	/** The item's custom (anvil/name-tag) name, or null when it has none. */
	public static @Nullable Component customName(ItemStack stack) {
		//? if >=1.21.2 {
		return stack.getCustomName();
		//?} elif >=1.20.5 {
		/*return stack.get(DataComponents.CUSTOM_NAME);*/
		//?} else {
		/*net.minecraft.nbt.CompoundTag display = stack.getTagElement("display");
		if (display == null || !display.contains("Name", 8)) return null;
		return net.minecraft.network.chat.Component.Serializer.fromJson(display.getString("Name"));*/
		//?}
	}

	/** Whether two stacks are the same item with the same data. 1.20.5 compares data
	 * COMPONENTS; before that it compares the item's NBT tags. */
	public static boolean sameItemSameComponents(ItemStack a, ItemStack b) {
		//? if >=1.20.5 {
		return ItemStack.isSameItemSameComponents(a, b);
		//?} else {
		/*return ItemStack.isSameItemSameTags(a, b);*/
		//?}
	}

	/** Whether two stacks are the same item (data ignored). Before 1.19.3 only the
	 * item+NBT comparison existed. */
	public static boolean sameItem(ItemStack a, ItemStack b) {
		//? if >=1.20 {
		return ItemStack.isSameItem(a, b);
		//?} else {
		/*return a.getItem() == b.getItem();*/
		//?}
	}

	private McItem() {}
}
