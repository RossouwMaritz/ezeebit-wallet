package com.ezeebit.wallet.ledger;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.ErrorCode;
import com.ezeebit.wallet.TestcontainersConfiguration;
import com.ezeebit.wallet.WalletException;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerServiceTests {

	@Autowired
	LedgerService ledger;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate tx;

	final String merchant = UUID.randomUUID().toString();

	@Test
	void balanceEqualsSumOfPostings() {
		long available = ledger.account(merchant, AccountType.AVAILABLE, Currency.ZAR);
		long held = ledger.account(merchant, AccountType.HELD, Currency.ZAR);
		long depositsIn = ledger.account("platform", AccountType.DEPOSITS_IN, Currency.ZAR);

		deposit(available, depositsIn, "100.50");
		deposit(available, depositsIn, "20");
		ledger.post("HOLD", ref(), List.of(posting(available, "-30.25"), posting(held, "30.25")));

		assertThat(balance(available)).isEqualByComparingTo("90.25");
		assertThat(balance(held)).isEqualByComparingTo("30.25");
		// Across the whole ledger (seed included): every cached balance matches its postings...
		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM account_balance b
				WHERE b.balance <> (SELECT COALESCE(SUM(p.amount), 0) FROM posting p WHERE p.account_id = b.account_id)""")
			.query(Long.class)
			.single()).isZero();
		// ...and every currency nets to zero.
		assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM (SELECT a.currency FROM posting p JOIN account a ON a.id = p.account_id
				GROUP BY a.currency HAVING SUM(p.amount) <> 0) unbalanced""")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void seedStocksEveryCurrency() {
		assertThat(jdbc.sql("SELECT currency FROM account WHERE owner = 'platform' AND type = 'FX'").query(String.class).list())
			.containsExactlyInAnyOrder(Arrays.stream(Currency.values()).map(Currency::name).toArray(String[]::new));
	}

	@Test
	void mixedCurrencyEntryIsRejected() {
		long zar = ledger.account(merchant, AccountType.AVAILABLE, Currency.ZAR);
		long usdt = ledger.account("platform", AccountType.DEPOSITS_IN, Currency.USDT);
		String ref = ref();

		assertThatThrownBy(() -> ledger.post("DEPOSIT", ref, List.of(posting(zar, "100"), posting(usdt, "-100"))))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not balance");
		assertNothingBooked(ref, zar);
	}

	@Test
	void postingInTheWrongCurrencyIsRejected() {
		long zar = ledger.account(merchant, AccountType.AVAILABLE, Currency.ZAR);
		long depositsIn = ledger.account("platform", AccountType.DEPOSITS_IN, Currency.ZAR);

		assertThatThrownBy(() -> ledger.post("DEPOSIT", ref(),
				List.of(new Posting(zar, Currency.NGN, new BigDecimal("1")), new Posting(depositsIn, Currency.NGN, new BigDecimal("-1")))))
			.extracting(e -> ((WalletException) e).code())
			.isEqualTo(ErrorCode.CURRENCY_MISMATCH);
	}

	@Test
	void amountFinerThanTheCurrencyAllowsIsRejected() {
		long available = ledger.account(merchant, AccountType.AVAILABLE, Currency.ZAR);
		long depositsIn = ledger.account("platform", AccountType.DEPOSITS_IN, Currency.ZAR);

		assertThatThrownBy(() -> deposit(available, depositsIn, "1.005"))
			.extracting(e -> ((WalletException) e).code())
			.isEqualTo(ErrorCode.INVALID_AMOUNT);
	}

	@Test
	void overdraftIsRejectedAndNothingMoves() {
		long available = ledger.account(merchant, AccountType.AVAILABLE, Currency.ZAR);
		long payoutsOut = ledger.account("platform", AccountType.PAYOUTS_OUT, Currency.ZAR);
		long depositsIn = ledger.account("platform", AccountType.DEPOSITS_IN, Currency.ZAR);
		deposit(available, depositsIn, "10");
		BigDecimal payoutsBefore = balance(payoutsOut);
		String ref = ref();

		assertThatThrownBy(() -> ledger.post("PAYOUT", ref, List.of(posting(available, "-10.01"), posting(payoutsOut, "10.01"))))
			.isInstanceOfSatisfying(InsufficientFundsException.class, e -> assertThat(e.accountId()).isEqualTo(available));
		assertThat(balance(available)).isEqualByComparingTo("10");
		assertThat(balance(payoutsOut)).isEqualByComparingTo(payoutsBefore);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_entry WHERE reference = ?").param(ref).query(Long.class).single())
			.isZero();
	}

	@Test
	void accountCreatedConcurrentlyIsFound() {
		long id = tx.execute(status -> {
			// The first read fixes this transaction's snapshot; the account is then committed by another one.
			jdbc.sql("SELECT COUNT(*) FROM account").query(Long.class).single();
			long other = CompletableFuture.supplyAsync(() -> ledger.account(merchant, AccountType.AVAILABLE, Currency.ZAR)).join();
			assertThat(ledger.account(merchant, AccountType.AVAILABLE, Currency.ZAR)).isEqualTo(other);
			return other;
		});
		assertThat(balance(id)).isZero();
	}

	private void deposit(long available, long depositsIn, String amount) {
		ledger.post("DEPOSIT", ref(), List.of(posting(available, amount), posting(depositsIn, "-" + amount)));
	}

	private Posting posting(long accountId, String amount) {
		Currency currency = Currency.valueOf(jdbc.sql("SELECT currency FROM account WHERE id = ?").param(accountId).query(String.class).single());
		return new Posting(accountId, currency, new BigDecimal(amount));
	}

	private BigDecimal balance(long accountId) {
		return jdbc.sql("SELECT balance FROM account_balance WHERE account_id = ?")
			.param(accountId)
			.query(BigDecimal.class)
			.single();
	}

	private void assertNothingBooked(String ref, long accountId) {
		assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_entry WHERE reference = ?").param(ref).query(Long.class).single())
			.isZero();
		assertThat(balance(accountId)).isZero();
	}

	private static String ref() {
		return UUID.randomUUID().toString();
	}

}
