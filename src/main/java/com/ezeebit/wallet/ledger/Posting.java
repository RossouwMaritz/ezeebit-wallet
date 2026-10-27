package com.ezeebit.wallet.ledger;

import java.math.BigDecimal;

import com.ezeebit.wallet.Currency;

/** One leg of a journal entry. Positive raises the account's balance, negative lowers it. */
public record Posting(long accountId, Currency currency, BigDecimal amount) {
}
