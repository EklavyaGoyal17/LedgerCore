-- Read-only investigations. Run inside psql connected to a seeded simulation DB.
SELECT current_timestamp AS captured_at,
  (SELECT count(*) FROM journal_transactions) AS journals,
  (SELECT count(*) FROM journal_entries) AS entries,
  (SELECT count(*) FROM financial_accounts) AS financial_accounts;

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

-- Pick the busiest ledger once; exclude fixture-selection cost from the endpoint plan.
SELECT ledger_account_id AS statement_ledger
FROM journal_entries GROUP BY ledger_account_id ORDER BY count(*) DESC LIMIT 1
\gset

-- Actual AccountService.statement window and display ordering, first page.
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM (
  SELECT e.id,e.journal_id,j.reference,j.kind,e.debit_minor,e.credit_minor,j.posted_at,
    sum(e.credit_minor-e.debit_minor) OVER
      (ORDER BY j.posted_at,e.id ROWS UNBOUNDED PRECEDING) AS running
  FROM journal_entries e JOIN journal_transactions j ON j.id=e.journal_id
  WHERE e.ledger_account_id=:'statement_ledger'::uuid
) s ORDER BY posted_at,id LIMIT 50 OFFSET 0;
