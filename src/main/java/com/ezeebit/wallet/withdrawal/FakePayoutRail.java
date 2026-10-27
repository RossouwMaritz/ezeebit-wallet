package com.ezeebit.wallet.withdrawal;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.withdrawal.WithdrawalService.Destination;

import org.springframework.stereotype.Component;

/** In-process stand-in for a real rail. Accepts everything unless a test scripts the next outcomes. */
@Component
public class FakePayoutRail implements PayoutRail {

	public enum Outcome {
		ACCEPT, REJECT, TIMEOUT
	}

	private final Queue<Outcome> script = new ConcurrentLinkedQueue<>();

	private final Queue<Long> submitted = new ConcurrentLinkedQueue<>();

	/** The next submits get these outcomes, in order; after that, ACCEPT. */
	public void script(Outcome... outcomes) {
		script.addAll(List.of(outcomes));
	}

	/** Withdrawal ids submitted so far, in order. */
	public List<Long> submitted() {
		return List.copyOf(submitted);
	}

	@Override
	public void submit(long withdrawalId, Currency currency, BigDecimal amount, Destination destination) {
		submitted.add(withdrawalId);
		Outcome outcome = script.poll();
		if (outcome == Outcome.REJECT) {
			throw new Rejected("Destination refused");
		}
		if (outcome == Outcome.TIMEOUT) {
			throw new UncheckedIOException(new SocketTimeoutException("Read timed out"));
		}
	}

}
