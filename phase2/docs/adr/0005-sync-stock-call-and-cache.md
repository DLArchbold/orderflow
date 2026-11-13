# ADR 0005: A synchronous stock call that orders do not depend on, and how its cache goes stale

Status: accepted

## Context
The UI shows how many units of a SKU are in stock next to the order form. That needs one synchronous call from
order-service to inventory-service, which is also what gives Resilience4j and Redis something real to protect.

## Decisions
1. **The call is not part of order creation.** Orders are accepted when inventory-service is down. Correctness comes
   from the asynchronous path (outbox, `orders.events`, idempotent consumer, `inventory.results`); this call is only
   a convenience for the UI.
2. **Timeouts first** (connect 500 ms, read 1 s), then a circuit breaker and a bulkhead (`inventory`) on
   `StockClient.get`. The fallback is `available = null` ("unknown"), never 0, because 0 would tell the user the
   item is sold out when we simply do not know. A 404 for an unknown SKU is ignored by the breaker.
3. **Redis cache in front of the breaker** (`@Cacheable` on `StockService.get`, TTL 30 s). The fallback is never
   cached (`unless = "#result.available == null"`), so a 10 s outage does not become 30 s of "unknown".
   Values are `Serializable` records (JDK serialization, the Spring default), chosen over a JSON serializer for
   having no extra configuration; the cost is that old cache entries are unreadable after the record changes,
   which the 30 s TTL makes harmless.
4. **Staleness:** only the 30 s TTL. A reservation changes the number up to 30 s before the UI shows it. Evicting the
   SKUs of an order when `InventoryResultHandler` applies its result would shorten that, and is left out on
   purpose: it adds coupling for a number that is only informative.
5. **A cache is an optimisation, never a requirement.** A `CacheErrorHandler` logs and carries on, Redis timeouts are
   500 ms, and the Redis health indicator is switched off so a Redis outage does not make the service unready.

## Alternatives considered
**Move the stock check into order creation: rejected.** A check before the order is a time-of-check / time-of-use race
(two orders can both see 1 unit), so it could only ever be advisory; the guarantee comes from the reservation in
inventory-service. It would also make order creation depend on inventory-service at run time, turning an inventory outage
into an order-service outage (or, with an "accept anyway" fallback, a check that decides nothing), and it would cut against
the decoupled, outbox-based design the drills prove. A non-blocking "low stock" hint on `POST /orders` from the cached value
would be harmless but is not worth the change.

## Consequences
- With Redis down every read pays about 1 s (a failed get plus a failed put at 500 ms each) but still answers.
  With nothing listening on the host the connection is refused immediately; the full 500 ms timeouts appear when the host drops packets.
- Only entries inside the 30 s TTL are served from the cache. There is no stale-while-error: once an entry expires during an
  inventory outage the answer is "unknown" (`available = null`), which is not cached.
- Because the lookup is outside the order path, load tests of order creation say nothing about the cache. Measuring its benefit
  needs a k6 scenario on `GET /api/stock/{sku}` with a skewed SKU mix, run with the cache switched off and on (not run yet).
