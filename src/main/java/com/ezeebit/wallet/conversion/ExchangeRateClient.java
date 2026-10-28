package com.ezeebit.wallet.conversion;

import java.math.BigDecimal;
import java.time.Instant;

import com.ezeebit.wallet.Currency;

/** The market price feed. Implementations time out rather than block; any exception means no rate. */
public interface ExchangeRateClient {

	/** How many units of {@code to} one unit of {@code from} buys, at mid-market, as of {@code asOf}. */
	record Rate(BigDecimal rate, Instant asOf) {
	}

	Rate rate(Currency from, Currency to);

}
