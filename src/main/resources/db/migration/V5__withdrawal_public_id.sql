-- The id the API, the audit trail and the payout rail see. Random, so it can't be guessed, says nothing about
-- volume, and can't collide with a withdrawal from a rebuilt database or another environment at the rail.
-- The auto-increment id stays the primary key and never leaves the service.
ALTER TABLE withdrawal ADD COLUMN public_id CHAR(36) NULL AFTER id;
UPDATE withdrawal SET public_id = UUID();
ALTER TABLE withdrawal MODIFY public_id CHAR(36) NOT NULL, ADD UNIQUE KEY uq_withdrawal_public_id (public_id);
