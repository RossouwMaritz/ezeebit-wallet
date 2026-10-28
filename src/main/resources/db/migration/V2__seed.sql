-- House FX stock, one row per Currency enum value, funded from treasury (which may go negative).
CREATE TEMPORARY TABLE fx_stock (currency VARCHAR(8), amount DECIMAL(38, 18));
INSERT INTO fx_stock VALUES
    ('ZAR', 1000000), ('NGN', 100000000), ('KES', 10000000), ('USDT', 100000), ('USDC', 100000);

INSERT INTO account (owner, type, currency)
SELECT 'platform', t.type, s.currency
FROM fx_stock s
CROSS JOIN (SELECT 'FX' AS type UNION ALL SELECT 'TREASURY') t;

INSERT INTO journal_entry (public_id, type, reference) VALUES (UUID(), 'SEED', 'fx-stock');
SET @entry = LAST_INSERT_ID();

INSERT INTO posting (entry_id, account_id, amount)
SELECT @entry, a.id, IF(a.type = 'FX', s.amount, -s.amount)
FROM account a
JOIN fx_stock s ON s.currency = a.currency;

INSERT INTO account_balance (account_id, balance)
SELECT account_id, SUM(amount) FROM posting GROUP BY account_id;

DROP TEMPORARY TABLE fx_stock;
