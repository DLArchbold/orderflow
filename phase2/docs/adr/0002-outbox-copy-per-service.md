# ADR 0002: Copy the outbox classes into inventory-service instead of sharing a library

Status: accepted

## Context
inventory-service must write its result events through an outbox too (a stock change and the "StockReserved"
event have to commit together, and Kafka is not part of the database transaction). The classes needed are
`OutboxEvent`, `OutboxRepository` and `OutboxPublisher`, a few dozen lines in total.

## Decision
Copy the three classes into `com.orderflow.inventory.outbox` (and add the same `outbox` table in `V2__outbox.sql`).
Do not create a shared library module.

## Consequences
- The two services release independently; a change to one publisher cannot break the other's build.
- A bug fix has to be applied twice. Acceptable for two small classes; revisit if a third service needs an outbox
  or the publisher grows (batching, `FOR UPDATE SKIP LOCKED`, tracing), at which point a shared module pays off.
- The publisher is switchable with `orderflow.outbox.enabled` so tests that do not use Kafka create no bean.
