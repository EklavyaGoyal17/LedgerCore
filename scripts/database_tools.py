"""Binary-safe PostgreSQL backup/restore helpers for the LedgerCore Compose project."""

import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def compose(*arguments, **kwargs):
    return subprocess.run(
        ["docker", "compose", "-p", "ledgercore", "-f", str(ROOT / "docker-compose.yml"), *arguments],
        cwd=ROOT,
        check=True,
        **kwargs,
    )


def sql(query, database="ledgercore"):
    return compose(
        "exec", "-T", "postgres", "psql", "-X", "-v", "ON_ERROR_STOP=1",
        "-U", "ledgercore", "-d", database, "-At", "-c", query,
        stdout=subprocess.PIPE,
    ).stdout.decode("utf-8").strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def snapshot(database="ledgercore"):
    # Ledger is authoritative; this checks every derived customer balance as well
    # as balanced journals and their required successful business outcome.
    query = """
    SELECT json_build_object(
      'journals', (SELECT count(*) FROM journal_transactions),
      'entries', (SELECT count(*) FROM journal_entries),
      'transfers', (SELECT count(*) FROM transfers),
      'idempotency', (SELECT count(*) FROM idempotency_records),
      'audit', (SELECT count(*) FROM audit_events),
      'outbox', (SELECT count(*) FROM outbox_events),
      'accounts', (SELECT count(*) FROM financial_accounts),
      'unbalanced', (SELECT count(*) FROM journal_transactions j WHERE
        (SELECT coalesce(sum(debit_minor-credit_minor),0) FROM journal_entries e WHERE e.journal_id=j.id) <> 0
        OR (SELECT count(*) FROM journal_entries e WHERE e.journal_id=j.id) < 2),
      'orphans', (SELECT count(*) FROM journal_transactions j WHERE NOT EXISTS
        (SELECT 1 FROM transfers t WHERE t.journal_id=j.id AND t.status='COMPLETED')),
      'projection_mismatches', (SELECT count(*) FROM financial_accounts a WHERE a.balance_minor < 0 OR a.balance_minor <>
        (SELECT coalesce(sum(credit_minor-debit_minor),0) FROM journal_entries e WHERE e.ledger_account_id=a.ledger_account_id)),
      'unfinished', (SELECT count(*) FROM idempotency_records WHERE state <> 'FINISHED')
    );
    """
    result = json.loads(sql(query, database))
    for field in ("unbalanced", "orphans", "projection_mismatches", "unfinished"):
        if result[field] != 0:
            raise RuntimeError(f"Database {database} failed {field}: {result[field]}")
    return result


def backup(path):
    path = Path(path).resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    manifest = path.with_suffix(path.suffix + ".json")
    if manifest.exists():
        raise FileExistsError(f"Backup manifest already exists: {manifest}")
    # Exclusive creation protects existing backups from accidental replacement.
    with path.open("xb") as stream:
        compose("exec", "-T", "postgres", "pg_dump", "-U", "ledgercore", "-d", "ledgercore",
                "--format=custom", "--no-owner", "--no-acl", stdout=stream)
    metadata = {"format": "PostgreSQL custom", "sha256": sha256(path), "source": "ledgercore"}
    with manifest.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(metadata, indent=2) + "\n")
    return metadata


def restore(path, database):
    if not re.fullmatch(r"ledgercore_restore_[a-z0-9_]{1,40}", database):
        raise ValueError("Restore target must be a NEW database named ledgercore_restore_<lowercase suffix>")
    path = Path(path).resolve()
    metadata = json.loads(path.with_suffix(path.suffix + ".json").read_text(encoding="utf-8"))
    if metadata["sha256"] != sha256(path):
        raise ValueError("Backup checksum mismatch")
    # CREATE DATABASE fails if target exists. No --clean, DROP, or volume deletion.
    sql(f'CREATE DATABASE "{database}"')
    with path.open("rb") as stream:
        compose("exec", "-T", "postgres", "pg_restore", "-U", "ledgercore", "-d", database,
                "--exit-on-error", "--single-transaction", "--no-owner", "--no-acl", stdin=stream)
    return snapshot(database)
