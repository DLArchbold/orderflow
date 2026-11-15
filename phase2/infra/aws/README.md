# AWS deployment notes (PLAN step 16)

Target: ECS Fargate, one service per container, behind an ALB, rolling deployment.

Checklist (fill in as you do it, keep only what you actually deployed). Updated 2026-11-15; the Terraform is in `phase2/infra/terraform`, decisions in `phase2/docs/adr/0006-aws-choices.md`:
- [x] ECR repositories for order-service, inventory-service, frontend
- [x] RDS PostgreSQL (small instance) with one database per service (`db.t4g.micro`; `orders` and `inventory` created by the one-off `db-init` task)
- [x] Kafka: choose ONE (MSK Serverless, or a single Redpanda/Kafka container) and note the choice and cost (one `apache/kafka` container on ECS; cost reasoning in ADR 0006)
- [x] ECS task definitions with health checks on /actuator/health/readiness (the order-service target group checks that path)
- [x] ALB target groups; deployment config minimumHealthyPercent=100, maximumPercent=200 (application services; Kafka and Keycloak use 0 / 100)
- [x] Secrets in Secrets Manager / SSM (never in the repo) (DB and Keycloak secrets injected with `valueFrom`)
- [x] Proof of a rolling deploy with no failed requests (k6 running during deploy): 2026-11-14, 0 failed of 12,003 at 25 orders/s for 8 minutes, `phase2/docs/aws-deploy-under-load.md`
- [x] Tear down afterwards to stop cost; keep screenshots/logs as evidence (evidence committed in `phase2/docs/k6-runs/aws-deploy/`; `terraform destroy` run on 2026-11-15 and the console checked for leftovers; the budget alert was kept)

Do not describe this as "zero-downtime" until the deploy-under-load proof exists. Update 2026-11-14: the proof now exists for one run at one rate of a trivial change, which still does not support "zero-downtime"; use the wording in `phase2/docs/aws-deploy-under-load.md`, "How to state this result".
