# Deployment, database permissions, and recovery

LedgerCore's Compose deployment is a local financial simulation. The backend runs as a non-root user, with a read-only root filesystem, a bounded temporary filesystem, dropped Linux capabilities, no privilege escalation, and a 30-second graceful shutdown window. Only explicitly configured loopback API/observability ports are exposed.

## Database identities and upgrades

Copy `.env.example` to `.env` and replace every local password and JWT secret before a shared deployment. `DATABASE_PASSWORD` authenticates the Compose PostgreSQL administrative bootstrap user; `MIGRATION_PASSWORD` and `RUNTIME_PASSWORD` authenticate separate roles. The backend receives only the runtime database credential. No migration or administrative credentials are passed to it.

`docker compose up --build -d --wait --wait-timeout 300` first runs the one-shot `db-bootstrap` service. Its SQL creates or updates `ledgercore_migrator` and `ledgercore_runtime`, transfers application schema/table/function ownership to the migrator, and removes public/runtime schema creation privileges. This also upgrades an existing PostgreSQL volume in place. It does **not** delete or recreate the live database, and does not use cluster-wide ownership reassignment. Keep the existing `DATABASE_PASSWORD` when upgrading an existing volume: the image's initialization environment cannot reset an already initialized administrator password.

The one-shot `migrate` service launches the same application jar with `--migrate-only`. That branch runs Flyway programmatically using `DATABASE_URL`, `FLYWAY_USER`, and `FLYWAY_PASSWORD` and exits without creating an HTTP server, Spring context, JPA datasource, or security beans. It receives no runtime database credential or JWT secret. The backend starts only after successful migration, with `SPRING_FLYWAY_ENABLED=false` and only its `DATABASE_USER`/`DATABASE_PASSWORD`. Migration failure prevents backend startup. Native/test Spring startup keeps Flyway enabled by default; it supports separate `MIGRATION_USER`/`MIGRATION_PASSWORD` and falls back to datasource credentials for isolated developer/test compatibility.

`R__runtime_privileges.sql` runs after versioned migrations. Runtime can select and insert application records and update the small set of mutable operational/projection tables. It cannot truncate, create tables, disable triggers, delete journals, update immutable financial history, or read/write Flyway history. This protects against accidental destructive application queries; a runtime credential still has broad application data access and is not a substitute for API authorization. Compose isolates powerful credentials to the bootstrap and migration jobs. Protect Docker access and the local environment file because the host administrator can inspect those jobs.

Do not change previously applied versioned migrations: add a new migration and update repeatable grants if its runtime capabilities differ. Changing role passwords is done by updating `.env` and rerunning `docker compose up --force-recreate db-bootstrap`, followed by recreating the backend. Existing PostgreSQL client sessions may need reconnecting.

## Backups and restore drills

The Python CLIs work on Windows, macOS, and Linux and require Python 3 plus Docker Compose. They stream the PostgreSQL custom format directly through binary file handles, avoiding PowerShell text encoding corruption. Backups are consistent `pg_dump` snapshots; dump and checksum manifest files are never intentionally overwritten. The `backups/` directory is ignored by Git. Restrict filesystem access and encrypt copied backups because they contain user information and financial history.

```powershell
python scripts/backup.py backups/ledgercore-20261024.dump
python scripts/restore.py backups/ledgercore-20261024.dump --database ledgercore_restore_manual
```

The restore CLI checks the manifest's SHA-256 before writing, creates a **new** database, restores in one transaction, then checks balances derived from entries, journal balancing, orphan journals, and unfinished idempotency records. It refuses live database names or existing targets and never drops data. A failed restore leaves its newly created database for investigation. This script is a verification restore, not an automatic production failover or live cutover mechanism. Restored objects belong to the administrative restore user; use deployment provisioning before giving an application access to a recovered production database.

Pause writers and use the following for an end-to-end drill. It compares source counts before/after and the restored ledger invariants, then deletes only the random disposable database created by that invocation. Existing databases and volumes remain untouched. Do not run `docker compose down -v` as a recovery step.

```powershell
python scripts/restore-drill.py
```

This validates logical recovery. Production operations must additionally define encrypted off-host retention, recovery point/time objectives, physical backup/PITR with WAL archiving, restore authorization, and repeated recovery exercises. A backup stored only on the same workstation does not survive loss of that workstation.

## Abrupt application process recovery

`ProcessRecoveryIT` uses a real PostgreSQL Testcontainer with separate migration/runtime roles and launches a separate Java process for each application invocation. A migration-only JVM first migrates without booting the web application. Runtime JVMs have Flyway disabled and assert that no migration credential variables were inherited. A test-only `CrashHarnessMain` boots the full application. Its test-only primary `Faults` bean calls `Runtime.halt(91)` at `BEFORE_COMMIT` or `AFTER_COMMIT`. The harness exists under `src/test` and is absent from the packaged application; there is no production crash endpoint.

After abrupt termination a new JVM reconnects to the same database and retries the same actor, key, and payload. Before-commit death must leave no posting; after-commit death must leave exactly one. Two subsequent independent replay JVMs must return the same stored transfer response. Assertions check the final source balance, one transfer, two journal entries, one financial outbox event, one audit event, and clean reconciliation. Another test executes deposit, transfer, and reversal under the runtime role, then proves forbidden DDL, truncation, trigger disabling, immutable updates/deletes, and Flyway history access fail under that connection.

Kafka publication and Redis rate limiting are disabled in this focused financial recovery test. Kafka outages, poison records, consumer replay, and broker delivery recovery are covered by `EventInfrastructureIT`. Neither test establishes multi-node PostgreSQL failover or lossless recovery from destruction of the PostgreSQL data volume.
