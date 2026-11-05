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
This pipeline was written and its commands were run locally, but it has not been run on GitHub. The Docker images and the
compose `apps` profile were validated (`docker compose config`, `docker build --check`) but not built, because the machine
had under 1 GB of free disk. The first run on GitHub is the first real test of the `images` and `e2e` jobs.
