#!/usr/bin/env python3
"""Arrival-rate load generator for POST /api/orders (standard library only).

A STAND-IN for phase2/load-tests/k6/create-orders.js, used where k6 could not be installed. It does the same thing:
fetch one token per user from Keycloak, then start a fixed number of orders per second (new requests start on schedule
whether or not earlier ones have finished), mixing SKU-1 and SKU-2. Numbers from this tool are NOT k6 numbers and must
be labelled as such.

  python scripts/load_orders.py --rate 20 --duration 180 --label "kafka stopped 60s"
"""
import argparse, json, statistics, sys, threading, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor


def token(keycloak, user):
    data = urllib.parse.urlencode({"client_id": "orderflow-cli", "grant_type": "password",
                                   "username": user, "password": f"{user}-dev-pw"}).encode()
    with urllib.request.urlopen(f"{keycloak}/realms/orderflow/protocol/openid-connect/token", data, timeout=10) as r:
        return json.load(r)["access_token"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rate", type=float, default=20, help="orders per second")
    ap.add_argument("--duration", type=float, default=30, help="seconds")
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--keycloak-url", default="http://localhost:8180")
    ap.add_argument("--users", default="alice,bob")
    ap.add_argument("--workers", type=int, default=300)
    ap.add_argument("--label", default="")
    args = ap.parse_args()

    tokens = [token(args.keycloak_url, u) for u in args.users.split(",")]
    results = []          # (start offset s, latency s, status)
    lock = threading.Lock()
    t0 = time.perf_counter()

    def one(i):
        sku = "SKU-1" if i % 2 == 0 else "SKU-2"
        body = json.dumps({"items": [{"sku": sku, "quantity": 1}]}).encode()
        req = urllib.request.Request(f"{args.base_url}/api/orders", body, method="POST", headers={
            "Content-Type": "application/json", "Authorization": f"Bearer {tokens[i % len(tokens)]}"})
        start = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                r.read()
                status = r.status
        except urllib.error.HTTPError as e:
            status = e.code
        except Exception:
            status = 0
        with lock:
            results.append((start - t0, time.perf_counter() - start, status))

    total = int(args.rate * args.duration)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for i in range(total):
            delay = t0 + i / args.rate - time.perf_counter()
            if delay > 0:
                time.sleep(delay)      # the arrival schedule does not wait for responses
            pool.submit(one, i)
    elapsed = time.perf_counter() - t0

    lat = sorted(r[1] for r in results)
    ok = sum(1 for r in results if r[2] == 201)

    def pct(p):
        return round(lat[min(len(lat) - 1, int(p * len(lat)))] * 1000, 1) if lat else None

    summary = {"label": args.label, "tool": "scripts/load_orders.py (not k6)", "target_rate": args.rate,
               "duration_s": round(elapsed, 1), "sent": total, "completed": len(results), "created_201": ok,
               "failed": len(results) - ok, "failed_pct": round(100 * (len(results) - ok) / max(1, len(results)), 2),
               "achieved_rate": round(len(results) / elapsed, 1),
               "p50_ms": pct(0.50), "p95_ms": pct(0.95), "p99_ms": pct(0.99),
               "max_ms": round(lat[-1] * 1000, 1) if lat else None,
               "statuses": {str(s): sum(1 for r in results if r[2] == s) for s in sorted({r[2] for r in results})}}
    print(json.dumps(summary, indent=2))
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
