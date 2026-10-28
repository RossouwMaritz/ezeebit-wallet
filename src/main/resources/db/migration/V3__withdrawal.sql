-- (merchant_id, idempotency_key) is unique so a retried request can never hold funds twice.
-- public_id is what the API, the audit trail and the payout rail see; it is the rail's idempotency key, so
-- resubmitting can never pay twice. Random, so it can't be guessed, says nothing about volume, and can't
-- collide at the rail with a withdrawal from a rebuilt database or another environment. id never leaves
-- the service.
-- Destination: BANK uses account_number + bank_code, CHAIN uses network + address.
CREATE TABLE withdrawal (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id        CHAR(36)        NOT NULL,
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
    UNIQUE KEY uq_withdrawal_public_id (public_id),
    UNIQUE KEY uq_withdrawal_key (merchant_id, idempotency_key)
);
