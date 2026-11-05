#!/usr/bin/env python3
"""Runs scripts/drill-check.sql against both databases in the compose Postgres and applies the definitions of
"lost" and "duplicated" from phase2/docs/resilience.md.

  python scripts/drill_check.py --created 3600        # --created = orders the load generator got a 201 for

Exit code 0 only if every check passes. Needs the compose stack (docker compose ... exec postgres psql).
"""
import argparse, re, subprocess, sys
from pathlib import Path

COMPOSE = ["docker", "compose", "-f", "phase2/infra/docker-compose.full.yml", "exec", "-T", "postgres"]
INITIAL_STOCK = 1_000_000


def parse(path):
    blocks, db, name, buf = [], None, None, []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if m := re.match(r"-- db: (\w+)", line):
            db = m.group(1)
        elif m := re.match(r"-- name: (\w+)", line):
            name, buf = m.group(1), []
        elif name and not line.startswith("--"):
            buf.append(line)
            if line.rstrip().endswith(";"):
                blocks.append((db, name, "\n".join(buf)))
                name = None
    return blocks


def run(db, sql):
    out = subprocess.run(COMPOSE + ["psql", "-U", "orderflow", "-d", db, "-At", "-F", "|", "-c", sql],
                         capture_output=True, text=True, check=True, env={**__import__("os").environ, "MSYS_NO_PATHCONV": "1"})
    rows = [r.split("|") for r in out.stdout.strip().splitlines() if r]
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--created", type=int, required=True, help="orders the load generator got a 201 for")
    ap.add_argument("--baseline", type=int, default=0, help="orders already in the database before the load started")
    args = ap.parse_args()

    r = {name: run(db, sql) for db, name, sql in parse(Path(__file__).with_name("drill-check.sql"))}
    one = lambda n: int(r[n][0][0])
    confirmed = {sku: int(q) for sku, q in r["confirmed_quantity_by_sku"]}
    reserved = {sku: int(q) for sku, q in r["reserved_quantity_by_sku"]}
    totals = {sku: int(q) for sku, q in r["stock_available_plus_reserved"]}

    checks = [
        (f"lost: baseline {args.baseline} + orders created (201) vs rows in orders", args.baseline + args.created, one("orders_total"), lambda a, b: a == b),
        ("lost: orders still PENDING after the drain (expected 0)", 0, one("orders_still_pending"), lambda a, b: a == b),
        ("lost: unpublished outbox rows, order-service (expected 0)", 0, one("orders_outbox_unpublished"), lambda a, b: a == b),
        ("lost: unpublished outbox rows, inventory-service (expected 0)", 0, one("inventory_outbox_unpublished"), lambda a, b: a == b),
        ("duplicated: orders with more than one OrderCreated event (expected 0)", 0, one("orders_with_more_than_one_created_event"), lambda a, b: a == b),
        ("duplicated: orders with more than one result event (expected 0)", 0, one("orders_with_more_than_one_result"), lambda a, b: a == b),
        ("stock never negative (expected 0 rows)", 0, one("stock_negative"), lambda a, b: a == b),
    ]
    for sku in sorted(totals):
        checks.append((f"{sku}: available + reserved still the initial stock", INITIAL_STOCK, totals[sku], lambda a, b: a == b))
        checks.append((f"duplicated: {sku} reserved vs quantity of CONFIRMED orders", confirmed.get(sku, 0), reserved[sku], lambda a, b: a == b))

    failed = 0
    print(f"orders by status: {dict((s, int(c)) for s, c in r['orders_by_status'])}; processed_messages: {one('processed_messages')}")
    for text, expected, actual, ok in checks:
        good = ok(expected, actual)
        failed += not good
        print(f"{'PASS' if good else 'FAIL'}  {text}: expected {expected}, got {actual}")
    print("ALL CHECKS PASS" if not failed else f"{failed} CHECK(S) FAILED")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
