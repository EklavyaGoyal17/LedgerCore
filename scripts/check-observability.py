"""Verify real Compose metrics, provisioning and one complete simulated-transfer trace.

Python standard library only. Reads local .env configuration without printing secrets.
Creates two simulation accounts, a 25.00 deposit and a 1.23 transfer; retains evidence.
"""

import argparse
import base64
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from database_tools import ROOT, compose, sql


def settings():
    values = {}
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip("\"'")
    values.update(os.environ)
    return values


def request(base, path, method="GET", body=None, headers=None):
    supplied = {"Accept": "application/json"}
    supplied.update(headers or {})
    if body is not None:
        supplied["Content-Type"] = "application/json"
    req = urllib.request.Request(base.rstrip("/") + path, method=method,
        data=None if body is None else json.dumps(body).encode(), headers=supplied)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        # Deliberately exclude response bodies and request headers, including tokens/passwords.
        raise RuntimeError(f"{method} {path.split('?')[0]} returned HTTP {failure.code}") from None
    except urllib.error.URLError:
        raise RuntimeError(f"Cannot reach {base}{path.split('?')[0]}") from None


def eventually(check, timeout=120):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (RuntimeError, AssertionError, KeyError) as failure:
            last = str(failure)
        time.sleep(2)
    raise RuntimeError(f"Telemetry did not become ready within {timeout}s: {last or 'condition remained false'}")


def spans(document):
    result = []
    for batch in document.get("batches", document.get("resourceSpans", [])):
        for scope in batch.get("scopeSpans", batch.get("instrumentationLibrarySpans", [])):
            result.extend(scope.get("spans", []))
    return result


def identifier(value):
    if not value:
        return ""
    if re.fullmatch(r"[a-fA-F0-9]+", value):
        return value.lower()
    return base64.b64decode(value).hex()


def attributes(span):
    return {item["key"]: next(iter(item.get("value", {}).values()), None)
            for item in span.get("attributes", [])}


def trace_evidence(document, trace_id):
    found = spans(document)
    names = sorted({span.get("name", "") for span in found})
    by_name = {span.get("name"): span for span in found}
    needed = {"ledgercore.transfer", "ledgercore.journal.post", "ledgercore.outbox.publish"}
    assert needed.issubset(by_name), f"Required application spans missing; observed names={names}"
    server = [span for span in found if str(span.get("kind")) in ("2", "SPAN_KIND_SERVER")]
    producers = [span for span in found if str(span.get("kind")) in ("3", "SPAN_KIND_PRODUCER")
                 and attributes(span).get("messaging.system") == "kafka"]
    consumers = [span for span in found if str(span.get("kind")) in ("4", "SPAN_KIND_CONSUMER")
                 and attributes(span).get("messaging.system") == "kafka"]
    assert server and producers and consumers, f"HTTP/Kafka producer/consumer spans missing; observed names={names}"
    assert all(identifier(span.get("traceId")) == trace_id for span in found), "Trace contains an unexpected trace ID"
    parents = {identifier(span["spanId"]): identifier(span.get("parentSpanId")) for span in found}

    def ancestor(parent, child):
        parent_id, current = identifier(parent["spanId"]), identifier(child["spanId"])
        visited = set()
        while current and current not in visited:
            if current == parent_id:
                return True
            visited.add(current)
            current = parents.get(current, "")
        return False

    transfer = by_name["ledgercore.transfer"]
    journal = by_name["ledgercore.journal.post"]
    outbox = by_name["ledgercore.outbox.publish"]
    assert any(ancestor(span, transfer) for span in server), "Transfer is detached from HTTP trace"
    assert ancestor(transfer, journal), "Journal is detached from financial command"
    assert ancestor(transfer, outbox), "Outbox lost originating transaction trace context"
    assert any(ancestor(outbox, producer) and any(ancestor(producer, consumer) for consumer in consumers)
               for producer in producers), "Kafka producer/consumer trace context is disconnected"
    return {"traceId": trace_id, "spanNames": names, "spanCount": len(found),
            "connectedHttpTransferJournalOutboxKafka": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".tools" / "observability-check.json")
    args = parser.parse_args()
    cfg = settings()
    validation = compose("exec", "-T", "prometheus", "promtool", "test", "rules", "/dev/stdin",
        input=(ROOT / "infrastructure" / "prometheus" / "alerts-test.yml").read_bytes(),
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert b"SUCCESS" in validation.stdout, "Prometheus alert behavior tests did not complete"
    api = cfg.get("BASE_URL", f"http://localhost:{cfg.get('API_PORT', '8080')}")
    prometheus = cfg.get("PROMETHEUS_URL", f"http://localhost:{cfg.get('PROMETHEUS_PORT', '9090')}")
    grafana = cfg.get("GRAFANA_URL", f"http://localhost:{cfg.get('GRAFANA_PORT', '3000')}")
    basic = base64.b64encode((cfg.get("GRAFANA_USER", "admin") + ":" +
        cfg.get("GRAFANA_PASSWORD", "local-grafana-change-me")).encode()).decode()
    grafana_headers = {"Authorization": "Basic " + basic}
    dashboard = request(grafana, "/api/dashboards/uid/ledgercore", headers=grafana_headers)["dashboard"]
    assert len(dashboard["panels"]) >= 14, "Latest dashboard is not provisioned"
    assert {"HTTP latency p95 / p99", "Financial command latency p95 / p99", "Unresolved consumer quarantine"}.issubset(
        {panel["title"] for panel in dashboard["panels"]}), "Dashboard is missing required diagnostics"
    for uid, kind in (("prometheus", "prometheus"), ("tempo", "tempo")):
        assert request(grafana, f"/api/datasources/uid/{uid}", headers=grafana_headers)["type"] == kind

    def target_ready():
        targets = request(prometheus, "/api/v1/targets")["data"]["activeTargets"]
        return any(target["labels"].get("job") == "ledgercore" and target["health"] == "up" for target in targets)
    eventually(target_ready)
    rules = request(prometheus, "/api/v1/rules")["data"]["groups"]
    rule_names = {rule["name"] for group in rules for rule in group["rules"]}
    required_rules = {"LedgerCoreOutboxBacklog", "LedgerCoreConsumerQuarantine", "LedgerCoreBackendUnavailable"}
    assert required_rules.issubset(rule_names), "Prometheus alert rules were not loaded"

    token = request(api, "/api/v1/auth/login", "POST", {
        "email": cfg.get("ADMIN_EMAIL", "admin@ledgercore.local"),
        "password": cfg.get("ADMIN_PASSWORD", "local-admin-password-change-me")})["accessToken"]
    auth = {"Authorization": "Bearer " + token}
    account_a = request(api, "/api/v1/accounts", "POST", {"currency": "INR"}, auth)["id"]
    account_b = request(api, "/api/v1/accounts", "POST", {"currency": "INR"}, auth)["id"]
    request(api, "/api/v1/admin/simulated-deposits", "POST",
        {"accountId": account_a, "amount": "25.00", "currency": "INR"},
        dict(auth, **{"Idempotency-Key": str(uuid.uuid4())}))
    trace_id = uuid.uuid4().hex
    trace_headers = dict(auth, **{"Idempotency-Key": str(uuid.uuid4()),
        "traceparent": f"00-{trace_id}-{uuid.uuid4().hex[:16]}-01"})
    transfer = request(api, "/api/v1/transfers", "POST", {
        "sourceAccountId": account_a, "destinationAccountId": account_b,
        "amount": "1.23", "currency": "INR"}, trace_headers)
    transfer_id = str(uuid.UUID(transfer["id"]))
    journal = request(api, f"/api/v1/journals/{transfer['journalId']}", headers=auth)
    assert sum(Decimal(entry["debit"]) for entry in journal["entries"]) == Decimal("1.23")
    assert sum(Decimal(entry["credit"]) for entry in journal["entries"]) == Decimal("1.23")

    def delivered():
        return sql("SELECT count(*) FROM outbox_events o JOIN processed_events p ON p.event_id=o.id "
            f"JOIN event_audit_projection a ON a.event_id=o.id WHERE o.aggregate_id='{transfer_id}'::uuid "
            "AND o.published_at IS NOT NULL") == "1"
    eventually(delivered)

    evidence = eventually(lambda: trace_evidence(request(grafana,
        f"/api/datasources/proxy/uid/tempo/api/traces/{trace_id}", headers=grafana_headers), trace_id))
    metrics = ("http_server_requests_seconds_bucket", "ledgercore_transfer_duration_seconds_bucket",
        "ledgercore_transfer_committed_total", "ledgercore_outbox_pending", "ledgercore_events_quarantined",
        "hikaricp_connections_active", "hikaricp_connections_pending", "kafka_consumer_fetch_manager_records_lag_max")

    def metrics_ready():
        checked = {}
        for metric in metrics:
            response = request(prometheus, "/api/v1/query?" + urllib.parse.urlencode({"query": metric + '{job="ledgercore"}'}))
            result = response["data"]["result"]
            assert result, f"Expected live metric missing: {metric}"
            checked[metric] = len(result)
        return checked
    evidence.update({"simulation": True, "transferId": transfer_id, "journalId": transfer["journalId"],
        "durablePublishedAndConsumed": True, "dashboardPanels": len(dashboard["panels"]),
        "alertRules": sorted(rule_names), "liveMetricSeries": eventually(metrics_ready)})
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    print("PASS: Prometheus scrape/metrics/rules, Grafana provisioning, balanced journal, durable event delivery and connected Tempo spans")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, AssertionError, KeyError) as failure:
        raise SystemExit(f"FAIL: {failure}") from None
