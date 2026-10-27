package com.ezeebit.wallet.ledger;

import java.math.BigDecimal;

/** One leg of a journal entry. Positive raises the account's balance, negative lowers it. */
public record Posting(long accountId, String currency, BigDecimal amount) {
}
