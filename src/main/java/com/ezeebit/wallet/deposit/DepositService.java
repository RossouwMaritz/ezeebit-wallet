package com.ezeebit.wallet.deposit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.ErrorCode;
import com.ezeebit.wallet.WalletException;
import com.ezeebit.wallet.ledger.AccountType;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.ledger.Posting;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stands in for money arriving from a bank or chain. The external reference is the idempotency key:
 * the same reference again returns the deposit already booked, never a second one.
 */
@Service
public class DepositService {

	/** {@code reference} is the bank or chain transaction id; max 63 so merchant/reference fits the ledger's 128. */
	public record DepositRequest(@NotNull Currency currency, @NotNull @Positive BigDecimal amount,
			@NotBlank @Size(max = 63) String reference) {
	}

	public record Deposit(long entryId, Currency currency, BigDecimal amount, String reference) {
	}

	private final LedgerService ledger;

	private final JdbcClient jdbc;

	private final TransactionTemplate tx;

	DepositService(LedgerService ledger, JdbcClient jdbc, TransactionTemplate tx) {
		this.ledger = ledger;
		this.jdbc = jdbc;
		this.tx = tx;
	}

	public Deposit deposit(String merchantId, DepositRequest request) {
		String reference = merchantId + "/" + request.reference();
		try {
			return tx.execute(status -> book(merchantId, reference, request));
		}
		catch (DuplicateKeyException e) {
			// journal_entry (type, reference) already holds it. Read outside the rolled-back transaction
			// so a deposit committed by a concurrent request is visible.
			return existing(reference, request).orElseThrow();
		}
	}

	private Deposit book(String merchantId, String reference, DepositRequest request) {
		Currency currency = request.currency();
		long available = ledger.account(merchantId, AccountType.AVAILABLE, currency);
		long depositsIn = ledger.account(LedgerService.PLATFORM, AccountType.DEPOSITS_IN, currency);
		long entryId = ledger.post("DEPOSIT", reference, List.of(new Posting(available, currency, request.amount()),
				new Posting(depositsIn, currency, request.amount().negate())));
		return new Deposit(entryId, currency, request.amount().setScale(currency.scale), request.reference());
	}

	private Optional<Deposit> existing(String reference, DepositRequest request) {
		return jdbc.sql("""
				SELECT e.id, a.currency, p.amount
				FROM journal_entry e
				JOIN posting p ON p.entry_id = e.id
				JOIN account a ON a.id = p.account_id
				WHERE e.type = 'DEPOSIT' AND e.reference = ? AND a.type = 'AVAILABLE'""")
			.param(reference)
			.query((rs, i) -> {
				Currency currency = Currency.valueOf(rs.getString(2));
				BigDecimal amount = rs.getBigDecimal(3).setScale(currency.scale);
				if (currency != request.currency() || amount.compareTo(request.amount()) != 0) {
					throw new WalletException(ErrorCode.IDEMPOTENCY_CONFLICT, "Deposit " + request.reference()
							+ " is already booked as " + amount.toPlainString() + " " + currency);
				}
				return new Deposit(rs.getLong(1), currency, amount, request.reference());
			})
			.optional();
	}

}
