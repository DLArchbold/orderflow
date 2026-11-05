# OrderFlow

An order-management system built to show event-driven design done carefully: a **React + TypeScript** UI, two **Java 21 / Spring Boot** services
(`order-service`, `inventory-service`) that talk through **Kafka** with the **transactional outbox** pattern and **idempotent consumers**,
**PostgreSQL** with Flyway, **OAuth2/JWT** login through Keycloak, **Redis** cache with **Resilience4j**, and metrics, tracing and alerts
(Prometheus, Grafana, Jaeger).

```
browser ──REST + SSE──> order-service ──(same transaction)──> orders DB: orders + outbox
                             │ OutboxPublisher                         
                             v                                         
                       Kafka: orders.events ──> inventory-service ──(same transaction)──> inventory DB: stock + processed_messages + outbox
                             ^                                │ OutboxPublisher
                             └──── inventory.results <────────┘        (retries + dead-letter topics on both consumers)
```

An order starts `PENDING`; inventory-service reserves stock (all lines or none, once per message id) and answers `RESERVED` or `REJECTED`; order-service moves
the order to `CONFIRMED` or `REJECTED` and pushes the change to the browser. Details: [architecture](phase2/docs/architecture.md), decisions in [`phase2/docs/adr/`](phase2/docs/adr).

## Results (read the "setup / evidence" column: some numbers are from a stand-in tool or a reduced scale)
| Metric | Result | Setup / evidence |
|---|---|---|
| Throughput (orders/s) | **At least 300 orders/s accepted with 0 failures; the limit was not found** | One i7-14700 machine running the load generator and the whole system together; 30 s per step. Measured with `scripts/load_orders.py`, **not k6** (k6 could not be installed). [perf.md](phase2/docs/perf.md#load-test-plan-step-25) |
| p95 create-order latency | 32 to 57 ms at 50 orders/s; about 31 ms at 100 to 300 orders/s | same runs |
| Outbox lag at the top rate | peak 26 s at 300 orders/s, drained 26 s after the step ended; near zero at 150/s or less | same runs |
| Query time before/after index | **about 60 ms to 0.2 ms** (19,252 to 53 buffers) for "my newest 50 orders" at **3,000,000 orders** (a customer with 30,031 orders) | [perf.md](phase2/docs/perf.md), `scripts/seed-orders.sql` |
| Failed requests during rolling deploy | **not measured** | the AWS deploy was never run (no AWS account used); [Terraform](phase2/infra/terraform/README.md) is an unvalidated draft |
| Orders lost / duplicated after failure drills | **0 / 0** in three drills at 20 orders/s (Kafka stopped 60 s, inventory-service killed 60 s, order-service killed) | [resilience.md](phase2/docs/resilience.md), `scripts/drill.sh`, `scripts/drill_check.py` |
| Security scan findings fixed | 3 advisory groups from `npm audit` (all in dev tooling), now 0 vulnerabilities. CodeQL and Dependabot have **not run yet** (nothing pushed) | [security.md](phase2/docs/security.md) |

What is not done, so nobody has to guess: pushing to GitHub and the first CI run; building the Docker images and `docker compose --profile apps`; the kind cluster;
everything on AWS; the demo GIF; the k6 runs. The files for all of them are in the repository and are marked as untested where they are.

## Quick start

Prerequisites: JDK 21, Maven, Node 22, Docker Desktop (or Docker Engine + the compose plugin). Give Docker about 6 GB of memory.

```bash
# 1. Infrastructure: Postgres, Kafka, Redis, Keycloak, Jaeger, Prometheus, Grafana
docker compose -f phase2/infra/docker-compose.full.yml up -d --wait

# 2. The two services, each in its own terminal (inventory-service is on :8081, order-service on :8080)
cd services/inventory-service && mvn spring-boot:run
cd services/order-service && mvn spring-boot:run

# 3. The UI
cd frontend && npm ci && npm run dev          # http://localhost:5173
```

Log in at http://localhost:5173 as `alice` / `alice-dev-pw`, `bob` / `bob-dev-pw` (customers) or `ada` / `ada-dev-pw` (admin). Throwaway development passwords.

| What | Where |
|---|---|
| Swagger UI (press Authorize with a token) | http://localhost:8080/swagger-ui.html |
| Keycloak admin | http://localhost:8180 (admin / admin) |
| Grafana (OrderFlow dashboard) | http://localhost:3000 (admin / admin) |
| Prometheus, Jaeger | http://localhost:9090, http://localhost:16686 |

A token for curl: `curl -s -d client_id=orderflow-cli -d grant_type=password -d username=alice -d password=alice-dev-pw http://localhost:8180/realms/orderflow/protocol/openid-connect/token`

### Differences between Windows and Linux/macOS
- **Windows (Git Bash)**: put `MSYS_NO_PATHCONV=1` in front of `docker compose exec ... /opt/kafka/...` commands, otherwise Git Bash rewrites the path. To stop a
  service started with `mvn spring-boot:run`, find the PID with `netstat -ano | findstr :8080` and run `taskkill /F /PID <pid> /T` (in Git Bash write it as `taskkill /F ...` with `MSYS_NO_PATHCONV=1`).
- **Linux/macOS**: no path rewriting; stop a service with `kill $(lsof -ti :8080)`. Docker Engine needs your user in the `docker` group for Testcontainers.
- **Docker Engine 29 or newer**: the Testcontainers version Spring Boot 3.3 manages is too old for it; both services pin `testcontainers.version` 1.21.4 in their `pom.xml`.

### Tests
```bash
cd services/order-service && mvn verify        # unit + Testcontainers (PostgreSQL, Kafka, Redis); Docker must be running
cd services/inventory-service && mvn verify
cd frontend && npm test                         # Vitest + React Testing Library
cd frontend && npx playwright test              # browser tests; needs the stack and both services running (uses installed Edge locally, Chromium in CI)
cd e2e && mvn verify                            # HTTP end-to-end through both services
promtool: docker compose -f phase2/infra/docker-compose.full.yml exec prometheus promtool test rules /etc/prometheus/alerts_test.yml
```

### Failure drills and load
`python scripts/load_orders.py --rate 50 --duration 60` (a stand-in for the k6 script in `phase2/load-tests/k6/`), `scripts/perf_run.sh <dir>`, `scripts/drill.sh kafka|inventory|order <dir>`.
Pipeline and CI: [docs/ci.md](docs/ci.md). Build plan and "done when" checks: [PLAN_with_two_phases.md](PLAN_with_two_phases.md).

## Layout
| Path | What |
|---|---|
| `services/order-service` | orders API, outbox publisher, inventory-result consumer, SSE stream, stock lookup with circuit breaker and Redis cache |
| `services/inventory-service` | stock reservation (idempotent, all-or-nothing), its own outbox, dead-letter replay, stock endpoint |
| `frontend` | React + TypeScript (Vite), OIDC login with PKCE, live status; Vitest and Playwright tests |
| `e2e` | HTTP end-to-end tests against the running stack |
| `phase2/infra` | docker-compose, Keycloak realm, Prometheus rules, Grafana dashboard, Kubernetes manifests, Terraform draft |
| `phase2/docs` | architecture, ADRs 0001 to 0006, security, performance, resilience |
| `scripts` | seed data, load generator, drill and check scripts |
