# ADR 0001: Transactional outbox for publishing order events

Status: accepted

## Context
Order creation must update PostgreSQL and notify the inventory service via Kafka. Writing to both
directly can leave them inconsistent (DB commit succeeds, publish fails, or the reverse).

## Decision
Write the order and an `outbox` row in the same database transaction. A publisher polls unpublished rows,
sends them to Kafka and marks them published.

## Consequences
- Delivery is at-least-once, so consumers must be idempotent (see `processed_messages` in inventory-service).
- Adds publish latency (poll interval) and a polling load on the DB (partial index keeps it cheap).
- Alternative considered: CDC (e.g. Debezium). More moving parts than this project needs.
