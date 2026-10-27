package com.ezeebit.wallet.ledger;

import com.ezeebit.wallet.ErrorCode;
import com.ezeebit.wallet.WalletException;

/** Carries the account so callers can tell a merchant short (funds) from a house short (liquidity). */
public class InsufficientFundsException extends WalletException {

	private final long accountId;

	public InsufficientFundsException(long accountId) {
		super(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds in account " + accountId);
		this.accountId = accountId;
	}

	public long accountId() {
		return accountId;
	}

}
