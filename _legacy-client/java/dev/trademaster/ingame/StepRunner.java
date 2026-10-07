package dev.trademaster.ingame;

import dev.trademaster.core.RunSession;
import dev.trademaster.strategy.RerollStrategy.StepResult;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Tiny tick-driven step queue used instead of LPCTools' full InGameFuture
 * framework. Steps report DONE to advance, RUNNING to wait another tick,
 * or FAILED to abort the whole chain.
 */
public class StepRunner {

	@FunctionalInterface
	public interface Step {
		StepResult tick(RunSession session);
	}

	private final Queue<Step> steps = new ArrayDeque<>();
	private @Nullable Step current = null;
	private boolean finished = false;
	private boolean aborted = false;
	private @Nullable Component failure = null;

	public StepRunner add(Step step) {
		this.steps.add(step);
		return this;
	}

	public StepResult tick(RunSession session) {
		// an aborted chain must never report DONE: consumers treat DONE as
		// success and would advance the phase on work that was never finished
		if (this.aborted) {
			return StepResult.failed(Component.literal("TradeMaster: step chain aborted"));
		}
		if (this.finished) return StepResult.DONE;
		if (this.failure != null) return StepResult.failed(this.failure);

		if (this.current == null) {
			this.current = this.steps.poll();
			if (this.current == null) {
				this.finished = true;
				return StepResult.DONE;
			}
		}

		StepResult result = this.current.tick(session);
		switch (result.step()) {
			case DONE -> {
				this.current = null;
				// an intermediate step's DONE must not surface as DONE: consumers
				// treat DONE as "the whole chain finished" and abort the runner,
				// which would discard every remaining step
				if (!this.steps.isEmpty()) return StepResult.RUNNING;
			}
			// a null reason would leave `failure` null and re-tick the failed
			// step forever on the next pass
			case FAILED, FAILED_FULL -> this.failure = result.reason() != null
					? result.reason()
					: Component.literal("TradeMaster: step failed");
			case RUNNING -> {}
		}
		return result;
	}

	/** Terminal: a ticked-after-abort runner reports FAILED, never DONE. */
	public void abort() {
		this.steps.clear();
		this.current = null;
		this.finished = false;
		this.failure = null;
		this.aborted = true;
	}
}
