package dev.trademaster.compat;


import dev.trademaster.compat.McBuiltIn;import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;

/**
 * Version-gated Villager-profession access.
 *
 * <p>26.1 changed the profession model twice over:
 * <ul>
 *   <li>{@code VillagerData.getProfession()} (a plain {@code VillagerProfession}) became
 *       {@code profession()} returning a {@code Holder<VillagerProfession>};</li>
 *   <li>the {@code VillagerProfession.LIBRARIAN/NONE/NITWIT} constants became
 *       {@code ResourceKey<VillagerProfession>} instead of {@code VillagerProfession}
 *       values.</li>
 * </ul>
 * Both are normalised here to the modern shape (a Holder and a ResourceKey), which the
 * shared code then uses uniformly. The package move (npc.villager) is handled globally by
 * a Stonecutter replacement, so the source writes the earlier package.
 */
public final class McVillager {

	/** The villager's profession, as a Holder on every version. */
	public static Holder<VillagerProfession> profession(Villager villager) {
		//? if >=1.21.5 {
		/*return villager.getVillagerData().profession();*/
		//?} else {
		return McRegistry.wrap(McBuiltIn.villagerProfessions(), villager.getVillagerData().getProfession());
		//?}
	}

	/** Whether {@code profession} works at the point-of-interest type {@code poi}. 1.19
	 * replaced the single {@code getJobPoiType} with a {@code heldJobSite} predicate over
	 * holders. */
	public static boolean worksAt(VillagerProfession profession, net.minecraft.world.entity.ai.village.poi.PoiType poi) {
		//? if >=1.19 {
		return profession.heldJobSite().test(McRegistry.wrap(McBuiltIn.poiTypes(), poi));
		//?} else {
		/*return profession.getJobPoiType() == poi;*/
		//?}
	}

	/** The librarian profession's registry key (the mod's default/configured target). */
	public static ResourceKey<VillagerProfession> librarian() {
		//? if >=1.21.5 {
		/*return VillagerProfession.LIBRARIAN;*/
		//?} else {
		return keyOf(VillagerProfession.LIBRARIAN);
		//?}
	}

	/** The unemployed ("none") profession's registry key. */
	public static ResourceKey<VillagerProfession> none() {
		//? if >=1.21.5 {
		/*return VillagerProfession.NONE;*/
		//?} else {
		return keyOf(VillagerProfession.NONE);
		//?}
	}

	/** The nitwit profession's registry key. */
	public static ResourceKey<VillagerProfession> nitwit() {
		//? if >=1.21.5 {
		/*return VillagerProfession.NITWIT;*/
		//?} else {
		return keyOf(VillagerProfession.NITWIT);
		//?}
	}

	/** A profession VALUE's registry key. Only reachable on the pre-26.1 branch (26.1+
	 *  constants are keys already), so it is compiled only there. */
	//? if <1.21.5 {
	private static ResourceKey<VillagerProfession> keyOf(VillagerProfession profession) {
		return McBuiltIn.villagerProfessions().getResourceKey(profession).orElseThrow();
	}
	//?}

	private McVillager() {}
}
