package com.ezeebit.wallet.conversion;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.ezeebit.wallet.Currency;

import org.springframework.stereotype.Component;

/** In-process stand-in for a real feed. Fixed rates unless a test scripts the next outcomes. */
@Component
public class FakeExchangeRateClient implements ExchangeRateClient {

	public enum Outcome {
		OK, STALE, OUTLIER, ERROR
	}

	private static final Map<Currency, BigDecimal> USD_PER_UNIT = Map.of(Currency.ZAR, new BigDecimal("0.055"),
			Currency.NGN, new BigDecimal("0.00065"), Currency.KES, new BigDecimal("0.0077"), Currency.USDT,
			BigDecimal.ONE, Currency.USDC, BigDecimal.ONE);

	private final Queue<Outcome> script = new ConcurrentLinkedQueue<>();

	/** The next calls get these outcomes, in order; after that, OK. */
	public void script(Outcome... outcomes) {
		script.addAll(List.of(outcomes));
	}

	@Override
	public Rate rate(Currency from, Currency to) {
		BigDecimal rate = USD_PER_UNIT.get(from).divide(USD_PER_UNIT.get(to), MathContext.DECIMAL64);
		Outcome outcome = script.poll();
		if (outcome == null) {
			outcome = Outcome.OK;
		}
		return switch (outcome) {
			case OK -> new Rate(rate, Instant.now());
			case STALE -> new Rate(rate, Instant.now().minusSeconds(60));
			case OUTLIER -> new Rate(rate.multiply(new BigDecimal("1.05")), Instant.now());
			case ERROR -> throw new IllegalStateException("Feed unavailable");
		};
	}

}
