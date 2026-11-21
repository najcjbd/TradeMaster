package dev.trademaster.matching;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchIndexResolveTest {

	private static final Identifier A = Identifier.withDefaultNamespace("good_a");
	private static final Identifier B = Identifier.withDefaultNamespace("good_b");

	@Test
	void uniqueNamesAreIndexed() {
		Map<String, Identifier> index = TradeMatcher.resolveIndex(
				Map.of("unique", Set.of(A)), Map.of());
		assertEquals(A, index.get("unique"));
	}

	@Test
	void aCollisionWithoutAChoiceIsDropped() {
		Map<String, Identifier> index = TradeMatcher.resolveIndex(
				Map.of("shared", Set.of(A, B)), Map.of());
		assertFalse(index.containsKey("shared"));
	}

	@Test
	void aChosenOwnerResolvesTheCollision() {
		Map<String, Identifier> index = TradeMatcher.resolveIndex(
				Map.of("shared", Set.of(A, B)), Map.of("shared", B));
		assertEquals(B, index.get("shared"));
	}

	@Test
	void aStaleChoiceCannotResolveTheCollision() {
		Map<String, Identifier> index = TradeMatcher.resolveIndex(
				Map.of("shared", Set.of(A, B)),
				Map.of("shared", Identifier.withDefaultNamespace("gone")));
		assertFalse(index.containsKey("shared"));
		assertTrue(index.isEmpty());
	}
}
