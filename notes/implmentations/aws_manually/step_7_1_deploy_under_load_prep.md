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

## E. Watch tools for the run: ALB metrics (second source of evidence besides k6)
Do this before `terraform destroy`. The ALB must still exist to find its dimension, though the metrics stay in CloudWatch for about 15 months.

**1. Note the time window.** Write down the k6 start and end in UTC, and the moment `terraform apply` ran. Filter every graph to that window plus a few minutes of margin.

**2. Open the metrics.** CloudWatch, region `us-east-1`, Metrics, All metrics, `ApplicationELB`, **Per AppELB Metrics**, then the row for the load balancer (`app/orderflow/<hash>`). Every `terraform apply` creates a new ALB with a new hash, so make sure it is the one from this run.

**3. Statistic and period.** Use a **1 minute** period for all four.

| Metric | Statistic | Meaning |
|---|---|---|
| `RequestCount` | Sum | Requests received. Should roughly match k6's request count. |
| `HTTPCode_Target_2XX_Count` | Sum | 2xx responses from the containers. Should roughly match the 201s. |
| `HTTPCode_Target_5XX_Count` | Sum | 5xx returned by the containers (order-service and so on). |
| `HTTPCode_ELB_5XX_Count` | Sum | Errors generated by the ALB itself (502/503 when no healthy target exists). |
| `TargetResponseTime` | p95 (under Percentiles in the dropdown) | Seconds from ALB to container. Multiply by 1,000 to compare with k6's ms. |

- Use Sum for counts. Average divides by the number of datapoints, not time, so it hides the volume.
- **Do not use 1 second.** ALB metrics are standard resolution, one datapoint per minute. The console offers 1 and 10 second periods anyway. A 1-second graph may show a different number of points, but that is the same minute totals drawn differently, not finer detail. To check, compare the Sum over the run window at both periods: the totals should match. Use the 1-minute numbers in the write-up. For a real per-second view use k6's `--out json=` file.
- A fixed absolute range that misses the run, or a console time zone that differs from your notes, also gives an empty graph. Use Relative, 1 week, to rule that out.

**4. Missing 5xx metrics are not an error.** CloudWatch creates a count metric only when it has its first datapoint. If no 5xx occurred, `HTTPCode_Target_5XX_Count` and `HTTPCode_ELB_5XX_Count` are not in the list. Confirm with `HTTPCode_Target_2XX_Count` and `RequestCount`, which must be present. In the write-up say "no datapoints", not "measured 0".

CLI list of metrics that exist for the ALB:
```
aws cloudwatch list-metrics --region us-east-1 --namespace AWS/ApplicationELB --query "Metrics[].MetricName" --output text
```
Fetch one metric:
```
aws cloudwatch get-metric-statistics --region us-east-1 --namespace AWS/ApplicationELB \
  --metric-name RequestCount --dimensions Name=LoadBalancer,Value=app/orderflow/<hash> \
  --start-time <UTC start> --end-time <UTC end> --period 60 --statistics Sum
```
For `TargetResponseTime` use `--extended-statistics p95` in place of `--statistics`. Get `<hash>` from `aws elbv2 describe-load-balancers --names orderflow`: everything after `loadbalancer/` in the ARN.

**5. If a graph is empty.** Use `RequestCount` as a control: your k6 run sent over 1,500 requests, so it cannot be legitimately empty. If it has data, the query is right and an empty 5xx graph is a true result. If it is also empty, check: region, which ALB hash, Per AppELB Metrics category, Sum statistic, UTC vs local time, and account. `aws cloudwatch list-metrics --namespace AWS/ApplicationELB --metric-name RequestCount` prints the exact dimension value to paste. Optional extra check: `curl <app_url>/api/does-not-exist`, wait about 2 minutes, then graph `HTTPCode_Target_4XX_Count`.

**6. Compare with k6.**
- Both 5xx metrics absent or zero agrees with `http_req_failed` 0%. A non-zero `HTTPCode_ELB_5XX_Count` during the deploy would show requests with no healthy target.
- `TargetResponseTime` covers only ALB to container, so it is lower than k6's `http_req_duration`, which includes the network from this machine.
- ALB metrics only cover requests that reached the ALB. A connection that never arrived shows in k6 but not here.
- Record the window, the results and screenshots next to the k6 evidence.

## Next
E and F: open the watch tools, start k6 (25/s, 8 min, `--out json=` plus `--summary-export` and the console), run `terraform apply -auto-approve -var image_tag=dc1acc0` at about the 2-minute mark, and wait for all three services to show one PRIMARY deployment with `COMPLETED`. Then record the evidence, destroy and check the console.
