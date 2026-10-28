-- A firm price for one conversion. The id is the conversion's idempotency key: entry_id is set in the
-- same transaction that books the CONVERSION entry, so a quote executes at most once.
CREATE TABLE quote (
    id            CHAR(36)        PRIMARY KEY,
    merchant_id   VARCHAR(64)     NOT NULL,
    sell_currency VARCHAR(8)      NOT NULL,
    sell_amount   DECIMAL(38, 18) NOT NULL,
    buy_currency  VARCHAR(8)      NOT NULL,
    buy_amount    DECIMAL(38, 18) NOT NULL,
    rate          DECIMAL(38, 18) NOT NULL,
    expires_at    TIMESTAMP(6)    NOT NULL,
    entry_id      BIGINT,
    created_at    TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (entry_id) REFERENCES journal_entry (id)
);
