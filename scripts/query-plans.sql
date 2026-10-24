-- Read-only investigations. Run inside psql connected to a seeded simulation DB.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM outbox_events
WHERE published_at IS NULL AND next_attempt_at <= clock_timestamp()
ORDER BY created_at,id LIMIT 25;

EXPLAIN (ANALYZE, BUFFERS)
SELECT a.id,coalesce(sum(e.credit_minor-e.debit_minor),0)
FROM financial_accounts a LEFT JOIN journal_entries e ON e.ledger_account_id=a.ledger_account_id
GROUP BY a.id;

EXPLAIN (ANALYZE, BUFFERS)
SELECT e.journal_id,e.debit_minor,e.credit_minor
FROM journal_entries e WHERE e.ledger_account_id=(SELECT ledger_account_id FROM financial_accounts WHERE balance_minor>0 LIMIT 1)
ORDER BY e.created_at,e.id LIMIT 50;
