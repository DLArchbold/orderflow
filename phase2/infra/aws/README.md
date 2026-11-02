# AWS deployment notes (PLAN step 16)

Target: ECS Fargate, one service per container, behind an ALB, rolling deployment.

Checklist (fill in as you do it, keep only what you actually deployed):
- [ ] ECR repositories for order-service, inventory-service, frontend
- [ ] RDS PostgreSQL (small instance) with one database per service
- [ ] Kafka: choose ONE (MSK Serverless, or a single Redpanda/Kafka container) and note the choice and cost
- [ ] ECS task definitions with health checks on /actuator/health/readiness
- [ ] ALB target groups; deployment config minimumHealthyPercent=100, maximumPercent=200
- [ ] Secrets in Secrets Manager / SSM (never in the repo)
- [ ] Proof of a rolling deploy with no failed requests (k6 running during deploy)
- [ ] Tear down afterwards to stop cost; keep screenshots/logs as evidence

Do not describe this as "zero-downtime" until the deploy-under-load proof exists.
