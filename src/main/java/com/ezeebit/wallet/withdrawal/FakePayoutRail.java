package com.ezeebit.wallet.withdrawal;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.withdrawal.WithdrawalService.Destination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for a real rail. Accepts everything unless a test scripts the next outcomes.
 * With {@code wallet.fake-rail.settle-after} set (local runs only), it also reports each accepted payout as
 * succeeded after that delay, the way a real rail's webhook would.
 */
@Component
public class FakePayoutRail implements PayoutRail {

	private static final Logger log = LoggerFactory.getLogger(FakePayoutRail.class);

	public enum Outcome {
		ACCEPT, REJECT, TIMEOUT
	}

	private final Queue<Outcome> script = new ConcurrentLinkedQueue<>();

	private final Queue<String> submitted = new ConcurrentLinkedQueue<>();

	// A provider because WithdrawalService depends on this rail; looking it up later breaks the cycle.
	private final ObjectProvider<WithdrawalService> withdrawals;

	private final Duration settleAfter;

	FakePayoutRail(ObjectProvider<WithdrawalService> withdrawals,
			@Value("${wallet.fake-rail.settle-after:#{null}}") Duration settleAfter) {
		this.withdrawals = withdrawals;
		this.settleAfter = settleAfter;
	}

	/** The next submits get these outcomes, in order; after that, ACCEPT. */
	public void script(Outcome... outcomes) {
		script.addAll(List.of(outcomes));
	}

	/** Withdrawal ids submitted so far, in order. */
	public List<String> submitted() {
		return List.copyOf(submitted);
	}

	@Override
	public void submit(String withdrawalId, Currency currency, BigDecimal amount, Destination destination) {
		submitted.add(withdrawalId);
		Outcome outcome = script.poll();
		if (outcome == Outcome.REJECT) {
			throw new Rejected("Destination refused");
		}
		if (outcome == Outcome.TIMEOUT) {
			throw new UncheckedIOException(new SocketTimeoutException("Read timed out"));
		}
		if (settleAfter != null) {
			CompletableFuture
				.runAsync(() -> withdrawals.getObject().onPayoutResult(withdrawalId, true),
						CompletableFuture.delayedExecutor(settleAfter.toMillis(), TimeUnit.MILLISECONDS))
				.exceptionally(e -> {
					log.warn("Fake rail could not report withdrawal {} as paid", withdrawalId, e);
					return null;
				});
		}
	}

}
