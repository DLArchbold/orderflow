# Architecture

```mermaid
flowchart LR
  UI[React + TS frontend] -- REST + SSE --> OS[order-service]
  OS -- same TX --> ODB[(orders DB<br/>orders + outbox)]
  OS -- OutboxPublisher --> K{{Kafka<br/>orders.events}}
  K --> IS[inventory-service]
  IS --> IDB[(inventory DB<br/>stock + processed_messages)]
  IS -- inventory.results --> K
  K --> OS
  K -. retries exhausted .-> DLQ{{orders.events.DLT}}
```

Order lifecycle: `PENDING -> CONFIRMED | REJECTED | CANCELLED`, `CONFIRMED -> CANCELLED`.

## Event contracts

- `orders.events`: key = order id, header `message-id` = the order-service outbox row id, value = the `OrderResponse`
  JSON (`id`, `customerId`, `status`, `items[{sku, quantity}]`).
- `inventory.results`: key = order id, header `message-id` = inventory's outbox row id, value =
  `{"orderId":"...","result":"RESERVED"|"REJECTED","reason":"..."}` (`reason` is null when reserved).
- `orders.events.DLT`: records that failed processing after the retries (step 16).

Delivery is at-least-once. The `message-id` is the outbox row id, which stays the same across every re-send of a
row (the Kafka offset does not), so consumers dedupe on it.

Fill in as built: failure modes and what happens in each.
