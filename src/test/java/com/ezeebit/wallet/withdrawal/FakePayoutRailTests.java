package com.ezeebit.wallet.withdrawal;

import java.math.BigDecimal;
import java.time.Duration;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.withdrawal.FakePayoutRail.Outcome;
import com.ezeebit.wallet.withdrawal.WithdrawalService.Destination;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FakePayoutRailTests {

	static final Destination BANK = new Destination(Destination.Type.BANK, "62000000001", "250655", null, null);

	final WithdrawalService withdrawals = mock(WithdrawalService.class);

	@SuppressWarnings("unchecked")
	final ObjectProvider<WithdrawalService> provider = mock(ObjectProvider.class);

	@Test
	void reportsAcceptedPayoutsAsPaidAfterTheDelay() {
		when(provider.getObject()).thenReturn(withdrawals);
		FakePayoutRail rail = new FakePayoutRail(provider, Duration.ofMillis(200));

		rail.submit("w-1", Currency.ZAR, BigDecimal.TEN, BANK);

		verify(withdrawals, after(100).never()).onPayoutResult("w-1", true);
		verify(withdrawals, timeout(2_000)).onPayoutResult("w-1", true);
	}

	@Test
	void reportsNothingWhenRejectedOrWithoutADelay() {
		when(provider.getObject()).thenReturn(withdrawals);
		FakePayoutRail settling = new FakePayoutRail(provider, Duration.ofMillis(50));
		settling.script(Outcome.REJECT);
		assertThatThrownBy(() -> settling.submit("w-1", Currency.ZAR, BigDecimal.TEN, BANK))
			.isInstanceOf(PayoutRail.Rejected.class);

		new FakePayoutRail(provider, null).submit("w-2", Currency.ZAR, BigDecimal.TEN, BANK);

		verify(withdrawals, after(300).never()).onPayoutResult("w-1", true);
		verifyNoInteractions(withdrawals);
	}

}
