# Deploy under load on AWS (PLAN step 24c)

**Result: a rolling deploy of the three application services on ECS Fargate, made while k6 sent 25 orders/s for 8 minutes, completed with
0 failed requests out of 12,003 and 0 dropped iterations.** Every order was accepted with a 201 and confirmed exactly once.
There was one latency spike of about 3 seconds when the new order-service task took over (17 requests over 500 ms, the slowest 1,760 ms).

This is one run at one rate. It is not called zero-downtime here; see "How to state this result" and "What this run does not show".

## How to state this result
The claim is about this run, so it carries the run's conditions with it.

- **Short form:** "Deployed a new version to ECS Fargate under a load of 25 orders/s with 0 failed requests out of 12,003, in one 8-minute run."
- **Full form:** the short form, plus "0 dropped iterations, p95 131 ms, and one latency spike of about 3 seconds at the cutover (slowest request 1,760 ms);
  one task per service, and the new version differed by one log message."

Wording the evidence does not support:

| Do not say | Why |
|---|---|
| "Zero-downtime deployments" | That describes the system in general. The evidence is one run, at one rate, of a trivial change. |
| "No impact on users" | 17 requests took between 0.5 and 1.8 seconds at the cutover. None failed, but they were slower. |
| "Deployed under production load" | 25 orders/s against one task per service is a chosen test rate, not a production traffic level. |
| "0 failed requests" with no numbers | The count, the rate and the duration are what make the claim checkable. |
| "Zero-downtime" for the whole stack | Kafka and Keycloak deploy by stop-then-start and were not redeployed in this run. |

## Setup
- **Date and place:** 2026-11-14, AWS `us-east-1`, the stack from `phase2/infra/terraform` (decisions in [ADR 0006](adr/0006-aws-choices.md)).
- **What was deployed:** `terraform apply -var image_tag=dc1acc0`, replacing tag `9ff11d1` on order-service, inventory-service and frontend.
  Kafka, Keycloak and RDS were not touched.
- **What changed between the tags:** one reworded log message in order-service (`InventoryResultHandler`). order-service and inventory-service
  differ only by a rebuilt jar. The frontend code is unchanged, so its image is the same content under a new tag, not a new frontend version.
- **Services:** one task each on Fargate. order-service 1 vCPU and 2 GB, inventory-service 0.5 vCPU and 1 GB, frontend 0.25 vCPU and 0.5 GB,
  Kafka and Keycloak 1 vCPU and 2 GB each. RDS PostgreSQL 16 on `db.t4g.micro`, single AZ. No Redis.
- **Deployment settings (the three application services):** `minimum_healthy_percent = 100`, `maximum_percent = 200`, so the new task starts
  and passes its health check before the old one stops; circuit breaker with rollback; target group `deregistration_delay` 30 s;
  order-service health check on `/actuator/health/readiness`.
- **Load:** k6 2.3.0 with `phase2/load-tests/k6/create-orders.js`, constant arrival rate of 25 orders/s for 8 minutes (`POST /api/orders`, SKU-1 and SKU-2
  mixed, tokens for alice and bob). Run from one Windows machine over the internet to the public ALB, HTTP only. Thresholds: failures under 1%, p95 under 500 ms.
- **Baseline:** the same script at 25/s for 1 minute against `9ff11d1` with no deploy gave 0 failed of 1,503, p95 128 ms, max 451 ms.
- **Why 25 orders/s:** it was the starting rate in the run plan (step 20 of `notes/implmentations/aws_manually/step_0_master_step_list.txt`:
  run 1 minute at 25/s, require 0 failed requests, 0 dropped iterations and p95 under 500 ms, lower the rate if it fails). The baseline passed
  first time, so it was kept; why 25 was proposed rather than another number was not recorded, and no higher rate was tried on AWS.
  The reasons a low rate suits this test:
  - The run measures the deploy, not capacity. The rate must sit well inside what the stack handles without a deploy, so that a failure during
    the run points at the deploy and not at overload.
  - The stack is small: one task per service, inventory-service on 0.5 vCPU, RDS on a burstable `db.t4g.micro`. The local limits in
    [perf.md](perf.md) (400 orders/s accepted) come from a much stronger machine and do not carry over.
  - The load came from one machine over the internet. At about 130 ms per request, 25/s needs only a few virtual users (at most 11 were in use),
    so the load generator was not a limit.
  - 8 minutes at 25/s gives about 12,000 orders and room before and after a rollout of about 2 minutes, with a raw output (51.5 MB) that could
    still be committed once split in two.
  - It is in the same range as the local runs: the failure drills ran at 20/s and k6 was clean at 50/s.

Image sizes:

| Image | ECR compressed, `9ff11d1` | ECR compressed, `dc1acc0` | Local `docker images` |
|---|---|---|---|
| order-service | 204.2 MB | 204.2 MB | 643 MB |
| inventory-service | 186.7 MB | 186.7 MB | 606 MB |
| frontend | 26.4 MB | 26.4 MB | 94 MB |

## Results
| Measure | Value |
|---|---|
| Requests | 12,003 (12,001 orders plus 2 token requests in `setup()`) |
| Failed requests (`http_req_failed`) | **0 of 12,003** |
| Responses other than 201 on order requests | 0 of 12,001 |
| Dropped iterations | 0 (12,001 completed against a target of 12,000; at most 11 of 100 virtual users in use) |
| Achieved rate | 24.98 orders/s |
| Latency | median 94 ms, p90 113 ms, **p95 131 ms**, max 1,760 ms |
| Thresholds | both passed |
| ALB 5xx (target and load balancer) | no datapoints for the window, which means none occurred |
| Rollout | all three deployments `SUCCESSFUL`, no rollback |
| Orders confirmed | 12,001, each exactly once |

The latency includes the network path from the load machine to `us-east-1`, so it is not comparable with the local numbers in [perf.md](perf.md).

## Timeline (UTC)
k6 times come from the load machine's clock, which ran about 4 seconds behind the AWS clock. ECS and log times are on the AWS clock.

| Time | Event | Source |
|---|---|---|
| 18:31:53 | k6 started | raw k6 output |
| 18:33:53 | `terraform apply` started (k6 start plus 2 minutes; not recorded directly) | run notes |
| 18:34:11 to 18:34:17 | ECS started the three deployments | `rollout-history.json` |
| 18:34:49 | new order-service task wrote its first log line | order-service logs |
| 18:35:36 | Spring finished starting in the new task | order-service logs |
| 18:35:50, 18:35:53 | frontend and inventory-service deployments finished | `rollout-history.json` |
| 18:35:59 | new order-service task logged its first order | order-service logs |
| 18:36:08 | old order-service task logged its last order | order-service logs |
| **18:36:14** | **order-service deployment finished; rollout complete** | `rollout-history.json` |
| 18:36:46 | old order-service task wrote its last log line | order-service logs |
| 18:39:54 | k6 finished | raw k6 output |

The rollout took about 2 minutes from the first deployment starting to the last one finishing. k6 ran for 2 minutes before it and more than 3 minutes after it.

## What happened during the rollout
- **No request failed in any 10-second window**, before, during or after the rollout ([deploy-timeline.txt](k6-runs/aws-deploy/deploy-timeline.txt)).
- **One latency spike.** In the window starting 18:35:50 (load machine clock), 17 requests took longer than 500 ms, all within about 3 seconds, and the
  slowest took 1,760 ms. This is when the new order-service task began serving requests. Every other window has a p95 under 330 ms and a maximum
  under 430 ms. The ALB shows the same minute as its slowest (`TargetResponseTime` p95 of 71 ms at 18:36, against 15 to 55 ms in the other minutes).
- **Both tasks served orders during the overlap.** The old task logged 6,135 orders created and the new task 5,866, which is 12,001 in total.
- **Events were published twice during the overlap, as ADR 0006 predicts, and it was harmless.** The two tasks published the same outbox rows for a
  short time: the logs show 12,542 publishes for 12,001 orders, so the `OrderCreated` event of 541 orders was sent twice, all between 18:35:37 and 18:36:45.
  inventory-service ignored all 541 duplicates ("already processed; duplicate ignored") and reserved stock once for each of the 12,001 orders.
- **inventory-service published twice during its own overlap too.** Its old and new tasks both published the same 112 `StockReserved` events
  between 18:35:50 and 18:36:03: 12,113 publishes for 12,001 events. order-service received those 12,113 inventory results and ignored the 112
  that arrived for an order that was already `CONFIRMED`. The outcome is 12,001 orders confirmed, each exactly once (7,174 by the old
  order-service task, 4,827 by the new one). Why both tasks sent the same rows (the publisher's select takes no row lock) is explained in
  [architecture.md](architecture.md#duplicate-publishes-during-a-rolling-deploy-why-order-service-received-12113-results-for-12001-orders).

## Things in the evidence that look odd but are explained
- **The ALB counted 14,033 requests, 2,030 more than k6 sent.** A browser tab was open on the frontend, signed in as alice. The frontend repeats its
  stock lookup (`GET /api/stock/SKU-1`) whenever its order list changes, and it receives live updates for alice's orders, which k6 was creating.
  The order-service logs show 2,025 of these lookups, only while k6 ran. They returned without a 5xx as well. This is inferred from the logs and the
  frontend code; there are no ALB access logs.
- **About 3,800 "Unable to connect to Redis" warnings.** They come from those same stock lookups (2,025 `Cache get failed`, 1,796 `Cache put failed`),
  on both the old and the new task. There is no Redis on AWS by design (ADR 0006). Creating an order does not use the cache.
- **The new task is tied to `dc1acc0` by elimination, not by a direct record.** ECS marks a deployment `SUCCESSFUL` only once a task from the new
  revision is healthy and the old task is stopped; that revision points at `dc1acc0`; and the logs show exactly two order-service tasks in the window.
  `describe-tasks` for the stopped task had expired before it was saved.

## What this run does not show
- **Not a general zero-downtime claim.** It is one run, at one rate (25 orders/s), for 8 minutes. Higher rates and repeated runs were not tried.
- **One task per service.** Behaviour with several tasks per service, or with autoscaling, was not tested.
- **A trivial change.** The new version differs by one log message. A deploy with a database migration or an API change was not tested.
- **Only order creation was under load.** Reads, cancellations and the live order stream were not part of the k6 script.
- **Kafka, Keycloak and RDS were not redeployed.** Kafka and Keycloak use stop-then-start (0 / 100), so deploying either would interrupt requests.
- **No rollback under load.** The circuit breaker did not trigger in this run, so its behaviour under traffic is untested here.
- **Order outcomes were read from the order-service and inventory-service logs,** not queried from the database.
- **One load machine, over the internet, HTTP only.**

## Evidence
All in [k6-runs/aws-deploy/](k6-runs/aws-deploy/):

| File | What it holds |
|---|---|
| `deploy-console.txt`, `deploy-summary.json` | k6 console output and summary |
| `deploy-run-part1.json`, `deploy-run-part2.json` | raw k6 output, one file split in two at a line boundary; read both in order |
| `deploy-timeline.txt` | the raw output bucketed into 10-second windows |
| `aws-deploy-times.txt` | the recorded times and where each comes from |
| `rollout-history.json` | ECS deployment status, start and finish times per service |
| `task-definitions.json` | the task definition revisions and their image tags |
| `alb-metrics.json` | ALB 5xx counts, request count and response time, per minute |
| `ecr-images.json` | ECR compressed image sizes |
| `order-service-logs-deploy-window.txt` | order-service logs for 18:30 to 18:41 UTC |
| `inventory-service-logs-deploy-window.txt` | inventory-service logs for the same window |
| `smoke-*`, `trial-25-*` | the baseline runs against `9ff11d1` |
