-- (merchant_id, idempotency_key) is unique so a retried request can never hold funds twice.
-- The id is also the payout rail's idempotency key, so resubmitting can never pay twice.
-- Destination: BANK uses account_number + bank_code, CHAIN uses network + address.
CREATE TABLE withdrawal (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id      VARCHAR(64)     NOT NULL,
    idempotency_key  VARCHAR(64)     NOT NULL,
    currency         VARCHAR(8)      NOT NULL,
    amount           DECIMAL(38, 18) NOT NULL,
    destination_type VARCHAR(8)      NOT NULL,
    account_number   VARCHAR(34),
    bank_code        VARCHAR(16),
    network          VARCHAR(16),
    address          VARCHAR(128),
    state            VARCHAR(16)     NOT NULL,
    created_at       TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_withdrawal_key (merchant_id, idempotency_key)
);
