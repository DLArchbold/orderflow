# ADR 0006: AWS deployment choices (Terraform, ECS Fargate)

<<<<<<< HEAD
Status: applied and exercised. The Terraform in `phase2/infra/terraform` has been applied to AWS (from 2026-11-10) and a deploy under load was run on 2026-11-14 (see "What the deploy under load showed"). The stack was then removed with `terraform destroy` on 2026-11-15 and the console checked for leftovers (see "State of this work").
=======
Status: applied and exercised. The Terraform in `phase2/infra/terraform` has been applied to AWS (from 2026-11-10) and a deploy under load was run on 2026-11-14 (see "What the deploy under load showed"). The destroy and the leftover check have not happened yet (see "State of this work").
>>>>>>> ec7ee81a0d4f1bb784c463cc9118d6fe9cd28d69

## Decisions
| Topic | Choice | Why, and what it costs |
|---|---|---|
| Compute | ECS Fargate, one service per container, rolling deploy | No servers to patch; inventory-service 0.5 vCPU and 1 GB; order-service 1 vCPU and 2 GB with a 300 s health-check grace period (seen 2026-11-11: on 0.5 vCPU Spring took 116 s to start, the 120 s grace period expired before the ALB marked the target healthy, and ECS stopped each new task every ~4 minutes while the old one kept serving). |
| Network | VPC, 2 AZs; **public subnets for the ALB and the tasks, no NAT gateway** | A NAT gateway is about $32 a month plus data. Tasks get public IPs and security groups are the only guard. Acceptable for a demo; a real deployment puts tasks in private subnets behind a NAT or VPC endpoints. |
| Database | RDS PostgreSQL `db.t4g.micro`, single AZ, `skip_final_snapshot`, no deletion protection, in private subnets, password generated and kept in Secrets Manager | RDS is the slowest and costliest piece, so it is created once and early. Nothing survives `terraform destroy`, on purpose. Terraform cannot create databases inside RDS: a one-off Fargate task (`db-init`) runs `psql`. |
| Kafka | **One `apache/kafka` container on ECS** with a Cloud Map DNS name (`kafka.orderflow.local`) | MSK Serverless is a few hundred dollars a month and the smallest MSK cluster tens of dollars. The container has no volume, so the topics are gone when the task restarts. Accepted for a demo; the outbox republishes unpublished rows, but already-published events are lost. |
| Redis | **None** | The cache error handler (step 17c) means the stock endpoint still answers without it, at about 1 s per call. Seen live in the step 6 smoke test (2026-11-10): every stock lookup logs two `WARN` lines from `CacheConfig` (`Cache get failed` and `Cache put failed`, `RedisConnectionFailureException`), and orders are unaffected. The warnings are deliberately left visible, not silenced with a log-level override, because they are the only sign that the cache is not working. Not needed for the resume or interviews: the claim is the cache design, which compose shows; say plainly that Redis is omitted from AWS for cost (an ElastiCache `cache.t4g.micro` or a Redis task on ECS would be the cheap options) and that the cache fails open. |
| Identity | **Keycloak in dev mode** as one more ECS service with the realm import, reached through the ALB at `/realms/*`, `KC_HOSTNAME` = the ALB URL | Not production-grade (dev mode, H2 database, the realm's throwaway user passwords are in the repository). The token issuer must equal `issuer-uri` exactly; the services therefore validate the issuer against the public ALB URL but fetch the signing keys from the internal `keycloak.orderflow.local` name (`jwk-set-uri`). If this proves too fiddly, drop the `issuer-uri` and keep only `jwk-set-uri`, which skips the issuer check; say so in the README. |
| HTTPS | **HTTP only** | With no domain there is no ACM certificate. Tokens and passwords cross the internet in clear text. A real deployment needs a domain and a certificate. |
| State | **Local state**, one person on one machine | Simple. `terraform.tfstate` holds the generated passwords, so it is git-ignored. For sharing, use an S3 backend with `use_lockfile = true` (check the docs for the Terraform version in use). |
| Deployments | `minimum_healthy_percent = 100`, `maximum_percent = 200`, circuit breaker with rollback, target group `deregistration_delay` 30 s. Kafka and Keycloak use 0 / 100 (stop, then start) | Two Kafka brokers or two Keycloak instances with separate storage would split the data. |

## Consequence worth saying out loud when presenting the deploy
During a rolling deploy old and new order-service tasks overlap, so for a short time **two instances publish the same outbox rows**
and some events are sent twice. The idempotent consumer in inventory-service (`processed_messages`, step 14) keeps that harmless; how it works is in
[architecture.md](../architecture.md#idempotent-consumer-how-inventory-service-ignores-duplicates).

## What the deploy under load showed (2026-11-14)
Full results are in [aws-deploy-under-load.md](../aws-deploy-under-load.md): 25 orders/s for 8 minutes, tag `9ff11d1` to `dc1acc0`, 0 failed requests out of 12,003.
What it adds to the decisions above:

- **Deployments: the rolling settings held under load.** All three deployments were `SUCCESSFUL` with no rollback, and no request failed in any
  10-second window. The order-service deployment took 1 minute 57 seconds; all three were done about 2 minutes after the first one started.
- **Compute: 1 vCPU is enough for order-service to start well inside the grace period.** The new task logged "Started OrderServiceApplication in 47.8 s"
  (116 seconds on 0.5 vCPU, seen 2026-11-11), against a 300 s grace period.
- **The duplicate-publish consequence was measured.** The old and new order-service tasks overlapped for about a minute and published 12,542 events
  for 12,001 orders, so the `OrderCreated` event of 541 orders was sent twice. Every order was still confirmed exactly once.
- **Surprise: duplicates also arrived in the other direction.** order-service received 12,113 inventory results for 12,001 orders and ignored the 112
  that arrived for an order already `CONFIRMED` (the status check from [ADR 0003](0003-order-status-from-inventory.md)). So the consequence above is
  not only kept harmless by inventory-service's idempotent consumer; order-service's own check is needed too. The cause is the same overlap one
  service further on: the inventory-service logs show its old and new tasks both publishing the same 112 `StockReserved` outbox events between
  18:35:50 and 18:36:03. The idempotent consumer did its part as well: inventory-service ignored 541 duplicate `OrderCreated` messages and reserved
  stock once per order. Why the two tasks send the same rows (the outbox publisher's select takes no row lock, and a batch commits
  `published_at` only at its end) is explained in
  [architecture.md](../architecture.md#duplicate-publishes-during-a-rolling-deploy-why-order-service-received-12113-results-for-12001-orders).
- **Surprise: a latency spike when the new task takes over.** 17 requests took longer than 500 ms within about 3 seconds, the slowest 1,760 ms,
  as the new order-service task served its first requests. Nothing failed. With one task per service there is no warm instance to share the load,
  and nothing warms the new task up before the ALB sends it traffic.
- **Surprise: an open frontend tab multiplies requests.** The frontend repeats its stock lookup whenever its order list changes, and it receives live
  updates for the signed-in user's orders. A tab left open as alice during the run sent 2,025 stock lookups (the ALB counted 14,033 requests against k6's 12,003).
  With no Redis, each one logged the two `WARN` lines described in the Redis row, about 3,800 in total.
- **Redis: the lookups were not slow in this run.** The Redis row says about 1 s per call without the cache. Here the stock lookups were about 14% of
  all requests and the ALB's `TargetResponseTime` p95 stayed between 15 and 71 ms per minute, so they cannot have taken around 1 s each. The lookups
  were not timed individually.
- **Not exercised:** the circuit breaker did not trigger, and Kafka and Keycloak (0 / 100, stop then start) were not redeployed.

## What the deploy under load showed (2026-11-14)
Full results are in [aws-deploy-under-load.md](../aws-deploy-under-load.md): 25 orders/s for 8 minutes, tag `9ff11d1` to `dc1acc0`, 0 failed requests out of 12,003.
What it adds to the decisions above:

- **Deployments: the rolling settings held under load.** All three deployments were `SUCCESSFUL` with no rollback, and no request failed in any
  10-second window. The order-service deployment took 1 minute 57 seconds; all three were done about 2 minutes after the first one started.
- **Compute: 1 vCPU is enough for order-service to start well inside the grace period.** The new task logged "Started OrderServiceApplication in 47.8 s"
  (116 seconds on 0.5 vCPU, seen 2026-11-11), against a 300 s grace period.
- **The duplicate-publish consequence was measured.** The old and new order-service tasks overlapped for about a minute and published 12,542 events
  for 12,001 orders, so the `OrderCreated` event of 541 orders was sent twice. Every order was still confirmed exactly once.
- **Surprise: duplicates also arrived in the other direction.** order-service received 12,113 inventory results for 12,001 orders and ignored the 112
  that arrived for an order already `CONFIRMED` (the status check from [ADR 0003](0003-order-status-from-inventory.md)). So the consequence above is
  not only kept harmless by inventory-service's idempotent consumer; order-service's own check is needed too. The likely cause is inventory-service's
  own two tasks overlapping and publishing the same outbox rows, but its logs were not saved, so that is not confirmed.
- **Surprise: a latency spike when the new task takes over.** 17 requests took longer than 500 ms within about 3 seconds, the slowest 1,760 ms,
  as the new order-service task served its first requests. Nothing failed. With one task per service there is no warm instance to share the load,
  and nothing warms the new task up before the ALB sends it traffic.
- **Surprise: an open frontend tab multiplies requests.** The frontend repeats its stock lookup whenever its order list changes, and it receives live
  updates for the signed-in user's orders. A tab left open as alice during the run sent 2,025 stock lookups (the ALB counted 14,033 requests against k6's 12,003).
  With no Redis, each one logged the two `WARN` lines described in the Redis row, about 3,800 in total.
- **Redis: the lookups were not slow in this run.** The Redis row says about 1 s per call without the cache. Here the stock lookups were about 14% of
  all requests and the ALB's `TargetResponseTime` p95 stayed between 15 and 71 ms per minute, so they cannot have taken around 1 s each. The lookups
  were not timed individually.
- **Not exercised:** the circuit breaker did not trigger, and Kafka and Keycloak (0 / 100, stop then start) were not redeployed.

## State of this work
`terraform fmt -recursive` changed four files (formatting only), and after that `terraform init -backend=false` and `terraform validate` (Terraform 1.16.6, AWS provider 5.x) reported "The configuration is valid" on 2026-11-06. That checks syntax, references and argument names against the provider schema; it needs no AWS account and proves nothing about whether the resources will create. `plan` and `apply` were never run (no AWS credentials in use), so expect to fix errors on the first plan. No deploy, load test during a deploy, or destroy check has happened.

Update 2026-11-14: the paragraph above describes the state on 2026-11-06. Since then the stack has been planned and applied (first deployment 2026-11-10;
<<<<<<< HEAD
the fixes found along the way are in the Decisions table), and a deploy under load was run on 2026-11-14. After that, on 2026-11-15, `terraform destroy` removed 61
resources and the check for leftovers in `us-east-1` was clean, so nothing from this stack is running. The budget was kept on purpose: it was
removed from Terraform's state before the destroy, so a later apply must `terraform import` it first (see the Terraform README).
=======
the fixes found along the way are in the Decisions table), and a deploy under load was run on 2026-11-14. Still not done: `terraform destroy` and the
check for leftovers in the console.
>>>>>>> ec7ee81a0d4f1bb784c463cc9118d6fe9cd28d69
