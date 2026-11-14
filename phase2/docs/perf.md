# Performance notes

Record exact setup (instance sizes, data volume) next to every number.

## Query tuning (PLAN step 19)

### Setup
- Database `orders_perf`: the `orders` schema copied with `pg_dump --schema-only` (order-service was running, so `CREATE DATABASE ... TEMPLATE` was not possible),
  with the V1 index `idx_orders_customer_status ON orders (customer_id, status)` put back in place of V4/V5 to get a true "before". Loaded with `scripts/seed-orders.sql`.
- **Scale: 3,000,000 orders and 5,998,364 order lines** (load took 69 s; 395 MB orders, 821 MB order_lines with indexes). An earlier run at 300,020 orders
  (disk was nearly full then) gave 2.6 ms before and 1.19 ms after for the customer query; the 3M numbers below replace it.
- PostgreSQL 16.15 in the compose `postgres:16` container, `shared_buffers` 128MB, `work_mem` 4MB, default settings otherwise.
- Customers: 20,000 `cust-N` values plus `alice` and `bob` (30,031 orders for `alice`). Status mix: 90.0% CONFIRMED, 5.0% REJECTED,
  3.0% CANCELLED, 2.0% PENDING. `ANALYZE` was run after the load and after each index change.

### 19b: candidate queries, before (three runs each, `EXPLAIN (ANALYZE, BUFFERS)` on the server)
| Query | Runs (ms) | Used by the app |
|---|---|---|
| `SELECT * FROM orders WHERE customer_id = 'alice' ORDER BY created_at DESC LIMIT 50` | 56.2, 71.8, 60.9 | yes: every `GET /api/orders` for a customer (`OrderService.list`) |
| `SELECT * FROM orders WHERE status = 'PENDING' ORDER BY created_at DESC LIMIT 50` | 50.5, 50.2, 53.4 | no: there is no status filter in the API |
| `SELECT * FROM outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 100` | 0.037, 0.034, 0.036 | yes: the outbox poll (partial index, already cheap; outbox is small in this copy) |

The two orders queries cost about the same; the application never runs the status one, so the customer query is the one worked on in 19c.

Plan of the customer query before (45 ms on the run shown; reads 19,252 buffers to return 50 rows, 13,767 of them from disk on that first run):
```
Limit  (actual time=44.947..44.982 rows=50)  Buffers: shared hit=5485 read=13767
  ->  Sort  Sort Key: created_at DESC  Sort Method: top-N heapsort  Memory: 30kB
        ->  Bitmap Heap Scan on orders  (actual rows=30031)  Recheck Cond: (customer_id = 'alice')
              Heap Blocks: exact=19221
              ->  Bitmap Index Scan on idx_orders_customer_status  Index Cond: (customer_id = 'alice')  Buffers: shared read=28
```
The index finds `alice`'s 30,031 rows, but not in `created_at` order, so Postgres visits 19,221 heap pages and sorts all of them to keep 50.
The cost grows with the customer's history, not with the page size.

### 19c: after adding `idx_orders_customer_created ON orders (customer_id, created_at DESC)` (V4)
| Query | Runs (ms) |
|---|---|
| customer query | 0.211, 0.190, 0.198 (about **300x faster**, 56-72 ms to 0.2 ms) |
| status query | 53.2, 54.9, 56.7 (unchanged, as expected) |

```
Limit  (actual time=0.030..0.165 rows=50)  Buffers: shared hit=53
  ->  Index Scan using idx_orders_customer_created on orders  Index Cond: (customer_id = 'alice')  Buffers: shared hit=53
```
19,252 buffers become 53. The plan reads exactly the 50 rows needed and does no sort. Cost: the new index is 116 MB at 3M rows (the primary key is 122 MB,
the old status index 31 MB), so every insert maintains one more B-tree. The old index served no query the application runs and was dropped by V5.

### 19 re-run on 2026-11-05 (same method, a second independent run)
Same setup as above, with both services running as containers (`--profile apps`) and a fresh `orders_perf` made with `pg_dump --schema-only`.
Load: 3,000,000 orders and 6,000,326 order lines in 138 s (`alice` has 29,820 orders). Customer query, three runs each:

| | Runs (ms) | Buffers |
|---|---|---|
| before (`customer_id, status` index) | 54.9, 61.5, 66.5 | about 19,150 page reads, bitmap heap scan plus top-N sort of 29,820 rows |
| after (`customer_id, created_at DESC`) | 0.235, 0.200, 0.246 | 53, index scan, no sort |

The result matches the first run (about 300x). The first run's numbers stay as the ones quoted in the README. The `orders_perf` database was dropped afterwards.

## Load test (PLAN step 25)

**Tool: `scripts/load_orders.py`, NOT k6.** k6 could not be installed on this machine (winget hung, a binary download was blocked), so the
numbers below come from a stand-in written for this purpose (standard library Python, same behaviour as `phase2/load-tests/k6/create-orders.js`:
one Keycloak token per user, a fixed arrival rate that does not wait for responses, SKU-1/SKU-2 mix). They are not k6 numbers and should not
be quoted as such. The k6 script was first run on 2026-11-06: see "k6 runs" at the end of this file. (Update 2026-11-06: the tables above are from `load_orders.py`; the last section is from k6.)

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

- **Highest passing rate: at least 300 orders/s** (this first run only; the container re-run below found the limit, between 400 and 600 orders/s). The thresholds (failures < 1%, p95 < 500 ms) were never broken, so the real limit was **not found**;
  the single-threaded-schedule Python generator on the same machine is the more likely ceiling, and 300/s is as far as this run went.
- **Outbox lag peak: 26 s**, at the 300/s step; the publisher (one event at a time, 500 ms poll) fell behind the API there and took 26 s to drain
  after the step ended. At 200/s the drain took 9 s, and at 150/s or less the lag stayed near zero. HTTP latency looked good throughout while the pipeline
  fell behind at the top rate, as predicted: the API accepting orders is not the same as events leaving.
- **Error rate: 0 failed of 32,200 requests.** After the last drain: 32,156 orders CONFIRMED, 4 REJECTED (from an earlier test), 0 PENDING.
- These are warm-cache numbers from one machine and a short run each (60 s and 30 s, not the planned 3 minutes). Treat them as a first measurement.

### Re-run with the services as containers, and the ramp extended to 1,000/s (2026-11-05)
Same tool (`scripts/load_orders.py`, still **not k6**), same thresholds (failures < 1%, p95 < 500 ms), same machine, but order-service, inventory-service and the
frontend ran as containers (`compose --profile apps`, no CPU or memory limits, 15.5 GB available to Docker), not `mvn spring-boot:run`. Command:
`RAMP="100 150 200 300 400 600 800 1000" scripts/perf_run.sh <dir>`.

| Run | Orders | Failed | Achieved/s | p50 ms | p95 ms | p99 ms | Outbox lag drained after |
|---|---|---|---|---|---|---|---|
| warm-up 20/s | 400 | 0 | 20.0 | 19.3 | 30.7 | 34.2 | |
| steady 50/s, run 1, 2, 3 | 3,000 each | 0 | 50.0 | 17.6, 17.5, 18.4 | 30.1, 29.7, 29.6 | 31.7, 31.8, 31.2 | 0 s |
| ramp 100/s | 3,000 | 0 | 100.0 | 15.9 | 29.4 | 32.0 | 0 s |
| ramp 150/s | 4,500 | 0 | 149.9 | 8.4 | 32.6 | 44.3 | 0 s |
| ramp 200/s | 6,000 | 0 | 200.0 | 18.7 | 42.0 | 53.8 | 5 s |
| ramp 300/s | 9,000 | 0 | 299.8 | 6.9 | 28.1 | 31.4 | 19 s |
| ramp 400/s | 12,000 | 0 | 399.7 | 7.4 | 29.0 | 33.2 | 36 s |
| **ramp 600/s** | 18,000 | **21 (0.12%)** | 599.8 | 31.8 | **1,333.5** | 2,936.3 | 70 s |
| ramp 800/s | 24,000 | 83 (0.35%) | **445.3** (the run took 53.9 s, not 30 s) | 211.1 | 3,128.4 | 8,111.3 | 99 s |
| ramp 1000/s | 30,000 | 0 | **662.5** (45.3 s instead of 30 s) | 29.5 | 1,210.7 | 2,064.8 | 125 s |

- **Limit found: between 400 and 600 orders/s for the create-order API.** 400/s passed cleanly (p95 29 ms). The first threshold broken was p95 at 600/s (1.33 s against 500 ms),
  with 21 requests failing (status 0, a client timeout or connection error). At 800 and 1,000/s the generator could not hold the arrival rate (achieved 445/s and 663/s),
  so those two rows say the system or the generator, or both, was saturated; this run cannot separate them. Because 600/s was held on schedule, the generator was not the limit there.
- **Outbox lag peak: 124 s**, during the 1,000/s step. The lag grows with the rate: 19 s to drain at 300/s, 36 s at 400/s, 125 s at 1,000/s.
- **End-to-end throughput is lower than API throughput.** After the ramp, 21,325 orders were still PENDING; they drained at about **220 orders/s** (4,400 every 20 s), and
  everything reached CONFIRMED about 2 minutes later (98,154 CONFIRMED, 0 PENDING, 0 unpublished outbox rows, SKU-1 and SKU-2 each 51,570 reserved
  which equals the CONFIRMED quantities). So the API accepts about 400/s, but orders are confirmed at about 220/s once a backlog exists, and the part that limits
  that was not isolated (candidates: the publisher sending one event at a time, the inventory consumer, the broker). The earlier "drain" columns above only watch the outbox lag, not this
  second stage.
- Not measured: any CPU or memory limit on the containers (none set), a run longer than 30 s per step, and the same test with k6.

### k6 runs (2026-11-06, step 25b)
**Tool: k6 2.3.0** running `phase2/load-tests/k6/create-orders.js` (constant-arrival-rate, `preAllocatedVUs` 100, `maxVUs` 300, thresholds `http_req_failed < 1%` and `p(95) < 500 ms`), through `scripts/perf_run_k6.sh`
(the same sequence as `perf_run.sh`: 20 s warm-up at 20/s, three 60 s runs at 50/s, then 30 s steps, waiting for the outbox lag to reach 0 between runs).
Command: `RAMP="100 200 300 400 500 600 800" scripts/perf_run_k6.sh phase2/docs/k6-runs/perf-2026-11-06`. Raw k6 summaries, the lag samples and the timeline are in [k6-runs/perf-2026-11-06/](k6-runs/perf-2026-11-06/).
Setup as in the container re-run above: services as containers, no CPU or memory limits, 28 CPUs and 15.5 GB visible to Docker, Hikari pool 10, Tomcat 200 threads, outbox poll 500 ms, batch 100, one event sent at a time. The load generator shares the machine, so the numbers are conservative that way.

| Run | Requests | Failed | Achieved/s | p50 ms | p95 ms | Dropped iterations | Outbox lag drained after | Thresholds |
|---|---|---|---|---|---|---|---|---|
| warm-up 20/s | 402 | 0 | 20.0 | 19.4 | 49.0 | 0 | | pass |
| steady 50/s, run 1 | 3,003 | 0 | 50.0 | 5.2 | 13.0 | 0 | 0 s | pass |
| steady 50/s, run 2 | 3,003 | 0 | 49.9 | 5.3 | 7.6 | 0 | 0 s | pass |
| steady 50/s, run 3 | 3,003 | 0 | 50.0 | 5.2 | 7.2 | 0 | 0 s | pass |
| ramp 100/s | 3,002 | 0 | 99.9 | 5.4 | 36.1 | 0 | 1 s | pass |
| ramp 200/s | 6,003 | 0 | 199.2 | 21.5 | 49.7 | 0 | 4 s | pass |
| **ramp 300/s** | 5,849 | **56 (0.96%)** | **170.8** | 653.9 | **3,230** | **3,154** | 15 s | **p95 failed** |
| ramp 400/s | 12,002 | 0 | 399.3 | 5.0 | 8.0 | 0 | 36 s | pass |
| ramp 500/s | 15,002 | 0 | 499.2 | 5.0 | 8.0 | 0 | 54 s | pass |
| **ramp 600/s** | 16,235 | 3 (0.02%) | **469.8** | 6.0 | **671** | **1,767** | 59 s | **p95 failed** |
| ramp 800/s | 24,003 | 0 | 798.1 | 5.0 | 8.0 | 0 | 104 s | pass |

- **The ramp did not give a clean limit, and I did not isolate why.** The 300/s and 600/s steps stalled (maximum request time about 30 s, all VUs busy, so k6 dropped iterations and the achieved rate fell), while 400, 500 and 800/s
  passed with p95 of 8 ms. A system at its limit would not pass 800/s after failing 300/s, so this looks like an intermittent stall (candidates: a GC pause, Postgres checkpoint or autovacuum after the large inserts, a
  Docker Desktop scheduling pause), not a rate limit. The order-service log shows nothing relevant (one SSE broken-pipe warning). I did not repeat the failing steps or profile them.
- **What can be claimed:** every step up to 200/s passed in this run (and 400/s passed in both tools); 400, 500 and 800/s each passed once; 300/s and 600/s failed once. The highest rate that passed at every attempt is
  **200 orders/s** with k6 (300/s failed here, passed in the Python run). The earlier Python result (limit between 400 and 600/s) is neither confirmed nor refuted, and **800/s with p95 8 ms is a single passing run, not a sustained capacity**.
- **Outbox lag:** peak **100.8 s** (the 800/s step); drain time after each step grows with the rate (0 s at 50/s, 36 s at 400/s, 104 s at 800/s). Lag samples are in `lag.txt`.
- **Error rate:** 59 failed of 91,000-odd requests across the ramp (all in the two stalled steps); 0 in warm-up and the three steady runs.
- **End state:** after the lag reached 0 the table still had about 4,600 PENDING orders (the confirmation stage trails the outbox, as found earlier); a few minutes later: 222,610 CONFIRMED, 0 PENDING.
- Not done: runs longer than 30-60 s per step (the plan says 3 minutes), repeating the stalled steps, any CPU or memory limit.

### Chasing the stall (2026-11-06, repeat of the 300/s and 600/s steps)
`scripts/stall_hunt.sh` ran 300, 600, 300, 600, 300, 600 orders/s for 30 s each (k6, same script and setup), sampling order-service (Hikari pool, JVM) and Postgres (active and waiting sessions, checkpoints) every second.
Raw data: [k6-runs/stall-hunt-2026-11-06/](k6-runs/stall-hunt-2026-11-06/).

| Run | Rate | Achieved/s | Failed | p95 ms | Max request | Hikari active (max) | Hikari pending (max) |
|---|---|---|---|---|---|---|---|
| 1 | 300 | 299 | 0 | 7 | 157 ms | 5 | 0 |
| 2 | 600 | 598 | 0 | 7 | 47 ms | 6 | 0 |
| 3 | 300 | 299 | 0 | 7 | 36 ms | 4 | 0 |
| 4 | 600 | 599 | 0 | 7 | 39 ms | 5 | 0 |
| 5 | 300 | 296 | **31** | 8 | **30.0 s** | 4 | 0 |
| 6 | 600 | 599 | 0 | 8 | 31 ms | 8 | 0 |

- **The failures did not reproduce as a rate limit.** 600/s passed three times out of three (p95 7 to 8 ms, 0 failed), and 300/s passed twice and failed once. Together with the first k6 ramp, 300/s failed 2 of 4 times, 600/s 1 of 4 times, 400, 500 and 800/s 0 of 1 each. The failures follow no rate.
- **What a stalled run looks like (run 5, and the same in the earlier 300/s and 600/s steps):** a small group of requests (31, 56 and 3 in the three failing runs) hangs for about 30 s and then fails with
  `wsarecv: An existing connection was forcibly closed by the remote host` on `localhost:8080`. The stuck VUs stay stuck for the whole step (run 5: 32 VUs busy from start to end), so with 100 VUs preallocated nothing else suffers
  (p95 8 ms, `expected_response:true` max 58 ms); in the first ramp the 300/s step used up all 300 VUs, which is what produced the dropped iterations and the 3.2 s p95.
- **The hung requests never reached the application's order logic.** In run 5 the database holds 8,969 orders created in the window, equal to k6's count of 201 responses (8,969); the 31 failed requests created no order (9,000 requests sent, 8,969 + 31 = 9,000).
  So these are *failed, not lost*: nothing was committed. Hikari never had a waiting request (pending 0 in every second sampled) and active connections stayed at 8 or fewer out of 10, so the pool and the database were not the bottleneck.
- **(Superseded in part by "Stall chase, part 2" below: from a container the app itself saturated, and the reset errors did not reappear.)** Most likely cause (not proven): the Docker Desktop port forward on Windows (`localhost:8080` to the container) hanging a few connections** that are opened in the initial burst, then resetting them. Evidence for it: the app and database show no pressure, the
  stuck requests created no orders, and they all end with a reset from the "remote host". Evidence against or missing: I did not capture a packet trace, and Tomcat's own access log is not enabled, so I cannot show that the request never arrived at Tomcat.
  The sampled Tomcat and GC metrics columns in `samples.txt` are all 0 because I used metric names that do not exist in this build, so GC and Tomcat thread pressure are **not ruled out by this data** (Hikari and Postgres are).
- **What this means for the numbers:** the p95 and failure results of the ramp steps at 300 and 600/s are properties of this machine's Docker networking (or a similar front-end issue), not of order-service. Quote the clean runs (0 failed at 50, 100, 200, 400, 500, 800/s and in 5 of 6 repeats at 300 and 600/s) with that caveat, not "limit between X and Y".
- **To settle it:** run k6 from a container on the compose network (so it does not go through the Windows port forward), or turn on the Tomcat access log and compare. I did not do either.

### Stall chase, part 2: k6 inside a container on the compose network (2026-11-06)
Same script, run with `K6_MODE=docker scripts/stall_hunt.sh <dir>`: k6 (`grafana/k6:2.3.0`) on the `infra_default` network, calling `http://order-service:8080` and fetching tokens from `http://keycloak:8080` (the token issuer is pinned by `KC_HOSTNAME`, so the services accept it). No Windows port forward is involved.

**Data quality, stated first.** Docker Desktop stopped during the first attempt (`stall-hunt-container-2026-11-06`): runs 1 to 3 are valid, run 4 spans a gap of about 2.6 hours (the machine was probably suspended) and is discarded, runs 5 to 8 failed immediately with `no such host`. The second attempt (`stall-hunt-container-2-2026-11-06`) completed all 8 runs; those are the numbers below.
A third experiment (`pg-waits-2026-11-06`) sampled Postgres wait events once a second with `docker exec`; that probe loaded the machine, p95 got worse run after run (4.7 s, 53 ms, 7.9 s, 5.7 s, 49 s), and Docker's engine hung during run 6 (`error waiting for container: unexpected EOF`), so Docker Desktop had to be restarted. Treat its latencies as invalid; only the kind of wait it recorded is informative.

| Run (2nd attempt) | Rate | Achieved/s | Failed | p95 ms | Max ms | Dropped iterations | Hikari active (max) | Hikari pending (max) | Active HTTP requests (max) |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 300 | 293 | 0 | 12 | 823 | 53 | 10 | 127 | 138 |
| 2 | 600 | 397 | 0 | **813** | 1,342 | **5,954** | 10 | 191 | 200 |
| 3 | 300 | 299 | 0 | 7 | 38 | 0 | 4 | 0 | 3 |
| 4 | 600 | 599 | 0 | 7 | 32 | 0 | 5 | 0 | 7 |
| 5 | 300 | 294 | 0 | **758** | 2,112 | 159 | 10 | 191 | 200 |
| 6 | 600 | 598 | 0 | 13 | 86 | 0 | 10 | 10 | 12 |
| 7 | 300 | 187 | 0 | **2,274** | 7,868 | **1,754** | 10 | 191 | 200 |
| 8 | 600 | 583 | 0 | 17 | 616 | 232 | 10 | 3 | 13 |

- **No failed requests from the container, but real saturation episodes.** In runs 2, 5 and 7 the Hikari pool (10 connections) was fully used with up to 191 requests waiting for a connection and all 200 Tomcat threads busy; k6 ran out of free VUs and dropped iterations. Runs 3, 4 and 6 at the same or a higher rate were clean (p95 7 to 13 ms). Both rates produced both outcomes, so the episodes still do not follow the arrival rate. They clear by themselves.
- **This changes the earlier explanation.** In the host-k6 repeat, the stalled run showed no pressure in the app (Hikari pending 0) and the hung requests created no orders; here the application is visibly saturated, and nothing fails. The host-path stall (31 hung connections reset after 30 s) was **not reproduced** from the container (0 reset errors in 8 runs), which is some evidence that the Windows port forward is involved in that one; it is still not proof. The container-path episodes are a second effect that sits behind the port forward.
- **What the database was waiting on.** During load, the non-idle Postgres sessions were mostly waiting on `LWLock:WALWrite` and `IO:WALSync` in `COMMIT` (for example 107 and 16 session-seconds in the first diagnostic run, against 15 for reading outbox rows). That means commits queued behind the WAL flush: `synchronous_commit=on`, `fsync=on`, `wal_sync_method=fdatasync`, `max_wal_size=1GB`. A slow or variable `fdatasync` on Docker Desktop's virtual disk would produce this (every order is one transaction with an `orders`, `order_lines` and `outbox` insert and one commit), and it fits the pool filling up while Postgres CPU is idle. **That is the leading explanation, not a measured fact:** I did not measure `fdatasync` latency or disk I/O in the VM, and the probe disturbed that run.
- **Not tried, in order of usefulness:** time `pg_test_fsync` inside the Postgres container; repeat 300/s with `synchronous_commit=off` (if the episodes vanish, the WAL flush is confirmed); raise the Hikari pool; run the same test on a Linux machine or the AWS deployment (step 13), where the disk is different.
- **How to quote performance now:** order-service sustained 50/s with p95 of 7 to 13 ms in every run; at 300 to 800/s the same code was clean in most runs and saturated in some, with the cause most likely the local Docker disk, not the application code. Do not publish a "maximum orders/s" from this machine.

### Reading the k6 numbers: virtual users, dropped iterations, the 500 ms line
Questions raised while reviewing "Stall chase, part 2" above, with the answers.

- **Virtual user (VU).** A worker that runs the script's `default` function one pass at a time. In `create-orders.js` one pass (an *iteration*) is one `POST /api/orders` plus its check. A VU is busy until its response arrives.
  The script uses the `constant-arrival-rate` executor ("start N iterations per second whether or not earlier ones finished") with `preAllocatedVUs: 100` and `maxVUs: 300`. Starting an iteration needs a **free** VU.
  Busy VUs are roughly rate x response time: at 600/s with 5 ms responses about 3 are busy; with 2 s responses about 1,200 would be needed, and only 300 exist.
- **Dropped iteration is not a failed request.** It is an iteration k6 scheduled but never started because no VU was free. No request was sent, so it is not a non-201 and it is **not** counted in `http_req_failed` (which stayed at 0% in the container runs).
  It means the load generator could not deliver the load that was asked for: in run 2, 5,954 of the 18,000 scheduled orders were never sent, so the server saw about 397 requests/s instead of 600 (run 7: 187/s instead of 300).
  A *failed request* is one that was sent and got an error or timeout (the host-path resets in "Chasing the stall"). Neither is a *lost order*: see [resilience.md](resilience.md), "Limits of the "0 lost" claim".
- **What the saturation looked like.** Every order takes one of Hikari's 10 connections and holds one of Tomcat's 200 threads until its transaction commits. If commits slow down, connections return slower than requests arrive: all 10 are in use, requests queue
  for a connection (up to 191 waiting), threads pile up (200 busy), k6 runs out of free VUs and drops iterations, and the requests that do complete are slow because they waited. There were no failures because the waits were seconds, far below k6's 60 s request timeout.
- **`passes` and `fails` in a `--summary-export` JSON are inverted for `http_req_failed`.** It is a Rate metric whose value is `true` when a request failed, and k6 labels the `true` count `passes` and the `false` count `fails`. So `"passes": 0, "fails": 1503` (AWS trial, `trial-25-summary.json`) means 0 failed requests and 1,503 that did not fail. The console line `0.00% 0 out of 1503` says the same thing without the confusion.
  Likewise a threshold shown as `false` in that file (`"rate<0.01": false`) means it was not breached.
- **Why 500 ms.** It is the pass line in the script: `http_req_duration: ['p(95)<500']`, next to `http_req_failed: ['rate<0.01']`. p95 is the time 95% of requests finished under. Runs 2, 5 and 7 (p95 813, 758 and 2,274 ms) are the ones that break it.
  *Correction:* an earlier note called this "a stricter cut than the table shows"; that was wrong. The table has no pass/fail column and 500 ms is the project's own threshold, not a stricter one.
- **Pressure that stays under the line.** Run 1 (127 requests waiting, 53 dropped iterations, p95 12 ms) and run 8 (232 dropped, p95 17 ms) pass the thresholds although the system was under pressure. So some pressure showed in 5 of the 8 runs (1, 2, 5, 7, 8) and 3 of them (2, 5, 7) broke the 500 ms threshold.
- **Gap in the script (not fixed):** there is no threshold on dropped iterations or on the achieved rate, so a run that fails to deliver the requested rate can still pass (run 8: 583/s achieved against 600; run 1: 293 against 300).
  A threshold such as `dropped_iterations: ['count<1']`, or a check that the achieved rate is at least 95% of the target, would flag those runs.
- **Reading the claim.** "0 failed requests from the container" is true and incomplete: about one run in three was too slow. Say "no errors, but intermittent latency collapse".
