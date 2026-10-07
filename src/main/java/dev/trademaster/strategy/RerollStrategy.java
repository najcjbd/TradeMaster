package dev.trademaster.strategy;

import dev.trademaster.core.RunSession;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Abstraction over the two reroll mechanisms. */
public interface RerollStrategy {

	enum Step { RUNNING, DONE, FAILED, FAILED_FULL }

	record StepResult(Step step, @Nullable Component reason) {
		public static final StepResult RUNNING = new StepResult(Step.RUNNING, null);
		public static final StepResult DONE = new StepResult(Step.DONE, null);

		public static StepResult failed(Component reason) {
			return new StepResult(Step.FAILED, reason);
		}

		/** Failure because every target container is full; callers may tolerate it. */
		public static StepResult failedFull(Component reason) {
			return new StepResult(Step.FAILED_FULL, reason);
		}
	}

	void begin(RunSession session);

	StepResult tick();

	void abort();

	/** True when the failure happened on the placement side (recoverable by swapping villagers). */
	// Default body is never used today (audited 2026-09-19): both implementations
	// override it; kept so a new strategy does not have to implement it.
	default boolean placementBlocked() {
		return false;
	}
}
