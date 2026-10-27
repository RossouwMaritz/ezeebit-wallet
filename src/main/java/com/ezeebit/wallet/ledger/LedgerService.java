package com.ezeebit.wallet.ledger;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ezeebit.wallet.ErrorCode;
import com.ezeebit.wallet.WalletException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

	private record Account(long id, String currency, int scale, boolean allowNegative) {
	}

	private final JdbcClient jdbc;

	LedgerService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns the account id, creating the account (balance 0) on first use. */
	@Transactional
	public long account(String owner, AccountType type, String currency) {
		Optional<Long> existing = findAccount(owner, type, currency);
		if (existing.isPresent()) {
			return existing.get();
		}
		// Upsert, not INSERT IGNORE: IGNORE would also swallow the foreign key error for an unknown currency.
		// LAST_INSERT_ID(id) hands back the id even when another transaction created the row after our
		// snapshot was taken, which a plain re-SELECT under REPEATABLE READ would not see.
		jdbc.sql("""
				INSERT INTO account (owner, type, currency, allow_negative) VALUES (?, ?, ?, ?)
				ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)""")
			.params(owner, type.name(), currency, type.allowNegative)
			.update();
		long id = jdbc.sql("SELECT LAST_INSERT_ID()").query(Long.class).single();
		jdbc.sql("INSERT IGNORE INTO account_balance (account_id, balance) VALUES (?, 0)").param(id).update();
		return id;
	}

	private Optional<Long> findAccount(String owner, AccountType type, String currency) {
		return jdbc.sql("SELECT id FROM account WHERE owner = ? AND type = ? AND currency = ?")
			.params(owner, type.name(), currency)
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
		Map<String, BigDecimal> sums = new HashMap<>();
		for (Posting p : postings) {
			Account account = accounts.get(p.accountId());
			if (account == null) {
				throw new IllegalArgumentException("Unknown account " + p.accountId());
			}
			if (!account.currency().equals(p.currency())) {
				throw new WalletException(ErrorCode.CURRENCY_MISMATCH,
						"Account " + account.id() + " holds " + account.currency() + ", not " + p.currency());
			}
			if (p.amount().signum() == 0 || p.amount().stripTrailingZeros().scale() > account.scale()) {
				throw new WalletException(ErrorCode.INVALID_AMOUNT,
						p.amount().toPlainString() + " is not a valid " + p.currency() + " amount");
			}
			sums.merge(p.currency(), p.amount(), BigDecimal::add);
		}
		sums.forEach((currency, sum) -> {
			if (sum.signum() != 0) {
				throw new IllegalArgumentException("Entry does not balance in " + currency + ": " + sum.toPlainString());
			}
		});

		// Lock balance rows in account id order so two entries touching the same accounts cannot deadlock.
		postings.stream().sorted(Comparator.comparingLong(Posting::accountId)).forEach(p -> {
			boolean guarded = p.amount().signum() < 0 && !accounts.get(p.accountId()).allowNegative();
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

	private Map<Long, Account> loadAccounts(List<Posting> postings) {
		if (postings.size() < 2) {
			throw new IllegalArgumentException("An entry needs at least two postings");
		}
		Map<Long, Account> accounts = new HashMap<>();
		jdbc.sql("""
				SELECT a.id, a.currency, c.scale, a.allow_negative
				FROM account a JOIN currency c ON c.code = a.currency
				WHERE a.id IN (:ids)""")
			.param("ids", postings.stream().map(Posting::accountId).distinct().toList())
			.query((rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4)))
			.list()
			.forEach(a -> accounts.put(a.id(), a));
		return accounts;
	}

}
