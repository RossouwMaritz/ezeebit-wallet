package com.ezeebit.wallet.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ezeebit.wallet.Currency;
import com.ezeebit.wallet.ErrorCode;
import com.ezeebit.wallet.WalletException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

	/** Owner of the house and external accounts. */
	public static final String PLATFORM = "platform";

	private record Account(long id, Currency currency, AccountType type) {
	}

	private final JdbcClient jdbc;

	LedgerService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns the account id, creating the account (balance 0) on first use. */
	@Transactional
	public long account(String owner, AccountType type, Currency currency) {
		Optional<Long> existing = findAccount(owner, type, currency);
		if (existing.isPresent()) {
			return existing.get();
		}
		// LAST_INSERT_ID(id) hands back the id even when another transaction created the row after our
		// snapshot was taken, which a plain re-SELECT under REPEATABLE READ would not see.
		jdbc.sql("""
				INSERT INTO account (owner, type, currency) VALUES (?, ?, ?)
				ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)""")
			.params(owner, type.name(), currency.name())
			.update();
		long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
		jdbc.sql("INSERT IGNORE INTO account_balance (account_id, balance) VALUES (?, 0)").param(id).update();
		return id;
	}

	private Optional<Long> findAccount(String owner, AccountType type, Currency currency) {
		return jdbc.sql("SELECT id FROM account WHERE owner = ? AND type = ? AND currency = ?")
			.params(owner, type.name(), currency.name())
			.query(Long.class)
			.optional();
	}

	/**
	 * Books one balanced journal entry and updates the balance cache in the same transaction.
	 * Throws {@link InsufficientFundsException} if any non-negative account would go below zero;
	 * nothing is written in that case.
	 */
	@Transactional
	public long post(String type, String reference, List<Posting> postings) {
		Map<Long, Account> accounts = loadAccounts(postings);
		Map<Currency, BigDecimal> sums = new HashMap<>();
		for (Posting p : postings) {
			Account account = accounts.get(p.accountId());
			if (account == null) {
				throw new IllegalArgumentException("Unknown account " + p.accountId());
			}
			if (account.currency() != p.currency()) {
				throw new WalletException(ErrorCode.CURRENCY_MISMATCH,
						"Account " + account.id() + " holds " + account.currency() + ", not " + p.currency());
			}
			requireValidAmount(p.currency(), p.amount());
			sums.merge(p.currency(), p.amount(), BigDecimal::add);
		}
		sums.forEach((currency, sum) -> {
			if (sum.signum() != 0) {
				throw new IllegalArgumentException("Entry does not balance in " + currency + ": " + sum.toPlainString());
			}
		});

		// Lock balance rows in account id order so two entries touching the same accounts cannot deadlock.
		postings.stream().sorted(Comparator.comparingLong(Posting::accountId)).forEach(p -> {
			boolean guarded = p.amount().signum() < 0 && !accounts.get(p.accountId()).type().allowNegative;
			String sql = "UPDATE account_balance SET balance = balance + :amount WHERE account_id = :id"
					+ (guarded ? " AND balance + :amount >= 0" : "");
			int rows = jdbc.sql(sql).param("amount", p.amount()).param("id", p.accountId()).update();
			if (rows == 0) {
				throw new InsufficientFundsException(p.accountId());
			}
		});

		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql("INSERT INTO journal_entry (type, reference) VALUES (?, ?)").params(type, reference).update(key);
		long entryId = key.getKey().longValue();
		for (Posting p : postings) {
			jdbc.sql("INSERT INTO posting (entry_id, account_id, amount) VALUES (?, ?, ?)")
				.params(entryId, p.accountId(), p.amount())
				.update();
		}
		return entryId;
	}

	/** Non-zero, no finer than the currency allows, and fits DECIMAL(38,18) (20 digits before the point). */
	public static void requireValidAmount(Currency currency, BigDecimal amount) {
		if (amount.signum() == 0 || amount.stripTrailingZeros().scale() > currency.scale
				|| amount.precision() - amount.scale() > 20) {
			throw new WalletException(ErrorCode.INVALID_AMOUNT,
					amount.toPlainString() + " is not a valid " + currency + " amount");
		}
	}

	public record Balance(Currency currency, BigDecimal available, BigDecimal held) {
	}

	/** One posting, labelled by its entry's (type, reference), which is unique and safe to show merchants. */
	public record Entry(String type, String reference, AccountType account, BigDecimal amount, Instant createdAt) {
	}

	/** Available and held per currency the owner has used. */
	public List<Balance> balances(String owner) {
		return jdbc.sql("""
				SELECT a.currency,
				       SUM(IF(a.type = 'AVAILABLE', b.balance, 0)),
				       SUM(IF(a.type = 'HELD', b.balance, 0))
				FROM account a JOIN account_balance b ON b.account_id = a.id
				WHERE a.owner = ?
				GROUP BY a.currency
				ORDER BY a.currency""")
			.param(owner)
			.query((rs, i) -> {
				Currency currency = Currency.valueOf(rs.getString(1));
				return new Balance(currency, rs.getBigDecimal(2).setScale(currency.scale),
						rs.getBigDecimal(3).setScale(currency.scale));
			})
			.list();
	}

	/** Postings on the owner's accounts in one currency, newest first. */
	public List<Entry> entries(String owner, Currency currency) {
		// shortcut: newest 100 only, add a cursor (?before=<posting id>) when merchants need full history.
		return jdbc.sql("""
				SELECT e.type, e.reference, a.type, p.amount, e.created_at
				FROM posting p
				JOIN account a ON a.id = p.account_id
				JOIN journal_entry e ON e.id = p.entry_id
				WHERE a.owner = ? AND a.currency = ?
				ORDER BY p.id DESC
				LIMIT 100""")
			.params(owner, currency.name())
			.query((rs, i) -> new Entry(rs.getString(1), rs.getString(2), AccountType.valueOf(rs.getString(3)),
					rs.getBigDecimal(4).setScale(currency.scale), rs.getTimestamp(5).toInstant()))
			.list();
	}

	private Map<Long, Account> loadAccounts(List<Posting> postings) {
		if (postings.size() < 2) {
			throw new IllegalArgumentException("An entry needs at least two postings");
		}
		Map<Long, Account> accounts = new HashMap<>();
		// Locking read: account() may have returned an id committed after this transaction's snapshot.
		// The posting inserts take the same shared locks for their foreign key checks anyway.
		jdbc.sql("SELECT id, currency, type FROM account WHERE id IN (:ids) FOR SHARE")
			.param("ids", postings.stream().map(Posting::accountId).distinct().toList())
			.query((rs, i) -> new Account(rs.getLong(1), Currency.valueOf(rs.getString(2)),
					AccountType.valueOf(rs.getString(3))))
			.list()
			.forEach(a -> accounts.put(a.id(), a));
		return accounts;
	}

}
