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

## Load test (PLAN step 18)
Throughput, p95 latency, error rate.
