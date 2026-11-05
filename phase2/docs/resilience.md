# Resilience drills (PLAN step 26)

## Definitions (written before the drills, so the numbers can be checked)
- **Lost** = an order that has no row in `orders` although the client got a `201` for it, **or** an order still `PENDING` after the
  pipeline has drained (outbox lag back to 0) with nothing in the dead-letter topic. Also counted: an outbox row still unpublished after the drain,
  in either service.
- **Duplicated** = more stock reserved than the CONFIRMED orders account for (per SKU, `reserved` against the quantity of CONFIRMED order
  lines), **or** two rows for one request (an order with more than one `OrderCreated` outbox row, or an order with more than one result row in
  inventory's outbox). Stock must also never go negative, and `available + reserved` must still equal the initial 1,000,000 per SKU.
- **Not counted as duplicated: duplicate records on the Kafka topic.** The publisher is at-least-once, so a long outage produces several
  copies of one event (3 in the manual run of step 13f). The claim worth making is that those copies cause no duplicated *effect*: the
  `processed_messages` table in inventory-service absorbs them.

## How the checks run
`scripts/drill-check.sql` holds the queries, one set per database; `python scripts/drill_check.py --created <orders the client got 201 for>`
runs them against the compose Postgres and prints PASS or FAIL for each definition above. It must pass on a clean system before any drill.

## What "acknowledged" means (request path)
1. The browser sends `POST /orders` with its JWT to order-service (port 8080). 2. Filters validate the token and set the correlation id.
3. `OrderService.create` runs one transaction in the orders database: `orders` + `order_lines` + an `outbox` row (`OrderCreated`). 4. The transaction commits.
5. Order-service writes the HTTP `201` (status `PENDING`) back to the browser. That `201` is the acknowledgement; Kafka and inventory-service are not involved yet.
After that, asynchronously: `OutboxPublisher` sends the row to `orders.events` and marks it published after Kafka's ack; inventory-service reserves stock and writes its own
outbox row; its publisher sends the result to `inventory.results`; order-service's listener sets `CONFIRMED` or `REJECTED` and pushes it to the browser over SSE.
The one gap is between step 4 and step 5: if the process dies there, the order exists but the client never saw the `201`. In drill 3 this did not happen (the order count
matched the `201`s exactly), but nothing in the design rules it out.

## Drills
Setup for all three: the local stack (order-service and inventory-service started with `mvn spring-boot:run`), `scripts/load_orders.py` (a stand-in for k6, which
could not be installed) at a steady **20 orders/s for 180 s**, the disruption starting 30 s into the load, then waiting until no order is PENDING and running
`scripts/drill_check.py`. Driver: `scripts/drill.sh kafka|inventory|order <dir>`. Raw timelines and per-2-second samples of the outbox lag and the PENDING count
were kept in the run directories (not committed; the numbers are quoted here).

| | Drill 1: Kafka stopped 60 s | Drill 2: inventory-service killed 60 s | Drill 3: order-service killed ~20 s |
|---|---|---|---|
| Disruption | `docker compose stop kafka` at +30 s, `start` 62 s later | process killed at +30 s, started again 60 s later | `taskkill /F` at +30 s, started 20 s later |
| Orders sent / answered `201` | 3,600 / 3,600 | 3,600 / 3,600 | 3,600 / 3,079 |
| API errors | **0** (kept returning 201) | **0** | 521 connection errors (the API itself was down for about 26 s: 20 s plus start-up; mostly refused connections, a few in flight); never acknowledged, so not "lost", but a customer would have seen an error and had to resubmit |
| Latency p95 of the 201s | 45 ms | 34 ms | 4,100 ms (requests that started just before the kill waited on a dead connection) |
| Outbox lag peak | 64 s | 0.5 s | 0.5 s |
| PENDING orders peak | 1,296 | 1,468 | 369 |
| Time from the end of the load to no PENDING | 3 s | 2 s | 2 s |
| Orders lost | **0** | **0** | **0** |
| Reservations duplicated | **0** | **0** | **0** |
| Checks | all pass | all pass | all pass |

What was seen
- **Drill 1 (broker).** The API kept returning 201 for the whole minute: the order and its outbox row commit together, so a dead broker does not touch the request path.
  Orders piled up PENDING and the outbox lag climbed to 64 s, then the publisher caught up within seconds of the broker returning.
  **The lag alert did not fire.** `OutboxLagHigh` needs the lag above 60 s *for 2 minutes*; a 60 s outage only just crossed 60 s once. The alert fired in the
  step 21b test, where the publisher was switched off for five minutes; to see it during a broker outage the outage has to last longer than about three minutes.
- **Drill 2 (consumer).** Orders stayed PENDING while inventory-service was down (the events wait in `orders.events`), the outbox lag stayed near zero (order-service
  was healthy), and every order reached CONFIRMED after the restart. After a kill the restarted consumer waits for the old group member's session to expire, which
  delayed the catch-up by up to 45 s. No stock was reserved twice (`reserved` equals the CONFIRMED quantity, per SKU).
- **Drill 3 (order-service killed, the whole process).** `taskkill /F /PID <pid> /T` killed the JVM on port 8080, so the controller, `OrderService`, `OutboxPublisher`, the
  `inventory.results` listener and the SSE stream were all down together; not only the publisher. Inventory-service, Kafka, Postgres and Redis kept running.
  Nothing acknowledged (see What "acknowledged" means (request path) above) was lost. About 26 s of downtime at 20 requests/s explains the 521 failed requests: most found nothing listening on port 8080 (connection refused)
  and only a few were in flight when the process died. None of them got a `201`, so none counts as an accepted order. The PENDING peak (369) is the results waiting
  because nothing was applying them while order-service was down. After the restart the publisher sent the rows already committed, the listener applied the waiting
  results, and both services' outbox tables ended with 0 unpublished rows.
  Not run: a drill that stops only the publisher (orders would keep returning 201 and stay PENDING while the outbox lag grew, like drill 1).
- **Duplicate records on the topic were not counted** (see the definitions); what was checked is that they caused no duplicated effect: the per-SKU reservations equal
  the CONFIRMED quantities exactly and `processed_messages` equals the number of orders.

Limits of the "0 lost" claim: it covers orders the client got a `201` for. Requests that failed during the order-service outage are not counted, and the API has
no `Idempotency-Key`, so a client that retries after a lost response could create a second order. Order-service also runs one replica (two would publish the same outbox
rows), so a crash is an outage of the create API until it restarts.

Not captured: a Grafana screenshot for each drill (the image renderer plugin is not installed); the lag and PENDING samples above stand in for it. The optional
Kafka-outage-long-enough-to-fire-the-alert drill was not run.
