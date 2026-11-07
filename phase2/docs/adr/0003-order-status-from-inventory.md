# ADR 0003: Order status comes from the inventory result, not from a manual call

Status: accepted

## Context
Until step 15 an order moved to CONFIRMED through `POST /api/orders/{id}/confirm`, a stand-in for the inventory
check. Inventory-service now decides, and publishes `RESERVED` or `REJECTED` on `inventory.results`.
Cancelling also races with that result.

## Decisions
1. **Remove `POST /api/orders/{id}/confirm`** (and the Confirm button). Only the `inventory.results` consumer
   moves PENDING to CONFIRMED or REJECTED. An admin override would need the security rules from step 18 first.
2. **A CONFIRMED order cannot be cancelled.** `CANCELLED` is taken out of `CONFIRMED`'s `allowedNext()`.
   The alternative, a release event that gives the stock back, needs a new topic and a handler in inventory-service.
3. **A result for an order that is no longer PENDING is ignored and logged** (a warning for CANCELLED, since stock
   was reserved for it). Results for unknown order ids are ignored too: throwing would block the partition on a
   record that can never succeed.

## What the state machine does in each ordering
- Result first, then cancel: RESERVED confirms the order, the cancel then gets 409 (decision 2). REJECTED rejects
  it, the cancel gets 409 (REJECTED is terminal).
- Cancel first, then result: the order is CANCELLED, the late result is ignored (decision 3).

## Consequences
- Known limit: when a RESERVED result arrives for an order that was already CANCELLED, its stock stays reserved
  (no release flow yet).
- The result handler is idempotent without its own `processed_messages` table: "apply only if PENDING" gives the
  same final state when repeated. Inventory needs the table because its repeated action is a stock decrement.
