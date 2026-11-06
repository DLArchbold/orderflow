# CI pipeline

Defined in `.github/workflows/ci.yml` (plus `codeql.yml`; Dependabot is configured in `.github/dependabot.yml`).

| Job | Runs on | What it does |
|---|---|---|
| `backend` (matrix: order-service, inventory-service) | every push and pull request | `mvn -B verify`. The Testcontainers tests (PostgreSQL, Kafka, Redis) run unchanged because `ubuntu-latest` has Docker. |
| `frontend` | every push and pull request | `npm ci`, `npm test` (Vitest + React Testing Library), `npm run build` (type-check + Vite). |
| `images` | push to `main`, after `backend` and `frontend` pass | Builds the three images and pushes them to `ghcr.io/<owner>/orderflow-<name>` tagged with the commit SHA (and `latest`). |
| `e2e` | every push and pull request, after `backend` and `frontend` pass | Builds the images from source with `docker compose --profile apps up -d --wait --build`, then runs `mvn -f e2e/pom.xml verify` (create an order, wait for CONFIRMED; an order above the stock becomes REJECTED). On `main` only, it also runs the Playwright browser tests. On failure it uploads `compose-logs.txt` and the Playwright report. |
| `codeql` | push to `main`, pull requests, weekly | CodeQL analysis for Java and TypeScript. |

## Why Playwright runs on `main` only
It needs a browser download and takes about three times as long as the HTTP end-to-end test, and a browser test is the most
likely one to be flaky. The HTTP test already covers the path through both services, Kafka and both databases on every push,
so a pull request is not slowed down for the one thing only the browser can show (login redirect, live status in the page).
Run it locally with `cd frontend && npx playwright test` (see the README quick-start).

## Not yet verified
This pipeline was written and its commands were run locally, but it has not been run on GitHub. What did run locally (2026-11-05, Windows 10,
Docker Desktop): the three images were built, `docker compose -f phase2/infra/docker-compose.full.yml --profile apps up -d --wait --build` brought the
whole system up healthy (also from a fresh clone of the repository), and `mvn -f e2e/pom.xml verify` (2 tests) and `npx playwright test` (4 tests, with
`BASE_URL=http://localhost:5173`) passed against those containers. Not verified: any of it on a GitHub runner (the first run is the first real test of the
`images` and `e2e` jobs, the GHCR push, the `docker/build-push-action` caching, and Chromium instead of Edge for Playwright), and the `kind` job in step 23
(the same manifests were applied to a local kind cluster and both Deployments became Available, but not through `helm/kind-action`).
