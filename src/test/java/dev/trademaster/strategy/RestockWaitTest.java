package dev.trademaster.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestockWaitTest {

	@Test
	void anInFlightBatchNeverFailsImmediately() {
		LecternRestock.RestockWait wait = new LecternRestock.RestockWait();
		// the partial-batch/cursor state: restock() reports progress, offhand is empty
		// and nothing is countable in the inventory
		for (int i = 0; i < 60; i++) {
			assertEquals(LecternRestock.RestockWait.Result.WAIT, wait.tick(true, false, false));
		}
		assertEquals(LecternRestock.RestockWait.Result.WAIT_AFTER_RESET, wait.tick(true, false, false));
		for (int i = 0; i < 60; i++) {
			assertEquals(LecternRestock.RestockWait.Result.WAIT, wait.tick(true, false, false));
		}
		assertEquals(LecternRestock.RestockWait.Result.FAIL, wait.tick(true, false, false));
	}

	@Test
	void anEmptyInventoryWithNoBatchFailsImmediately() {
		assertEquals(LecternRestock.RestockWait.Result.FAIL,
				new LecternRestock.RestockWait().tick(false, false, false));
	}

	@Test
	void aLandedBatchResetsTheBudget() {
		LecternRestock.RestockWait wait = new LecternRestock.RestockWait();
		for (int i = 0; i < 30; i++) {
			assertEquals(LecternRestock.RestockWait.Result.WAIT, wait.tick(false, false, true));
		}
		assertEquals(LecternRestock.RestockWait.Result.WAIT, wait.tick(true, true, true));
		for (int i = 0; i < 60; i++) {
			assertEquals(LecternRestock.RestockWait.Result.WAIT, wait.tick(false, false, true));
		}
		assertEquals(LecternRestock.RestockWait.Result.WAIT_AFTER_RESET, wait.tick(false, false, true));
	}

	@Test
	void aCarriedStockWithNoBatchWaitsBeforeFailing() {
		LecternRestock.RestockWait wait = new LecternRestock.RestockWait();
		for (int i = 0; i < 60; i++) {
			assertEquals(LecternRestock.RestockWait.Result.WAIT, wait.tick(false, false, true));
		}
		assertEquals(LecternRestock.RestockWait.Result.WAIT_AFTER_RESET, wait.tick(false, false, true));
	}
}
