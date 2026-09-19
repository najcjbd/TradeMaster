package dev.trademaster.exec;

import dev.trademaster.strategy.RerollStrategy.StepResult;

/**
 * Disposes of the enchanted books blocking the pipeline (full inventory).
 * Implementations run one step per tick() until DONE/FAILED; the controller
 * calls abortCleanly() when the run stops mid-disposal.
 */
public interface BookDisposal {

	StepResult tick();

	void abortCleanly();
}
