-- Compare text exactly. The default (utf8mb4_0900_ai_ci) treats 'TX9a' and 'TX9A' as the same reference
-- and the same merchant; utf8mb4_bin would still treat 'TX9' and 'TX9 ' as equal.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;

-- One currency per account. owner is a merchant id, or 'platform' for house and external accounts.
-- currency and type hold names from the Currency and AccountType enums.
CREATE TABLE account (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner    VARCHAR(64) NOT NULL,
    type     VARCHAR(16) NOT NULL,
    currency VARCHAR(8)  NOT NULL,
    UNIQUE KEY uq_account (owner, type, currency)
);

-- Cache of SUM(posting.amount) per account, updated in the same transaction as the postings.
CREATE TABLE account_balance (
    account_id BIGINT PRIMARY KEY,
    balance    DECIMAL(38, 18) NOT NULL,
    FOREIGN KEY (account_id) REFERENCES account (id)
);

-- (type, reference) is unique so the same business event can never be booked twice.
-- public_id is the random id the API shows (deposits return theirs); id never leaves the service.
CREATE TABLE journal_entry (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id  CHAR(36)     NOT NULL,
    type       VARCHAR(32)  NOT NULL,
    reference  VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_entry_public_id (public_id),
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
