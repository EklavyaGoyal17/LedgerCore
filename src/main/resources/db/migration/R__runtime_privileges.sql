-- Testcontainers uses an isolated owner without this optional deployment role.
-- Repeatable grants cover new migrations while avoiding ownership/DDL/TRUNCATE.
DO $$
DECLARE t text;
BEGIN
 IF EXISTS (SELECT FROM pg_roles WHERE rolname='ledgercore_runtime') THEN
   REVOKE ALL ON ALL TABLES IN SCHEMA public FROM ledgercore_runtime;
   REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM ledgercore_runtime;
   FOREACH t IN ARRAY ARRAY['app_users','ledger_accounts','financial_accounts',
      'journal_transactions','journal_entries','transfers','idempotency_records',
      'audit_events','outbox_events','processed_events','event_audit_projection',
      'reconciliation_runs','failed_kafka_events','event_replay_attempts']
   LOOP
     IF to_regclass('public.' || t) IS NOT NULL THEN
       EXECUTE format('GRANT SELECT, INSERT ON TABLE public.%I TO ledgercore_runtime', t);
     END IF;
   END LOOP;
   -- PostgreSQL SELECT FOR UPDATE needs UPDATE privilege on at least one column.
   -- Reversal serializes on the original transfer; its immutable trigger rejects
   -- every actual row update, including no-op updates to the identifier.
   GRANT UPDATE (id) ON transfers TO ledgercore_runtime;
   FOREACH t IN ARRAY ARRAY['app_users','financial_accounts','idempotency_records',
                            'outbox_events','failed_kafka_events']
   LOOP
     IF to_regclass('public.' || t) IS NOT NULL THEN
       EXECUTE format('GRANT UPDATE ON TABLE public.%I TO ledgercore_runtime', t);
     END IF;
   END LOOP;
 END IF;
END $$;
