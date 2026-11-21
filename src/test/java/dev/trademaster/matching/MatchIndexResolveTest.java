package dev.trademaster.matching;

import dev.trademaster.compat.McIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchIndexResolveTest {

	private static final ResourceLocation A = McIds.mc("good_a");
	private static final ResourceLocation B = McIds.mc("good_b");

	@Test
	void uniqueNamesAreIndexed() {
		Map<String, ResourceLocation> index = TradeMatcher.resolveIndex(
				Map.of("unique", Set.of(A)), Map.of());
		assertEquals(A, index.get("unique"));
	}

	@Test
	void aCollisionWithoutAChoiceIsDropped() {
		Map<String, ResourceLocation> index = TradeMatcher.resolveIndex(
				Map.of("shared", Set.of(A, B)), Map.of());
		assertFalse(index.containsKey("shared"));
	}

	@Test
	void aChosenOwnerResolvesTheCollision() {
		Map<String, ResourceLocation> index = TradeMatcher.resolveIndex(
				Map.of("shared", Set.of(A, B)), Map.of("shared", B));
		assertEquals(B, index.get("shared"));
	}

	@Test
	void aStaleChoiceCannotResolveTheCollision() {
		Map<String, ResourceLocation> index = TradeMatcher.resolveIndex(
				Map.of("shared", Set.of(A, B)), Map.of("shared", McIds.mc("gone")));
		assertFalse(index.containsKey("shared"));
		assertTrue(index.isEmpty());
	}
}
