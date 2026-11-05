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
| A06 Vulnerable components | Dependabot + CodeQL | pending step 22 (not yet backed by evidence) |

Not claimed: transport encryption. Everything runs over plain HTTP in development, and the ALB in step 24 serves HTTP only
until a domain and certificate exist.

## Scan findings fixed
List each real CodeQL / Dependabot finding you fixed (date, what, how). Count them honestly.

## Known exposure
- `/actuator/prometheus` of order-service is open without a token so Prometheus can scrape it. It must only be reachable
  inside the private network (step 23 NetworkPolicy / step 24 security group), never from the public ALB.
