package com.ezeebit.wallet.withdrawal;

import java.math.BigDecimal;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.withdrawal.WithdrawalService.Destination;

/** The bank or chain that actually moves the money. Results arrive later through {@link WithdrawalService#onPayoutResult}. */
public interface PayoutRail {

	/**
	 * Returns normally when the rail accepted the payout, throws {@link Rejected} when it refused it outright.
	 * Any other exception (timeout, connection reset) means we do not know whether it was sent.
	 * {@code withdrawalId} is the rail's idempotency key, so resubmitting never pays twice.
	 */
	void submit(String withdrawalId, Currency currency, BigDecimal amount, Destination destination);

	class Rejected extends RuntimeException {

		public Rejected(String message) {
			super(message);
		}

	}

}
