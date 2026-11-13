You can reuse the procedure from your step 3 notes, with one catch: every image in Terraform uses the same `image_tag`, so the frontend needs the new tag too, even though its code didn't change.

Run these in PowerShell from the repo root.

**1. Commit the log changes first**

The tag is the commit SHA, so an uncommitted tree would give you an image that doesn't match its tag. `ecs_services.tf` also has uncommitted changes, which the apply will pick up. Check that you want them.

```powershell
cd "C:\Users\WJ\Desktop\Work 2023-05-12\Project\orderflow"
git add services/order-service services/inventory-service
git commit -m "Add INFO logs to the order flow"
$tag = git rev-parse --short HEAD
$tag
```

**2. Log in to ECR**

The 12-hour token from your last session has probably expired. Reuse your working throwaway-config approach:

```powershell
$tf = ".\phase2\infra\terraform\"
$env:DOCKER_CONFIG = Join-Path $env:TEMP "docker-ecr"
New-Item -ItemType Directory -Force $env:DOCKER_CONFIG | Out-Null
Set-Content (Join-Path $env:DOCKER_CONFIG "config.json") '{"auths":{"example.invalid":{}}}' -Encoding ascii

$pw = aws ecr get-login-password --region us-east-1
docker login --username AWS --password $pw 702623019395.dkr.ecr.us-east-1.amazonaws.com
```

If `aws sts get-caller-identity` fails, run `aws sso login` first.

**3. Build and push all three images**

```powershell
$reg = (terraform "-chdir=$tf" output -json ecr_repositories | ConvertFrom-Json)

docker build --platform linux/amd64 -t "$($reg.'order-service'):$tag" services/order-service
docker build --platform linux/amd64 -t "$($reg.'inventory-service'):$tag" services/inventory-service
docker build --platform linux/amd64 -t "$($reg.'frontend'):$tag" frontend

docker push "$($reg.'order-service'):$tag"
docker push "$($reg.'inventory-service'):$tag"
docker push "$($reg.'frontend'):$tag"
```

The frontend build should be fast because Docker reuses its cached layers. If you skip the frontend, its ECS task fails to pull `:$tag` and the deployment rolls back.

**4. Verify the tag is in ECR**

```powershell
aws ecr describe-images --repository-name orderflow-order-service --region us-east-1 --query "imageDetails[].imageTags"
aws ecr describe-images --repository-name orderflow-inventory-service --region us-east-1 --query "imageDetails[].imageTags"
aws ecr describe-images --repository-name orderflow-frontend --region us-east-1 --query "imageDetails[].imageTags"
```

Each repository should list `$tag`.

**5. Apply**

```powershell
terraform "-chdir=$tf" plan -var "image_tag=$tag"
terraform "-chdir=$tf" apply -var "image_tag=$tag"
```

Read the plan first. It should show only the task definitions and services being replaced, with image tags changing. Kafka and Keycloak use a stop-then-start deployment (0/100), so they restart briefly. Kafka has no volume, so its topics are lost on restart (ADR 0006).

**6. Check it worked**

1. In the ECS console, wait for the services to show 1/1 running on the new task definitions.
2. Create an order in the app.
3. Open the CloudWatch log groups for order-service and inventory-service and look for the "Order … created", "Stock reserved", and "… is now CONFIRMED" lines.
(if logs are not found, see ## What happened on 2026-11-11 (the new log lines did not appear) below)

**7. Clean up when you're done**

```powershell
Remove-Item Env:DOCKER_CONFIG
Remove-Item -Recurse -Force (Join-Path $env:TEMP "docker-ecr")
```

If the tag stays the same and you only want a redeploy, use `aws ecs update-service --force-new-deployment` instead. A new tag is cleaner because the task definition shows which commit is running.

---

## What happened on 2026-11-11 (the new log lines did not appear)

The INFO lines were committed (`2e552fa`), the images were pushed awith tag: `9ff11d1`, and `terraform apply -var "image_tag=9ff11d1"` was run. CloudWatch still showed only the Redis `WARN` lines, and "Order … created" was not found.

**How to find out which image is running.** In the console: ECS, cluster, Services, `order-service`, Configuration, "Task definition: revision", then Containers, Image URI. Or `aws ecs describe-services --cluster orderflow --services order-service --region us-east-1 --query "services[0].deployments"`. A revision marked **Inactive** has been replaced; look at the live one. Here the revision opened was `orderflow-order-service:2` (Inactive, image `:3167fa7`, from before the log commit), so the new image was not what was serving.

**What was wrong.**
- The Events tab, searched for "Stopped", showed `service order-service has stopped 1 running tasks` at 14:04, 14:08 and 14:12 (UTC-5): one new task stopped about every 4 minutes.
- The log stream of a stopped task showed `Started OrderServiceApplication in 116.396 seconds`, a single request reaching Tomcat (the ALB health probe), then a normal SIGTERM shutdown (`ionShutdownHook`, Kafka consumer leaving the group, Hikari closing), with no error. ECS stopped it.
- Cause (likely, not yet confirmed with the task's **Stopped reason**): order-service ran on 0.5 vCPU with `health_check_grace_period_seconds = 120`. Spring needed about 2 minutes to start and the ALB needs several passing checks afterwards, so the grace period ran out first and ECS killed each new task as unhealthy. With `deployment_minimum_healthy_percent = 100` the old task kept serving, and it has no INFO lines.
- Confirm with: the stopped task's **Stopped reason** (expect "Task failed ELB health checks in (target-group …)"), the service's **Deployments** tab, and the target group's target health.

**What was changed** in `phase2/infra/terraform/ecs_services.tf` (`terraform validate` passes; not yet planned or applied):
- order-service `cpu` 512 to 1024 and `memory` 1024 to 2048 (a valid Fargate pairing; costs about double for that task).
- order-service `health_check_grace_period_seconds` 120 to 300.
- ADR 0006 (compute row) records the new sizing and the reason.

**Why `-var "image_tag=9ff11d1"` is still needed after this commit.** `image_tag` is the tag of the images in ECR, not "the current commit". The new commit only changes a `.tf` file, so the pushed images stay `9ff11d1`. Leaving out `-var` falls back to the default, `latest`, which was never pushed, so ECS cannot pull it. Passing the SHA of the new commit fails the same way, because no image has that tag. Only rebuild and re-tag when the application code changes. To avoid retyping, put `image_tag = "9ff11d1"` in `terraform.tfvars` (git-ignored) and update it when you push new images.

**Next steps.**
1. `terraform "-chdir=$tf" plan -var "image_tag=9ff11d1"`: expect a new order-service task definition and the service updating.
2. `terraform "-chdir=$tf" apply -var "image_tag=9ff11d1"`.
3. Watch the service's **Deployments** tab for about 2 to 5 minutes. The new task should become healthy and the old one should stop.
4. Create an order and look for "Order … created", "Stock reserved" and "… is now CONFIRMED" in the log groups (clear any filter and use the newest log stream).

**Result.** Not recorded yet: add whether the new task became healthy and whether the INFO lines appeared.

**Update 2026-11-13.** The services ran with image tag `9ff11d1` and were healthy when the 24c preparation started (all five services 1/1, all target groups healthy, an order reached CONFIRMED). See [step_7_1_deploy_under_load_prep.md](step_7_1_deploy_under_load_prep.md).
