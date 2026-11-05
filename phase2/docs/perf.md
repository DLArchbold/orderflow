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

### 19c: the change
- `V4__orders_customer_created_index.sql`: `CREATE INDEX idx_orders_customer_created ON orders (customer_id, created_at DESC);`
- `V5__drop_unused_customer_status_index.sql`: `DROP INDEX idx_orders_customer_status;`. The old `(customer_id, status)` index is only
  chosen for a filter on both columns (checked with `EXPLAIN`), and no query in the application filters that way
  (`OrderRepository.findByCustomerId(customerId, pageable)` and `findAll(pageable)` are the only list queries), so nothing uses it.
- Both migrations were run by Flyway against `orders_perf` and against the dev `orders` database (`select * from flyway_schema_history`
  shows versions 1 to 5 in both), and on every clean Testcontainers database in `mvn test`.

### 19d: result (customer query: `... WHERE customer_id = 'alice' ORDER BY created_at DESC LIMIT 50`, 3,019 rows for alice)
| | Plan | Buffers | Runs (ms) | Median of 5 |
|---|---|---|---|---|
| Before (`idx_orders_customer_status`) | Bitmap Index Scan, Bitmap Heap Scan (3,019 rows), top-N Sort | 1,974 | 5.83, 1.68, 1.19, 1.10, 1.03 | **1.19 ms** |
| After (`idx_orders_customer_created`) | Index Scan, 50 rows, no Sort node | 53 | 0.194, 0.111, 0.119, 0.154, 0.122 | **0.122 ms** |

About 10x faster and 37x fewer buffers read. The very first "before" measurement (19b, three runs) read 2.64 to 2.66 ms on the same
plan; "before" here was re-measured in a rolled-back transaction that re-creates the old index, so five runs of one state could be taken.
Warm-cache millisecond timings on a laptop are noisy (the first run of a group is 3 to 5 times slower); the buffer count is the stable
number and it does not depend on the machine.

Plan after:
```
Limit  (actual time=0.023..0.147 rows=50)  Buffers: shared hit=53
  ->  Index Scan using idx_orders_customer_created on orders  Index Cond: (customer_id = 'alice')
```

Write cost, measured honestly: inserting 100,000 orders in one transaction (rolled back) took 499 ms with only the primary key, 816 to
831 ms with the new index added next to the old one, and 478 ms with the final index set (primary key + new index; the old index
is dropped). So the new index costs little once the old one is gone. The earlier 64% overhead was the cost of carrying both.
`idx_orders_customer_created` is 12 MB at creation (the whole `orders` table with its indexes was 40 MB before V4).

### Reproduce
1. `docker compose -f phase2/infra/docker-compose.full.yml exec -T postgres psql -U orderflow -d postgres -c "CREATE DATABASE orders_perf TEMPLATE orders;"` (order-service stopped)
2. `... psql -U orderflow -d orders_perf -v rows=300000 < scripts/seed-orders.sql`
3. Run the query with `EXPLAIN (ANALYZE, BUFFERS)` five times, apply V4 and V5 (start order-service with `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/orders_perf SERVER_PORT=8082`), run `ANALYZE orders;`, and repeat.

Setup: PostgreSQL 16.15 (docker `postgres:16`), `shared_buffers` 128MB, `work_mem` 4MB, warm cache (queries repeated), Windows 10 host running Docker Desktop with the load generator
and everything else on the same machine, 300,020 orders / 600,578 lines (10% of the planned 3 million, see Setup above).
The resume bullet therefore reads: "customer order list query from 1.2 ms to 0.12 ms (1,974 to 53 buffers) at 300,000 orders"; re-run
`scripts/seed-orders.sql` with the default 3,000,000 rows on a machine with disk to fill in the 3 million figure.
