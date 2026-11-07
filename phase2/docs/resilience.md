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
  step 21b test, where the publisher was switched off for five minutes; to see it during a broker outage the outage has to last longer than about three minutes (drill 4 below did that).
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
Three different things are easy to confuse in the load-test output (details in [perf.md](perf.md), "Reading the k6 numbers"): a *failed request* (sent, no 2xx), a *dropped iteration* (k6 never sent it, because no virtual user was free; not in `http_req_failed`) and a *lost order* (defined above). The drills above count the first and third; dropped iterations only reduce the load that was applied.

Grafana screenshots of the OrderFlow dashboard for each run are in `docs/images/` (taken afterwards with `scripts/grafana_screenshots.mjs` and Playwright, because the Grafana image-renderer plugin is not installed; each window has 60 s of context on both sides). The dashboard has no PENDING-orders panel, so the PENDING counts still come from the drill samples.

| Run | Screenshot |
|---|---|
| Kafka stopped 60 s (container run) | [grafana-drill-kafka-stopped-60s.png](../../docs/images/grafana-drill-kafka-stopped-60s.png) |
| inventory-service killed 60 s | [grafana-drill-inventory-killed-60s.png](../../docs/images/grafana-drill-inventory-killed-60s.png) |
| order-service killed | [grafana-drill-order-killed.png](../../docs/images/grafana-drill-order-killed.png) |
| Kafka stopped 244 s (drill 4) | [grafana-drill-kafka-stopped-244s.png](../../docs/images/grafana-drill-kafka-stopped-244s.png), alert state: [prometheus-alert-OutboxLagHigh.png](../../docs/images/prometheus-alert-OutboxLagHigh.png) (the `pending` series, then `firing`) |
| Perf ramp to 1,000 orders/s | [grafana-perf-ramp-to-1000.png](../../docs/images/grafana-perf-ramp-to-1000.png) |

Grafana shows local time (UTC-5) and the Prometheus graph shows UTC, so the clocks differ by five hours on the same event.

### Drills 1 to 3 repeated with the services as containers (2026-11-05)
Same method and rate (20 orders/s for 180 s, disruption 30 s in) but order-service, inventory-service and the frontend ran as containers (`--profile apps`); the
services were killed with `docker compose kill` and restarted with `docker compose start` (`scripts/drill.sh` detects this). The database already held about 119,000 orders
from the perf run, so the checks ran against a larger table.

| | Kafka stopped 60 s | inventory-service killed 60 s | order-service killed, started 21 s later |
|---|---|---|---|
| Orders sent / answered `201` | 3,600 / 3,600 | 3,600 / 3,600 | 3,600 / 3,000 |
| API errors | 0 | 0 | 600 (the API was down; never acknowledged, so not "lost") |
| Latency p95 / p99 of the 201s | 31.0 / 37.6 ms | 31.2 / 37.9 ms | 4,090 / 7,690 ms |
| Outbox lag peak | 70 s | 0.5 s | 0.5 s |
| PENDING peak | 1,418 | 1,377 | 279 |
| Time from end of load to no PENDING | 2 s | 2 s | 2 s |
| Orders lost / reservations duplicated | **0 / 0** | **0 / 0** | **0 / 0** |
| Checks | ALL CHECKS PASS | ALL CHECKS PASS | ALL CHECKS PASS |

The results match the first runs (one container run, one run per drill). The order-service drill lost 600 requests instead of 521 because the container needed longer to start than the
local process did; the restart time was not measured separately. As before, the "0 lost" claim covers orders that got a `201`.

### Drill 4: Kafka stopped 4 minutes, to see `OutboxLagHigh` fire (2026-11-05)
Setup: both services and the frontend as containers (`--profile apps`), `scripts/load_orders.py` at **10 orders/s for 360 s**, Kafka stopped 30 s in for **244 s**,
then started. Driver: `python scripts/drill_lag_alert.py <dir>`. Prometheus scrapes every 15 s with the default settings.

| Seconds after Kafka stopped | What Prometheus showed |
|---|---|
| 0 | stop; the API kept returning 201 |
| 63 | the outbox lag crossed 60 s |
| 88 | `OutboxLagHigh` **Pending** (about 25 s after the lag crossed 60 s: the scrape and the rule evaluation each run every 15 s) |
| 208 | **Firing**, 2 minutes after Pending, as the rule's `for: 2m` says |
| 243 | peak outbox lag **242 s** (about the length of the outage) |
| 244 | Kafka started |
| about 263 | lag back to 0; alert cleared |
| 360 (about) | load ended; 3 s later no order was PENDING |

Checks (`scripts/drill_check.py`): 3,600 orders created (201), 3,600 rows, 3,600 CONFIRMED, 0 PENDING, 0 unpublished outbox rows in either service, 0 orders with
a duplicated event, 1,800 reserved per SKU matching the CONFIRMED quantities. **ALL CHECKS PASS.** This closes the gap noted under drill 1.

Caveat on the evidence: the sampler in the first version of `drill_lag_alert.py` had a bug (it read the `ALERTS` labels from the wrong key), so `samples.txt` shows
the alert state as an error for the 36 samples while the alert was active. The Pending/Firing times and the lag peak above come from querying Prometheus afterwards
(`query_range` on `ALERTS{alertname="OutboxLagHigh"}` and `orderflow_outbox_lag_seconds`, step 5 s), not from the script's samples. (The script's own samples stopped recording
the lag when the error began, so their maximum of 132 s was wrong and is not the peak.) The bug is fixed in the script but the drill was not re-run with the fix.
The raw run directory was not committed. The alert was seen in Prometheus only; no notification receiver (Alertmanager) is configured.
