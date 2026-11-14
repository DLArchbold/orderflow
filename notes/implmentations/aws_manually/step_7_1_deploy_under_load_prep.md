# Step 7.1: Deploy under load (24c), preparation (sections A to C)

Date: 2026-11-13. Region `us-east-1`, profile `SystemAdministrator-702623019395`, account `702623019395`.
Sections E onwards (run the deploy under load, record evidence, destroy) are **not done yet**.

## A. Prerequisites: done
- Tools present: k6, aws, terraform, docker. AWS login works (`aws sso login --profile SystemAdministrator-702623019395`).
- Baseline tag (images in ECR and deployed before this test): `9ff11d1`.
- Secrets injection is already in the Terraform (`secrets` with `valueFrom` in `ecs_services.tf`; the execution role has `secretsmanager:GetSecretValue`), so nothing to add for that part of 24c.

## B. Restore the baseline: done, nothing applied
- All five ECS services at 1/1, PRIMARY deployment `COMPLETED`; order-service on task definition `:4`, image `:9ff11d1`.
- Target groups `order`, `frontend`, `keycloak`: all `healthy`. ALB `/` returns 200; `/realms/orderflow/.well-known/openid-configuration` returns 200.
- Kafka restarted with no volume (topics empty, accepted in ADR 0006). Order-service and inventory-service were assigned partitions about 1 minute after Kafka started; later Kafka log lines were idle-connection INFO messages, no errors. No forced redeploy needed.
- End-to-end: token for alice, `POST /api/orders` returned 201 `PENDING`, and 8 seconds later `GET` returned `CONFIRMED`.
- `terraform plan -var image_tag=9ff11d1` showed no ECS changes. Its only difference was the budget limit: 20 in AWS (changed outside Terraform), 10 in the `budget_usd` default.
- **Decision:** set `budget_usd = 20` in `terraform.tfvars` (git-ignored) so the plan stops showing that change. Not applied by itself; it is a no-op once the state matches.

## C. Prepare the second image: done
- New commit: **`dc1acc0`**: a small real change to the log line in `InventoryResultHandler.java` ("... ignored (order is no longer PENDING)"). No test depends on that text.
- Built with `--platform linux/amd64` and pushed all three images under `dc1acc0`. Both `9ff11d1` and `dc1acc0` are present in each ECR repository.
- Login detail: `docker login ... --password-stdin` failed with `400 Bad Request` from PowerShell; `--password $pw` (as in the redeploy notes) worked. A throwaway `DOCKER_CONFIG` at `%TEMP%\docker-ecr` was used and is still in place (remove it in the cleanup step).

Image sizes (record these in the 24c write-up):

| Image | ECR compressed, `9ff11d1` | ECR compressed, `dc1acc0` | Local `docker images` |
|---|---|---|---|
| order-service | 204,187,685 B | 204,215,474 B | 643 MB |
| inventory-service | 186,680,616 B | 186,708,337 B | 606 MB |
| frontend | 26,424,868 B | 26,424,866 B | 94 MB |

- order-service and inventory-service differ only by a rebuilt jar. The frontend code is unchanged, so it is effectively the same image under a new tag. Say so in the write-up; do not describe it as a new frontend version.
- `terraform plan -var image_tag=dc1acc0`: replaces the order, inventory and frontend task definitions (`9ff11d1` to `dc1acc0`) and updates those three services in place. Kafka and Keycloak are not touched, which is required so they don't restart during the test.

## D. Prepare the load test: done
ALB URL: `http://orderflow-1966634175.us-east-1.elb.amazonaws.com`, used for both `BASE_URL` and `KEYCLOAK_URL` (the ALB routes `/realms/*` to Keycloak). k6 2.3.0, run from the Windows machine, script `phase2/load-tests/k6/create-orders.js` unchanged. Outputs in `phase2/docs/k6-runs/aws-deploy/`.

| Run | Rate, duration | Requests | `http_req_failed` | Dropped iterations | p95 | Max | VUs used |
|---|---|---|---|---|---|---|---|
| Smoke (`smoke-*`) | 10/s, 30 s | 303 | 0 of 303 | none reported | 137.58 ms | 489.56 ms | 2 |
| Trial (`trial-25-*`) | 25/s, 1 min | 1,503 | 0 of 1,503 | none reported | 128.45 ms | 450.58 ms | 6 |

- Both thresholds passed in both runs; every order request returned 201. Request counts include the 2 token requests from `setup()` (1,501 orders + 2 = 1,503).
- Reading the JSON: in `trial-25-summary.json`, `http_req_failed` shows `"passes": 0, "fails": 1503`. That is k6's inverted labelling (a "pass" is a failed request), so it means 0 failed and 1,503 not failed. Thresholds shown as `false` mean not breached. See `perf.md`, "Reading the k6 numbers".
- **Chosen for the deploy run: 25 orders/s for 8 minutes** (about 12,000 orders). Stock is 1,000,000 per SKU, so it will not run out.
- The latency includes the network path from this machine to `us-east-1`, so it is not comparable with the local numbers in `perf.md`.
- These runs are against the old image (`9ff11d1`) with no deploy; they are the baseline, not the 24c evidence.

## Next
E and F: open the watch tools, start k6 (25/s, 8 min, `--out json=` plus `--summary-export` and the console), run `terraform apply -auto-approve -var image_tag=dc1acc0` at about the 2-minute mark, and wait for all three services to show one PRIMARY deployment with `COMPLETED`. Then record the evidence, destroy and check the console.
