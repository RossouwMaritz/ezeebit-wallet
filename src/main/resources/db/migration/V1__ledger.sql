CREATE TABLE currency (
    code  VARCHAR(8) PRIMARY KEY,
    scale TINYINT    NOT NULL
);

-- One currency per account. owner is a merchant id, or 'platform' for house and external accounts.
CREATE TABLE account (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner          VARCHAR(64) NOT NULL,
    type           VARCHAR(16) NOT NULL,
    currency       VARCHAR(8)  NOT NULL,
    allow_negative BOOLEAN     NOT NULL,
    UNIQUE KEY uq_account (owner, type, currency),
    FOREIGN KEY (currency) REFERENCES currency (code)
);

-- Cache of SUM(posting.amount) per account, updated in the same transaction as the postings.
CREATE TABLE account_balance (
    account_id BIGINT PRIMARY KEY,
    balance    DECIMAL(38, 18) NOT NULL,
    FOREIGN KEY (account_id) REFERENCES account (id)
);

-- (type, reference) is unique so the same business event can never be booked twice.
CREATE TABLE journal_entry (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    type       VARCHAR(32)  NOT NULL,
    reference  VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_entry (type, reference)
);

-- Signed: positive raises the account's balance, negative lowers it. Sums to zero per currency per entry.
CREATE TABLE posting (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id   BIGINT          NOT NULL,
    account_id BIGINT          NOT NULL,
    amount     DECIMAL(38, 18) NOT NULL,
    KEY ix_posting_account (account_id, id),
    FOREIGN KEY (entry_id) REFERENCES journal_entry (id),
    FOREIGN KEY (account_id) REFERENCES account (id)
);
