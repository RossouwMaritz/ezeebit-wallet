-- The id an entry can be shown and looked up by (deposits return theirs). Random, for the same reasons as
-- withdrawal.public_id; the auto-increment id stays the primary key and never leaves the service.
ALTER TABLE journal_entry ADD COLUMN public_id CHAR(36) NULL AFTER id;
UPDATE journal_entry SET public_id = UUID();
ALTER TABLE journal_entry MODIFY public_id CHAR(36) NOT NULL, ADD UNIQUE KEY uq_entry_public_id (public_id);
