INSERT INTO currency (code, scale) VALUES ('ZAR', 2), ('NGN', 2), ('KES', 2), ('USDT', 6), ('USDC', 6);

-- House FX stock per currency, funded from treasury (the external account that may go negative).
INSERT INTO account (owner, type, currency, allow_negative)
SELECT 'platform', t.type, c.code, t.type = 'TREASURY'
FROM currency c
CROSS JOIN (SELECT 'FX' AS type UNION ALL SELECT 'TREASURY') t;

INSERT INTO journal_entry (type, reference) VALUES ('SEED', 'fx-stock');
SET @entry = LAST_INSERT_ID();

INSERT INTO posting (entry_id, account_id, amount)
SELECT @entry, a.id, IF(a.type = 'FX', s.amount, -s.amount)
FROM account a
JOIN (SELECT 'ZAR' AS currency, 1000000 AS amount
      UNION ALL SELECT 'NGN', 100000000
      UNION ALL SELECT 'KES', 10000000
      UNION ALL SELECT 'USDT', 100000
      UNION ALL SELECT 'USDC', 100000) s ON s.currency = a.currency
WHERE a.owner = 'platform';

INSERT INTO account_balance (account_id, balance)
SELECT account_id, SUM(amount) FROM posting GROUP BY account_id;
