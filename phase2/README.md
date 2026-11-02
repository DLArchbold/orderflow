# Phase 2 (later)

Everything here is deliberately kept out of the way until Phase 1 (Postgres + Spring Boot API with Swagger UI + React) works.
Move things back when the matching step in `docs/PLAN.md` says so; don't pull in all of it at once.

| Folder | What it is | Used in |
|---|---|---|
| `order-service/` | Outbox/publisher code, security config, V2 migration, extra Maven dependencies, Dockerfile | Messaging, security, delivery steps |
| `inventory-service/` | Second Spring Boot service (move to `services/inventory-service`) | Messaging steps |
| `frontend/` | Live-status (SSE) hook, React Testing Library test, Playwright e2e | Frontend extras, testing steps |
| `infra/` | Full docker-compose (Kafka, Redis, Jaeger, Prometheus, Grafana), Kubernetes, Terraform, AWS notes | Messaging, observability, delivery steps |
| `github/` | CodeQL, Dependabot, full CI (move into `.github/`) | Delivery steps |
| `load-tests/` | k6 script | Load-test step |
| `docs/` | Architecture, ADR, security and performance notes | Documentation steps |
