package com.ezeebit.wallet.ledger;

public enum AccountType {
	AVAILABLE(false),
	HELD(false),
	FX(false),
	// External accounts mirror money outside our books, so they go negative by design.
	DEPOSITS_IN(true),
	PAYOUTS_OUT(true),
	TREASURY(true);

	final boolean allowNegative;

	AccountType(boolean allowNegative) {
		this.allowNegative = allowNegative;
	}
}
