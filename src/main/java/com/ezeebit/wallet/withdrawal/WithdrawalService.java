package com.ezeebit.wallet.withdrawal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.ErrorCode;
import com.ezeebit.wallet.WalletException;
import com.ezeebit.wallet.ledger.AccountType;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.ledger.Posting;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves money out in two steps. First the amount is held (available → held) in the same transaction
 * that records the withdrawal. Then, after commit, it goes to the rail. The rail's result settles the
 * hold (held → payouts_out) or releases it (held → available). If we cannot tell what the rail did,
 * the withdrawal is UNKNOWN and the money stays held: never lost, never paid twice.
 */
@Service
public class WithdrawalService {

	private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

	/** Only moves forward; SUCCEEDED and FAILED are final. */
	public enum State {
		REQUESTED, SUBMITTED, UNKNOWN, SUCCEEDED, FAILED;

		boolean isFinal() {
			return this == SUCCEEDED || this == FAILED;
		}
	}

	/** BANK takes accountNumber + bankCode (fiat only); CHAIN takes network + address (stablecoins only). */
	public record Destination(@NotNull Type type, @Size(max = 34) String accountNumber, @Size(max = 16) String bankCode,
			@Size(max = 16) String network, @Size(max = 128) String address) {

		public enum Type {
			BANK, CHAIN
		}
	}

	public record WithdrawalRequest(@NotNull Currency currency, @NotNull @Positive BigDecimal amount,
			@NotNull @Valid Destination destination) {
	}

	/** {@code id} is the public id, never the database's auto-increment one. */
	public record Withdrawal(String id, String merchantId, Currency currency, BigDecimal amount, Destination destination,
			State state) {
	}

	private static final String COLUMNS = """
			public_id, merchant_id, currency, amount, destination_type, account_number, bank_code, network, address, state""";

	private final LedgerService ledger;

	private final PayoutRail rail;

	private final JdbcClient jdbc;

	private final TransactionTemplate tx;

	WithdrawalService(LedgerService ledger, PayoutRail rail, JdbcClient jdbc, TransactionTemplate tx) {
		this.ledger = ledger;
		this.rail = rail;
		this.jdbc = jdbc;
		this.tx = tx;
	}

	public Withdrawal withdraw(String merchantId, String idempotencyKey, WithdrawalRequest request) {
		requireValidDestination(request.currency(), request.destination());
		LedgerService.requireValidAmount(request.currency(), request.amount());
		String id;
		try {
			id = tx.execute(status -> hold(merchantId, idempotencyKey, request));
		}
		catch (DuplicateKeyException e) {
			// Replay. Read outside the rolled-back transaction so a concurrent winner is visible, and return
			// its current state without resubmitting.
			return replay(merchantId, idempotencyKey, request);
		}
		submit(find(id).orElseThrow());
		return find(id).orElseThrow();
	}

	public Optional<Withdrawal> find(String merchantId, String id) {
		return find(id).filter(w -> w.merchantId().equals(merchantId));
	}

	/**
	 * The rail's final word on a payout. Safe to call any number of times, in any order relative to
	 * submit: the first result wins, later ones change nothing.
	 */
	public void onPayoutResult(String withdrawalId, boolean succeeded) {
		tx.executeWithoutResult(status -> {
			Withdrawal w = jdbc.sql("SELECT " + COLUMNS + " FROM withdrawal WHERE public_id = ? FOR UPDATE")
				.param(withdrawalId)
				.query(WithdrawalService::map)
				.optional()
				.orElseThrow(() -> new IllegalArgumentException("Unknown withdrawal " + withdrawalId));
			if (w.state().isFinal()) {
				if ((w.state() == State.SUCCEEDED) != succeeded) {
					log.warn("Withdrawal {} is {} but the rail now says succeeded={}; needs a human", w.id(), w.state(),
							succeeded);
				}
				return;
			}
			Currency currency = w.currency();
			long held = ledger.account(w.merchantId(), AccountType.HELD, currency);
			long to = succeeded ? ledger.account(LedgerService.PLATFORM, AccountType.PAYOUTS_OUT, currency)
					: ledger.account(w.merchantId(), AccountType.AVAILABLE, currency);
			ledger.post(succeeded ? "PAYOUT" : "RELEASE", w.id(),
					List.of(new Posting(held, currency, w.amount().negate()), new Posting(to, currency, w.amount())));
			jdbc.sql("UPDATE withdrawal SET state = ? WHERE public_id = ?")
				.params((succeeded ? State.SUCCEEDED : State.FAILED).name(), w.id())
				.update();
		});
	}

	private String hold(String merchantId, String idempotencyKey, WithdrawalRequest request) {
		Currency currency = request.currency();
		Destination d = request.destination();
		String id = UUID.randomUUID().toString();
		jdbc.sql("""
				INSERT INTO withdrawal (public_id, merchant_id, idempotency_key, currency, amount, destination_type,
				                        account_number, bank_code, network, address, state)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
			.params(id, merchantId, idempotencyKey, currency.name(), request.amount(), d.type().name(),
					d.accountNumber(), d.bankCode(), d.network(), d.address(), State.REQUESTED.name())
			.update();
		long available = ledger.account(merchantId, AccountType.AVAILABLE, currency);
		long held = ledger.account(merchantId, AccountType.HELD, currency);
		// Throws InsufficientFundsException if available is short, which rolls back the withdrawal row too.
		ledger.post("HOLD", id, List.of(new Posting(available, currency, request.amount().negate()),
				new Posting(held, currency, request.amount())));
		return id;
	}

	private void submit(Withdrawal w) {
		State next;
		try {
			rail.submit(w.id(), w.currency(), w.amount(), w.destination());
			next = State.SUBMITTED;
		}
		catch (PayoutRail.Rejected e) {
			onPayoutResult(w.id(), false);
			return;
		}
		catch (RuntimeException e) {
			log.warn("Withdrawal {}: rail outcome unknown, funds stay held", w.id(), e);
			next = State.UNKNOWN;
		}
		// Conditional: the rail's result may already have arrived and moved it to a final state.
		jdbc.sql("UPDATE withdrawal SET state = ? WHERE public_id = ? AND state = ?")
			.params(next.name(), w.id(), State.REQUESTED.name())
			.update();
	}

	private Withdrawal replay(String merchantId, String idempotencyKey, WithdrawalRequest request) {
		Withdrawal w = jdbc.sql("SELECT " + COLUMNS + " FROM withdrawal WHERE merchant_id = ? AND idempotency_key = ?")
			.params(merchantId, idempotencyKey)
			.query(WithdrawalService::map)
			.single();
		if (w.currency() != request.currency() || w.amount().compareTo(request.amount()) != 0
				|| !w.destination().equals(request.destination())) {
			throw new WalletException(ErrorCode.IDEMPOTENCY_CONFLICT,
					"Idempotency-Key " + idempotencyKey + " was already used for a different withdrawal");
		}
		return w;
	}

	private Optional<Withdrawal> find(String id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM withdrawal WHERE public_id = ?")
			.param(id)
			.query(WithdrawalService::map)
			.optional();
	}

	private static Withdrawal map(ResultSet rs, int row) throws SQLException {
		Currency currency = Currency.valueOf(rs.getString("currency"));
		Destination destination = new Destination(Destination.Type.valueOf(rs.getString("destination_type")),
				rs.getString("account_number"), rs.getString("bank_code"), rs.getString("network"),
				rs.getString("address"));
		return new Withdrawal(rs.getString("public_id"), rs.getString("merchant_id"), currency,
				rs.getBigDecimal("amount").setScale(currency.scale), destination, State.valueOf(rs.getString("state")));
	}

	private static void requireValidDestination(Currency currency, Destination d) {
		boolean valid = switch (d.type()) {
			case BANK -> !currency.stablecoin && present(d.accountNumber()) && present(d.bankCode())
					&& d.network() == null && d.address() == null;
			case CHAIN -> currency.stablecoin && present(d.network()) && present(d.address())
					&& d.accountNumber() == null && d.bankCode() == null;
		};
		if (!valid) {
			throw new WalletException(ErrorCode.INVALID_DESTINATION, currency + " cannot be paid to " + d.type()
					+ (currency.stablecoin ? " (use CHAIN with network and address)"
							: " (use BANK with accountNumber and bankCode)"));
		}
	}

	private static boolean present(String s) {
		return s != null && !s.isBlank();
	}

}
