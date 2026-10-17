package dev.trademaster.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** Version-gated entity accessors. {@code Entity.level()} replaced the {@code getLevel()}
 * accessor at 1.20. */
public final class McEntity {

	public static Level level(Entity entity) {
		//? if >=1.20 {
		return entity.level();
		//?} else {
		/*return entity.getLevel();*/
		//?}
	}

	private McEntity() {}
}
