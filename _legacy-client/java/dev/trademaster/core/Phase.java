package dev.trademaster.core;

/** Pipeline phases for one full run. */
public enum Phase {
	IDLE,
	PRECHECK,
	SUMMON,
	AWAIT_VILLAGER,
	ENSURE_LECTERN,
	WAIT_PROFESSION,
	CHECK_TRADE,
	REROLL,
	LOCK,
	NAME,
	SEND_AWAY,
	DEPOSIT
}
