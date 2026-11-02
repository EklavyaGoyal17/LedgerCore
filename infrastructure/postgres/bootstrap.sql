-- One-shot Compose provisioning. Safe for fresh AND existing LedgerCore volumes.
-- The administrative connection stays in this service, never in the application.
SELECT 'CREATE ROLE ledgercore_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ledgercore_migrator') \gexec
SELECT 'CREATE ROLE ledgercore_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ledgercore_runtime') \gexec
SELECT format('ALTER ROLE ledgercore_migrator PASSWORD %L', :'migration_password') \gexec
SELECT format('ALTER ROLE ledgercore_runtime PASSWORD %L', :'runtime_password') \gexec
ALTER ROLE ledgercore_migrator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE ledgercore_runtime NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
REVOKE ledgercore_migrator FROM ledgercore_runtime;
SELECT format('ALTER DATABASE %I OWNER TO ledgercore_migrator', current_database()) \gexec
SELECT format('REVOKE CREATE, TEMPORARY ON DATABASE %I FROM PUBLIC', current_database()) \gexec
ALTER SCHEMA public OWNER TO ledgercore_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM ledgercore_runtime;
GRANT USAGE ON SCHEMA public TO ledgercore_runtime;
SELECT format('GRANT CONNECT ON DATABASE %I TO ledgercore_runtime', current_database()) \gexec
-- Transfer only this application's schema objects; never use REASSIGN OWNED on a
-- cluster superuser, which could alter shared/template database ownership.
SELECT format('ALTER TABLE public.%I OWNER TO ledgercore_migrator', c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relkind IN ('r','p') \gexec
SELECT format('ALTER SEQUENCE public.%I OWNER TO ledgercore_migrator', c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relkind='S' \gexec
SELECT format('ALTER FUNCTION %s OWNER TO ledgercore_migrator', p.oid::regprocedure)
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='public' AND p.prokind='f' \gexec
