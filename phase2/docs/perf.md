# Performance notes

Record exact setup (instance sizes, data volume) next to every number.

## Query tuning (PLAN step 19)

### Setup
- Database `orders_perf`, a copy of the dev `orders` database, loaded with `scripts/seed-orders.sql`.
- **Scale: 300,020 orders and 600,578 order lines, not the 3 million of the plan.** The Windows host had under 1 GB free on
  C: when this was run, so a 3 million row load (about 2 GB with indexes and WAL) could have filled the disk and taken
  Docker down. The script takes `-v rows=N` and defaults to 3,000,000. Every number below is for 10% of the planned size, so
  the absolute times are small and the gaps are smaller than they would be at 3 million.
- PostgreSQL 16.15 in the compose `postgres:16` container, `shared_buffers` 128MB, `work_mem` 4MB, default settings otherwise.
- Customers: 20,000 `cust-N` values plus `alice` and `bob` (3,019 orders for `alice`). Status mix: 90.1% CONFIRMED, 5.0% REJECTED,
  3.0% CANCELLED, 2.0% PENDING. `ANALYZE` was run after the load.
- Existing index at this point: `idx_orders_customer_status ON orders (customer_id, status)`.

### 19b: candidate queries, before (three runs each, warm cache, `EXPLAIN (ANALYZE, BUFFERS)` on the server)
| Query | Runs (ms) | Used by the app |
|---|---|---|
| `SELECT * FROM orders WHERE customer_id = 'alice' ORDER BY created_at DESC LIMIT 50` | 2.66, 2.66, 2.64 | yes: every `GET /api/orders` for a customer (`OrderService.list`) |
| `SELECT * FROM orders WHERE status = 'PENDING' ORDER BY created_at DESC LIMIT 50` | 6.76, 6.46, 6.83 | no: there is no status filter in the API |
| `SELECT * FROM outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 100` | 0.05, 0.03, 0.03 | yes: the outbox poll (partial index, already cheap) |

The status filter is the slowest in absolute time, but the application never runs it, so it is not the one to tune. The query
the application runs on every list is the customer query; it is the one worked on in 19c.

Plan of the customer query before (2.6 ms; reads 1,979 buffers to return 50 rows):
```
Limit  (actual time=2.587..2.590 rows=50)  Buffers: shared hit=1979
  ->  Sort  Sort Key: created_at DESC  Sort Method: top-N heapsort  Memory: 31kB
        ->  Bitmap Heap Scan on orders  (actual rows=3019)  Recheck Cond: (customer_id = 'alice')
              Heap Blocks: exact=1969  Buffers: shared hit=1976
              ->  Bitmap Index Scan on idx_orders_customer_status  Index Cond: (customer_id = 'alice')  Buffers: shared hit=7
Execution Time: 2.623 ms
```
The index finds `alice`'s 3,019 rows, but not in `created_at` order, so Postgres visits 1,969 heap pages and sorts all 3,019 rows to
keep 50. The cost grows with the customer's history, not with the page size.

## Load test (PLAN step 25)

**Tool: `scripts/load_orders.py`, NOT k6.** k6 could not be installed on this machine (winget hung, a binary download was blocked), so the
numbers below come from a stand-in written for this purpose (standard library Python, same behaviour as `phase2/load-tests/k6/create-orders.js`:
one Keycloak token per user, a fixed arrival rate that does not wait for responses, SKU-1/SKU-2 mix). They are not k6 numbers and should not
be quoted as such. The k6 script is written but has never been run.

### Setup
- One Windows 10 machine (Intel Core i7-14700) running everything at once: the load generator, both services (`mvn spring-boot:run`, JVM defaults),
  PostgreSQL 16, Kafka, Redis, Keycloak, Prometheus, Grafana, Jaeger (all in Docker Desktop, no CPU or memory limits set). The numbers are conservative
  in that the load generator shares the machine, and optimistic in that nothing crosses a network.
- order-service: Hikari default pool (10), Tomcat default threads (200), outbox poll 500 ms, batch 100 events, one event sent at a time and waited for.
- Command: `scripts/perf_run.sh <dir>` (warm-up 20 s at 20/s, three runs of 60 s at 50/s, then 30 s at 100, 150, 200 and 300/s, waiting for the
  outbox lag to return to 0 between runs).

### Results
| Run | Orders | Failed | Achieved/s | p50 ms | p95 ms | p99 ms | Lag drained after |
|---|---|---|---|---|---|---|---|
| warm-up 20/s | 400 | 0 | 20.0 | 23.1 | 44.7 | 64.9 | |
| steady 50/s, run 1 | 3,000 | 0 | 50.0 | 34.1 | 56.7 | 73.0 | 0 s |
| steady 50/s, run 2 | 3,000 | 0 | 50.0 | 22.0 | 41.5 | 54.1 | 0 s |
| steady 50/s, run 3 | 3,000 | 0 | 50.0 | 19.7 | 32.4 | 35.5 | 0 s |
| ramp 100/s | 3,000 | 0 | 99.9 | 9.5 | 31.6 | 35.0 | 1 s |
| ramp 150/s | 4,500 | 0 | 149.9 | 8.6 | 31.0 | 34.1 | 0 s |
| ramp 200/s | 6,000 | 0 | 199.8 | 8.9 | 31.4 | 34.4 | 9 s |
| ramp 300/s | 9,000 | 0 | 299.8 | 9.3 | 31.8 | 35.5 | 26 s |

- **Highest passing rate: at least 300 orders/s.** The thresholds (failures < 1%, p95 < 500 ms) were never broken, so the real limit was **not found**;
  the single-threaded-schedule Python generator on the same machine is the more likely ceiling, and 300/s is as far as this run went.
- **Outbox lag peak: 26 s**, at the 300/s step; the publisher (one event at a time, 500 ms poll) fell behind the API there and took 26 s to drain
  after the step ended. At 200/s the drain took 9 s, and at 150/s or less the lag stayed near zero. HTTP latency looked good throughout while the pipeline
  fell behind at the top rate, as predicted: the API accepting orders is not the same as events leaving.
- **Error rate: 0 failed of 32,200 requests.** After the last drain: 32,156 orders CONFIRMED, 4 REJECTED (from an earlier test), 0 PENDING.
- These are warm-cache numbers from one machine and a short run each (60 s and 30 s, not the planned 3 minutes). Treat them as a first measurement.
