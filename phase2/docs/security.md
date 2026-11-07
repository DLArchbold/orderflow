# Security notes

Fill in as you build (PLAN step 18). Record only what is true.

## Decisions
- **inventory-service is unauthenticated and internal.** It is only called by order-service (`GET /stock/{sku}`) and
  by Kafka. In step 24 a security group restricts it to order-service. Its `/admin/dlt/replay` endpoint has no login
  either, so it is off unless `orderflow.admin.enabled=true`.
- **The owner of an order is the token's `sub`**, never a value from the request body (`OrderOwnershipTest`).
- **403, not 404, for someone else's order.** 404 would not reveal that the order exists, which is the safer
  answer against someone probing ids, but order ids are random UUIDs and a clear 403 is easier to debug and to show
  in the UI ("You are not allowed to see this order"). If ids ever become guessable, switch to 404.
- Old test rows with free-text customer ids stay invisible to customers (only an admin sees them).

## OWASP Top 10 mapping
Only rows that have evidence are listed.

| Risk | What this project does | Evidence (test / config) |
|---|---|---|
| A01 Broken access control | The owner is the token's `sub`, never the request body; a customer lists and reads only their own orders, another customer gets 403, an admin sees all | `OrderOwnershipTest` (4 tests) |
| A03 Injection | Only bind parameters, no SQL built from input: JPA derived queries in order-service, `JdbcClient` with `?` / named parameters in inventory-service | `InventoryService.reserve` (`ON CONFLICT`, `WHERE sku IN (:skus)`), `OrderRepository.findByCustomerId` |
| A05 Security misconfiguration | Stateless sessions; CSRF off only because the API uses bearer tokens and no cookies; CORS allows the frontend origin only; actuator exposes `health,info,circuitbreakers`; the replay endpoint is off unless `orderflow.admin.enabled=true` | `SecurityTest.preflightFromAnotherOriginIsRejected`, `SecurityConfig`, `application.yml` (`management.endpoints`) |
| A07 Identification and authentication failures | OAuth2 resource server validating the JWT signature and issuer against Keycloak; no token or a bad token is 401; the browser keeps the token in memory (PKCE login) | `SecurityTest.listWithoutTokenIs401`, `SecurityConfig`, `frontend/src/auth.ts` |
| A06 Vulnerable components | `npm audit` is clean (0 vulnerabilities, see "Scan findings fixed"); CodeQL ran on PR #1 with 0 results in both languages (default query suites only, not proof of safety); Dependabot alerts are enabled and 9 are open on `main` (vite, esbuild), expected to close when PR #1 is merged | `npm audit` output, `.github/dependabot.yml`, `.github/workflows/codeql.yml` |

Not claimed: transport encryption. Everything runs over plain HTTP in development, and the ALB in step 24 serves HTTP only
until a domain and certificate exist.

## Scan findings fixed
Counted honestly. CodeQL found nothing (0 results in `java-kotlin` and `javascript-typescript` on PR #1). Dependabot reports 9 open alerts on `main` (vite 8, esbuild 1) that the branch's upgrade already addresses; they are not counted as fixed until they close after the merge. The findings below come from `npm audit` run locally.

| Date | Finding | Where | Fix |
|---|---|---|---|
| 2026-11-05 | `tinypool` <= 2.1.1, prototype pollution gadget that can lead to remote code execution (GHSA-5gmw-xhrv-c9v3, GHSA-85c8-ppgw-ccpr), rated critical | dev dependency (via `vitest`), not in the shipped bundle | upgraded `vitest` 2.1.9 to 5.1.0 |
| 2026-11-05 | `esbuild` <= 0.24.2, any website can send requests to the dev server and read the response (GHSA-67mh-4wv8-2f99), rated moderate | dev dependency (via `vite` 5), the Vite dev server only | upgraded `vite` 5.4 to 7.3.7 |
| 2026-11-05 | `@vitest/mocker` <= 4.1.11, path traversal / code injection through mocks (GHSA-82fw-gwwq-j7x9, GHSA-qxg6-89mg-rqv8), rated moderate | dev dependency (via `vitest`) | same `vitest` upgrade |

Result: `npm audit` reports 0 vulnerabilities; `npm test` (7 tests), `npm run build` and the 4 Playwright tests pass on the new versions.
Total fixed: 3 advisory groups (4 advisories plus the dependencies they pulled in). All three were in development tooling.

Not scanned: the Maven dependencies (no scanner is installed here); Dependabot will report them once its config is on `main` (after PR #1 is merged).
