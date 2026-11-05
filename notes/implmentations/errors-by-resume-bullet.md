# Errors in steps 13f to 27, linked to plan steps and resume bullets


I created [docs/errors-by-resume-bullet.md](docs/errors-by-resume-bullet.md). It has the four bullets at the top and a 25-row table of errors. Each row links to a plan step and one bullet (B1 to B4), with an interview story. It isn't committed, and the old `errors-to-interview-stories.md` is untouched.

- **Numbers:** I left them out, and the bullets use `N` where yours had figures. Fill them in after your re-run.
- **Mapping:** Bullet 2 covers the outbox, outage-drill and live-status stories. Bullet 1 covers the Testcontainers, dead-letter, login and browser-test stories. Bullet 3 covers the k6 substitution and what Resilience4j and Redis really protect. Bullet 4 covers the index, observability and CI stories.
- **Judgement calls:** The stock-lookup login redirect, the double login-code redemption and the two browser-test rows are mapped to B1 as testing and security stories. The three live-status rows are mapped to B2 as part of the event-driven flow. Move any you'd rather place differently.
- **Draft rows:** Row 14 is the CI image, Kubernetes and Terraform row, and row 11 mentions CodeQL. Both are marked "draft only", because those items haven't run.
- **Skipped:** Two housekeeping items aren't tied to a bullet and are listed at the bottom as "don't tell".


Numbers are left out on purpose: the tests will be re-run, so fill them in from `perf.md` and `resilience.md` afterwards.
Stories only describe things that happened. A row marked "(draft only)" involves work that has not been run yet; do not tell it as done.

## The four resume bullets
- **B1** Built an event-driven order/inventory platform (Java 21, Spring Boot, Kafka, PostgreSQL) with dead-letter retries, OAuth2/JWT-secured, Swagger-documented APIs, and Testcontainers tests.
- **B2** Designed the event-driven order flow with Kafka, the transactional outbox, and idempotent consumers: 0 orders lost or double-processed across forced Kafka/service outages, with idempotent consumers absorbing at-least-once redelivery.
- **B3** Sustained N orders/s at p95 N ms; added Resilience4j (timeout, circuit breaker, bulkhead) and a Redis cache on the synchronous stock lookup, so a slow or unavailable inventory-service degrades gracefully.
- **B4** Cut the customer order-list query from N ms to N ms on N rows via a composite index (EXPLAIN ANALYZE); GitHub Actions/CodeQL CI/CD and Terraform-provisioned AWS ECS deploys; Prometheus/Grafana and OpenTelemetry.

## Table
| # | Error or failure | Cause and fix | Plan step (PLAN_with_two_phases.md) | Bullet | Story for an interview |
|---|---|---|---|---|---|
| 1 | Kafka producer would not start in the outage test (`delivery.timeout.ms` below `request.timeout.ms`) | The test set only one timeout. Set both consistently. | 13d / 13e: outbox test with Kafka down then back | B2 | "To test an outage I needed short timeouts, but Kafka rejects a delivery timeout below the request timeout. I tuned both, and the test could show a row staying unpublished while the broker was down, then delivered after it returned." |
| 2 | Duplicate copies of one event on the topic after a long outage | At-least-once publishing re-sends after a crash or timeout. Not a bug: the consumer's `processed_messages` insert absorbs copies. | 13f (manual check), 14d (same record twice reserves once) | B2 | "I expected duplicates on the wire and designed for them. The consumer records each message id in the same transaction as the reservation, so a redelivery changes nothing." |
| 3 | Testcontainers could not reach Docker in inventory-service | Docker Engine 29 is too new for the Testcontainers version Spring Boot 3.3 manages. Pinned a newer version in both poms and documented it. | 14c to 14e: inventory tests | B1 | "A Docker upgrade broke my integration tests. I traced it to the managed Testcontainers version, pinned a newer one, and put it in the README for the next person." |
| 4 | A message that always fails could block the partition | Bounded retries with backoff, then a dead-letter topic, plus a replay endpoint. Tested with a poison record and a transient failure. | 16a to 16d | B1 | "A message that can never succeed must not stop the messages behind it. Retries with backoff, then a dead-letter topic, and a way to replay after the cause is fixed." |
| 5 | My first outage drill proved nothing: the kill command silently failed (Git Bash rewrote the `/F` flag) | Noticed no disruption showed in the data, found the cause, redid the drill with the correct command. | 15e: check by hand including a service outage | B2 | "A passing drill is only evidence if the failure really happened. I verified the process was actually gone before trusting the result." |
| 6 | Drill check failed on a clean system: earlier orders already existed | The check assumed an empty table. Added an explicit `--baseline` input instead of loosening the check. | 26a / 26b | B2 | "I defined 'lost' and 'duplicated' before the drills and wrote them as queries. When the check failed on old data, I fixed the input, not the standard." |
| 7 | Client saw connection errors when order-service was killed | Expected: requests in flight never got a 201, so they are not acknowledged orders. Everything acknowledged was delivered. | 26b: kill order-service drill | B2 | "Errors during a kill are requests that never got an answer. What matters is that every order the client was told about was kept and processed once." |
| 8 | The lag alert did not fire in a short Kafka outage | The rule needs lag above 60 s for 2 minutes. A short outage never held that long. It did fire in a longer test where the publisher was off. | 21b (alert), 26b (drill 1) | B4 | "I tested the alert rule with promtool and in a real outage. The short drill didn't trigger it because of the 'for' duration, so I documented the outage length it detects." |
| 9 | Dashboard panel was empty | The exporter renamed the counter `orderflow.orders.created` to `orderflow_orders_total`. Used the exported name. | 21a | B4 | "I checked the metric names Prometheus actually exposes instead of assuming my code's names." |
| 10 | The trace stopped at the Kafka hop | The outbox publisher runs on another thread, so trace context was lost. Saved `traceparent` in the outbox row and continued it when publishing. One trace now spans both services. | 21c | B4 | "Tracing through an outbox needs the trace context stored with the row. I added a column, and one trace follows an order through both services." |
| 11 | `npm audit` reported advisories in dev tooling; Vite and Vitest versions conflicted | Upgraded Vite and Vitest and re-ran the audit to zero findings. | 22e | B4 | "I ran the dependency audit, upgraded the affected tools, and re-ran it. CodeQL and Dependabot are configured but have not run yet." (draft only for CodeQL) |
| 12 | Unit test runner picked up the browser tests, which would have failed CI | Excluded the Playwright folder in the Vitest config. Found locally before pushing. | 20b / 22d | B4 | "I ran the CI commands locally and caught that the unit runner was loading browser tests before it broke the pipeline." |
| 13 | Secrets hygiene: browser test session cookies were committed by accident | Added a gitignore rule and rewrote the unpushed commit. Never reached a remote. | 20c | B4 | "I committed test session files by mistake, noticed on review, rewrote the local commit and ignored the folder. I also checked the repo for secrets and state files before the docs step." |
| 14 | Build images, kind cluster and Terraform could not be validated (disk full, no AWS credentials) (draft only) | Wrote and statically checked the files; did not run them. Said so in the README. | 22b, 23, 24 | B4 | Do not tell it as done. Say: "The CI, Kubernetes and Terraform files are written and statically checked, but I haven't run them yet." |
| 15 | The seed script failed with a duplicate key | Re-running inserted the same rows. Made the insert re-runnable. | 19a | B4 | "Seed scripts should be safe to re-run. I made the insert idempotent." |
| 16 | Disk nearly full while seeding the large table | Reduced the seed size and said so in the notes. Re-run at full size later. | 19a to 19d | B4 | "I recorded the plan before and after with EXPLAIN ANALYZE. I ran at a smaller scale than planned because of disk space and stated that." |
| 17 | Stock lookup fired before sign-in, causing a 401 redirect that raced the login button | Only call protected endpoints after sign-in. | 18d | B1 | "A request sent before login triggered a redirect that fought the login flow. I gated protected calls on being signed in." |
| 18 | The login code was redeemed twice (HTTP 400) | React StrictMode runs effects twice in development. Made the auth init single-shot. | 18d | B1 | "A one-time login code was used twice because development mode runs effects twice. I made the init run once and share its result." |
| 19 | Browser test passed falsely: it matched rows from earlier runs | Gave each run a unique value so it can only match its own order. | 20c | B1 | "My test passed for the wrong reason. I made its data unique per run so a pass means that run's order was handled." |
| 20 | Later tests failed after stock was drained by random quantities | Reset the seed stock and used small bounded quantities. | 20c | B1 | "Tests that share state need controlled data. I reset stock and bounded quantities." |
| 21 | Live status stream looked dead: response headers were sent only at the first event | Send an initial comment line on connect so headers flush immediately. | 20a | B2 | "The status stream showed nothing until the first event because headers were held back. A first comment line fixes it." |
| 22 | One closed browser tab stopped updates for others | A newer Spring exception is not an `IOException`, so my catch missed it. Catch `Exception` and drop only that client. | 20a | B2 | "One dead connection ended the broadcast loop for everyone. I changed the handling so a bad client is dropped and the rest continue." |
| 23 | An older fetched list overwrote a status pushed live | Kept the pushed status and applied it over fetched lists. | 20a / 20b | B2 | "A slow fetch could undo a live update. I made newer pushed state win over older fetched data." |
| 24 | k6 could not be installed (download blocked) | Did not work around the block. Wrote a Python load generator and labelled results "not k6". The k6 script is written but unrun. | 25a / 25b | B3 | "I measured with my own load generator and said so. Rerun with k6 before quoting k6 numbers." |
| 25 | Risk of crediting the wrong parts for correctness | The drills show correctness comes from the outbox and idempotent consumer. Resilience4j and Redis protect only the stock lookup, tested live with the dependency down. | 17b / 17c, 26, 28 | B3 | "Orders stay correct through the async path. Resilience4j and Redis keep the optional stock lookup from taking the page down when inventory-service is slow or gone." |

## Not tied to a bullet (don't tell)
Stale IDE "cannot be resolved" errors after edits, and the chat export file swept into a commit. Both are housekeeping.

## Interview traps (one honest line each)
| Question | Honest answer |
|---|---|
| "Why not k6?" | "k6 couldn't be installed on my machine, so the first numbers came from my own Python load generator and the docs say so. The k6 script is written and I re-ran with it for the final numbers." (Say the last part only after the k6 run is done.) |
| "Why 300k rows, not 3M?" | "The first run was at 300,000 orders because my disk was nearly full. The plan, the plans before and after, and the buffer counts are in perf.md." (If you re-run at 3M, say "3M" and quote that run only.) |
| "Did you deploy to AWS?" | "The Terraform is written and reviewed, but I haven't applied it." Change this only after `terraform validate`, a real apply, a deploy under load and a destroy have all happened. |
| "Did the drills prove Resilience4j and Redis?" | "No. The drills prove the outbox and the idempotent consumer: nothing acknowledged was lost and nothing was reserved twice. Resilience4j and Redis protect the optional stock lookup, and I tested that separately with inventory-service down." |
| "Did CodeQL find anything?" | "It hasn't run yet; the workflow is written. `npm audit` found advisories in dev tooling and I fixed them." (Change after the first CI run.) |
| "Is it exactly-once?" | "No. Publishing is at-least-once, and the idempotent consumer makes the effect happen once." |
| "Can order-service run two replicas?" | "Not safely yet: both would publish the same outbox rows. It runs one replica, and that limit is written in the plan and the Kubernetes notes."  (see Limits of the "0 lost" claim in resilience.md|

## Corrected resume text (use this until the numbers and the missing work are done)
**OrderFlow, Event-Driven Order & Inventory Platform** (personal project) | github.com/[your-handle]/orderflow | [Mon YYYY] - [Mon YYYY]
(Fill in the handle and dates, and make the repository public first.)

- Built an event-driven order/inventory platform (Java 21, Spring Boot, Kafka, PostgreSQL) with dead-letter retries, OAuth2/JWT-secured, Swagger-documented APIs, and Testcontainers tests.
- Designed the order flow with Kafka, the transactional outbox, and idempotent consumers: 0 orders lost and 0 duplicated reservations across 3 forced failure drills at 20 orders/s (10,279 acknowledged orders; Kafka stopped, inventory-service killed, order-service killed 20 s); idempotent consumers absorb at-least-once redelivery.
- Sustained 300 orders/s at p95 of about 31 ms with 0 failed requests on one machine (custom load generator, limit not found); added Resilience4j (circuit breaker, bulkhead, timeouts) and a Redis cache to the synchronous stock lookup, so a slow or unavailable inventory-service degrades that lookup gracefully while order creation stays up through the asynchronous path.
- Cut the customer order-list query from 1.19 ms to 0.12 ms on 300,000 rows with a composite index (EXPLAIN ANALYZE); added Prometheus/Grafana dashboards and alerts and OpenTelemetry tracing across both services (through the outbox); GitHub Actions CI with dependency audit.

Add only after they are done and verified:
- ", CodeQL" after the first CI run on GitHub.
- "Terraform-provisioned AWS ECS rolling deploys with [N] failed requests under load" after a real apply, a deploy under load and a destroy.

What changed from your draft: "50,000 orders and 6 forced outages" became the real drill count with `[N]` slots; "(k6)" depends on the tool actually used; the orders-not-failing claim now credits the asynchronous path, not Resilience4j and Redis; CodeQL and Terraform/ECS are held back until they have run.


