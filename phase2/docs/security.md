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
| Risk | What this project does | Evidence (test / config) |
|---|---|---|
| A01 Broken access control | Customers can only read their own orders | _TBD_ |
| A02 Cryptographic failures | _TBD_ | |
| A03 Injection | Parameterized queries via JPA | _TBD_ |
| A05 Security misconfiguration | _TBD_ | |
| A06 Vulnerable components | Dependabot + CodeQL | _TBD_ |
| A07 Identification/authentication failures | OAuth2/JWT resource server | _TBD_ |

## Scan findings fixed
List each real CodeQL / Dependabot finding you fixed (date, what, how). Count them honestly.
