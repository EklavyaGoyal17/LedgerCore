"""Repeatable API acceptance demo. Python 3 standard library; no real money.

Usage: python scripts/demo.py
Set BASE_URL, ADMIN_EMAIL and ADMIN_PASSWORD if local Compose defaults changed.
"""
import concurrent.futures
import json
import os
import uuid
import urllib.error
import urllib.request

BASE = os.getenv("BASE_URL", "http://localhost:8080")


def request(method, path, body=None, token=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    req = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, json.load(res)
    except urllib.error.HTTPError as ex:
        return ex.code, json.load(ex)


def ok(method, path, body=None, token=None, key=None):
    status, result = request(method, path, body, token, key)
    assert status < 300, (status, result)
    return result


def main():
    admin = ok("POST", "/api/v1/auth/login", {"email": os.getenv("ADMIN_EMAIL", "admin@ledgercore.local"), "password": os.getenv("ADMIN_PASSWORD", "local-admin-password-change-me")})["accessToken"]
    users = []
    for _ in range(2):
        credentials = {"email": f"demo-{uuid.uuid4()}@example.invalid", "password": "Demo-password-for-simulation-123!"}
        ok("POST", "/api/v1/auth/register", credentials)
        users.append(ok("POST", "/api/v1/auth/login", credentials)["accessToken"])
    a = ok("POST", "/api/v1/accounts", {"currency": "INR"}, users[0])["id"]
    b = ok("POST", "/api/v1/accounts", {"currency": "INR"}, users[1])["id"]
    c = ok("POST", "/api/v1/accounts", {"currency": "INR"}, users[1])["id"]
    ok("POST", "/api/v1/admin/simulated-deposits", {"accountId": a, "amount": "1500.00", "currency": "INR"}, admin, str(uuid.uuid4()))
    body = {"sourceAccountId": a, "destinationAccountId": b, "amount": "500.00", "currency": "INR"}
    key = str(uuid.uuid4())
    transfer = ok("POST", "/api/v1/transfers", body, users[0], key)
    replay = ok("POST", "/api/v1/transfers", body, users[0], key)
    assert replay == transfer
    assert ok("GET", f"/api/v1/accounts/{a}/balance", token=users[0])["amount"] == "1000.00"
    assert ok("GET", f"/api/v1/accounts/{b}/balance", token=users[1])["amount"] == "500.00"
    status, problem = request("POST", "/api/v1/transfers", dict(body, amount="2000.00"), users[0], str(uuid.uuid4()))
    assert status == 422 and problem["code"] == "INSUFFICIENT_FUNDS"
    def withdraw(destination):
        return request("POST", "/api/v1/transfers", dict(body, destinationAccountId=destination, amount="800.00"), users[0], str(uuid.uuid4()))
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(withdraw, [b, c]))
    assert sorted(status for status, _ in results) == [201, 422], results
    assert ok("GET", f"/api/v1/accounts/{a}/balance", token=users[0])["amount"] == "200.00"
    report = ok("POST", "/api/v1/admin/reconciliation-runs", {}, admin)
    assert report["status"] == "CLEAN", report
    print(json.dumps({"simulation": True, "transfer": transfer, "sourceStatement": ok("GET", f"/api/v1/accounts/{a}/statement", token=users[0]), "reconciliation": report}, indent=2))
    print("PASS: successful transfer, duplicate replay, insufficient funds, concurrent withdrawals, balances, statement, reconciliation")


if __name__ == "__main__":
    main()
