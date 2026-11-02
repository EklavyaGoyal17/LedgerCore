"""Provision isolated simulated fixtures and measure all k6 workloads.

Run only against a local simulated instance whose rate limit is deliberately raised
for the experiment. ADMIN_PASSWORD is read from the environment, never printed.
The output folder contains summaries and safe IDs; temporary bearer-token fixtures
are deleted in a finally block and must never be committed.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import demo
from database_tools import sql


def database_snapshot():
    return json.loads(sql("""
        SELECT json_build_object(
          'journalTransactions',(SELECT count(*) FROM journal_transactions),
          'journalEntries',(SELECT count(*) FROM journal_entries),
          'transfers',(SELECT count(*) FROM transfers),
          'pendingOutbox',(SELECT count(*) FROM outbox_events WHERE published_at IS NULL),
          'quarantinedEvents',(SELECT count(*) FROM failed_kafka_events WHERE resolved_at IS NULL),
          'databaseBytes',pg_database_size(current_database()));
        """))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("BASE_URL", "http://localhost:8080"))
    parser.add_argument("--network", default="ledgercore_default")
    parser.add_argument("--duration", default="60s")
    parser.add_argument("--vus", type=int, default=5)
    parser.add_argument("--contention-vus", type=int, default=20)
    parser.add_argument("--output", type=Path, default=Path("load-tests/results") / dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    parser.add_argument("--image", default="grafana/k6:1.0.0")
    args = parser.parse_args()
    if args.vus < 1 or args.contention_vus < 2:
        parser.error("Use positive VUs and at least two contention VUs")
    demo.BASE = args.base_url.rstrip("/")
    args.output.mkdir(parents=True, exist_ok=True)
    password = os.getenv("ADMIN_PASSWORD", "local-admin-password-change-me")
    admin = demo.ok("POST", "/api/v1/auth/login", {"email": os.getenv("ADMIN_EMAIL", "admin@ledgercore.local"), "password": password})["accessToken"]
    credentials = {"email": f"experiment-{uuid.uuid4()}@example.invalid", "password": "Simulated-load-" + uuid.uuid4().hex}
    demo.ok("POST", "/api/v1/auth/register", credentials)
    token = demo.ok("POST", "/api/v1/auth/login", credentials)["accessToken"]
    report = {"startedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "simulation": True,
              "host": {"os": platform.system(), "release": platform.release(), "architecture": platform.machine(), "logicalCPUs": os.cpu_count()},
              "durationPerMode": args.duration, "vus": args.vus, "contentionVUs": args.contention_vus,
              "k6Image": args.image, "thinkTimeSeconds": 0.1, "runs": []}
    docker_info = subprocess.run(["docker", "info", "--format", "{{json .}}"], capture_output=True, text=True, check=True)
    info = json.loads(docker_info.stdout)
    report["docker"] = {k: info.get(k) for k in ["ServerVersion", "NCPU", "MemTotal", "Driver", "KernelVersion", "Architecture"]}
    with tempfile.TemporaryDirectory(prefix="ledgercore-load-") as temporary:
        fixture_file = Path(temporary) / "fixtures.json"
        fixtures = []
        for _ in range(args.vus):
            source = demo.ok("POST", "/api/v1/accounts", {"currency": "INR"}, token)["id"]
            destination = demo.ok("POST", "/api/v1/accounts", {"currency": "INR"}, token)["id"]
            demo.ok("POST", "/api/v1/admin/simulated-deposits", {"accountId": source, "amount": "100000.00", "currency": "INR"}, admin, str(uuid.uuid4()))
            fixtures.append({"source": source, "destination": destination, "token": token})
        fixture_file.write_text(json.dumps(fixtures), encoding="utf-8")
        report["initialReconciliation"] = demo.ok("POST", "/api/v1/admin/reconciliation-runs", {}, admin)
        assert report["initialReconciliation"]["status"] == "CLEAN", report["initialReconciliation"]
        report["initialDatabase"] = database_snapshot()
        for mode in ["accounts", "balance", "transfers", "contention", "mixed"]:
            run_id = str(uuid.uuid4())
            # Tokens are inside the short-lived read-only mount, never command arguments or results.
            command = ["docker", "run", "--rm", "--network", args.network,
                       "-v", str(Path(__file__).resolve().parents[1] / "load-tests") + ":/work:ro",
                       "-v", str(Path(temporary).resolve()) + ":/fixtures:ro",
                       "-v", str(args.output.resolve()) + ":/results",
                       "-e", "BASE_URL=http://backend:8080", "-e", "FIXTURES_FILE=/fixtures/fixtures.json",
                       "-e", "MODE=" + mode, "-e", "RUN_ID=" + run_id,
                       "-e", "VUS=" + str(args.contention_vus if mode == "contention" else args.vus),
                       "-e", "DURATION=" + args.duration, args.image, "run", "--summary-trend-stats", "avg,min,med,max,p(90),p(95),p(99)",
                       "--summary-export", "/results/" + mode + ".json", "/work/workload.js"]
            started = time.monotonic()
            result = subprocess.run(command, capture_output=True, text=True)
            args.output.joinpath(mode + ".txt").write_text(result.stdout + result.stderr, encoding="utf-8")
            run = {"mode": mode, "runId": run_id, "exitCode": result.returncode, "elapsedSeconds": round(time.monotonic() - started, 3)}
            run["databaseAfterMode"] = database_snapshot()
            stats = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}",
                                    "ledgercore-backend-1", "ledgercore-postgres-1"], capture_output=True, text=True, check=True)
            run["resourceSnapshotAfterMode"] = [json.loads(line) for line in stats.stdout.splitlines() if line.strip()]
            report["runs"].append(run)
            print(f"{mode}: {'PASS' if result.returncode == 0 else 'FAIL'}", flush=True)
            if result.returncode:
                print(result.stdout[-2000:] + result.stderr[-1000:], file=sys.stderr)
                break
        report["finalReconciliation"] = demo.ok("POST", "/api/v1/admin/reconciliation-runs", {}, admin)
        report["finishedAt"] = dt.datetime.now(dt.timezone.utc).isoformat()
        report["finalDatabase"] = database_snapshot()
        args.output.joinpath("experiment.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        assert report["finalReconciliation"]["status"] == "CLEAN", report["finalReconciliation"]
        return 0 if len(report["runs"]) == 5 and all(r["exitCode"] == 0 for r in report["runs"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
