# Tests listed under "What ran and passed"

Names are the real method or test names from the repository. Counts come from the source: 40 + 11 + 7 + 4 + 2 (e2e) + 3 promtool cases.
Note: the summary said 12 tests in inventory-service; the source holds 11, so use 11.
Updated 2026-11-05 (steps 1 to 9): see the last section for what was re-run against the Docker containers and what was not re-run.

## order-service (40, JUnit; Testcontainers marked TC)
| Class | Test | Purpose |
|---|---|---|
| `OrderTest` | `startsPending` | A new order begins as PENDING. |
| | `pendingCanBeConfirmed` | PENDING to CONFIRMED is allowed. |
| | `confirmedCannotBeCancelled` | A confirmed order cannot be cancelled. |
| | `pendingCanBeRejected` | PENDING to REJECTED is allowed (step 15). |
| | `confirmedRejectedAndCancelledCannotBeRejected` | Reject is only valid from PENDING. |
| | `rejectedIsTerminal` | A rejected order cannot move again. |
| | `cannotConfirmTwice` | Confirming twice is an illegal transition. |
| | `rejectsEmptyOrBadLines` | An order needs valid lines (SKU, quantity). |
| `OrderServiceOutboxTest` (TC) | `commitWritesOrderAndOutboxRow` | A successful create writes the order and its outbox row together. |
| | `rollbackLeavesNeitherOrderNorOutboxRow` | A failed create leaves neither, which is the point of the outbox. |
| `OutboxPublisherKafkaTest` (TC) | `eventArrivesExactlyOnceAfterCommit` | A committed row reaches Kafka once and is marked published. |
| | `unpublishedRowIsRetriedAndDeliveredAfterKafkaReturns` | With Kafka down the row stays unpublished, then is delivered when it returns. |
| | `theCorrelationIdTravelsInAKafkaHeader` | The correlation id is carried in a Kafka header. |
| `InventoryResultHandlerTest` (TC) | `reservedResultConfirmsAPendingOrder` | RESERVED moves a PENDING order to CONFIRMED. |
| | `rejectedResultRejectsAPendingOrder` | REJECTED moves it to REJECTED. |
| | `theSameResultAppliedTwiceLeavesTheSameState` | Re-applying a result is harmless (redelivery). |
| | `aResultForAnUnknownOrderIsIgnored` | An unknown order id does not crash or create anything. |
| | `aResultForACancelledOrderIsIgnored` | A late result cannot revive a cancelled order. |
| `StockClientTest` | `readsStockFromInventoryService` | The HTTP client parses inventory-service's stock response. |
| `StockClientResilienceTest` (TC) | `fiveFailuresOpenTheBreakerAndTheFallbackIsServedWithoutCallingInventory` | After repeated failures the circuit breaker opens and a fallback answers without calling inventory-service. |
| | `afterTheWaitASucceedingCallClosesTheBreakerAgain` | After the wait, a successful call closes the breaker. |
| `StockServiceCacheTest` (TC) | `twoReadsCauseOneCallToInventory` | The Redis cache serves the second read. |
| | `aFailureIsNotCached` | An error response is not stored in the cache. |
| `SecurityTest` (TC) | `listWithoutTokenIs401` | No token means 401. |
| | `listWithATokenIs200` | A valid token reaches the API. |
| | `documentationAndHealthNeedNoToken` | Swagger docs and health stay public. |
| | `preflightFromTheFrontendOriginIsAllowed` | CORS accepts the frontend origin. |
| | `preflightFromAnotherOriginIsRejected` | CORS rejects other origins. |
| `OrderOwnershipTest` (TC) | `theOwnerIsTheTokenSubjectEvenIfTheBodyNamesSomeoneElse` | The order owner is the token's `sub`, not the request body. |
| | `aCustomerListsOnlyTheirOwnOrders` | A customer's list contains only their orders. |
| | `anotherCustomerGets403OnGetAndCancel` | Someone else's order gives 403 on read and cancel. |
| | `anAdminSeesAndReadsEverything` | The admin role can see all orders. |
| | `theListIsPagedNewestFirst` | The list is paged, newest first (what the index serves). |
| `OrderStreamTest` (TC) | `theOwnerReceivesTheStatusChangeAndAnotherUserDoesNot` | SSE pushes a status change to the owner only. |
| `MetricsTest` (TC) | `theOrdersCreatedCounterIncrementsAfterOneCreate` | The orders-created counter goes up after a create. |
| | `theOutboxLagIsZeroWithNothingUnpublished` | Lag gauge is 0 when the outbox is empty. |
| | `theOutboxLagIsAboutTheAgeOfTheOldestUnpublishedRow` | Lag gauge equals the age of the oldest unpublished row. |
| `CorrelationIdFilterTest` (TC) | `aGivenCorrelationIdIsReturned` | A client-supplied id is echoed in the response. |
| | `oneIsGeneratedWhenTheClientSendsNone` | A missing id is generated. |
| | `anUnsafeValueIsReplacedNotEchoed` | A malicious value is replaced, protecting the logs. |

## inventory-service (11, JUnit + Testcontainers)
| Class | Test | Purpose |
|---|---|---|
| `InventoryServiceTest` | `reservingMovesStockAndWritesOneRowOfEachKind` | A reservation moves stock and writes one `processed_messages` row and one outbox row. |
| | `sameMessageIdTwiceReservesOnce` | The same message id reserves stock once (idempotency). |
| | `insufficientStockIsRejectedAndTheMessageIdIsStillRecorded` | Not enough stock means REJECTED, and the id is still recorded. |
| | `aFailingLineLeavesEveryLineUntouched` | All-or-nothing: one failing line changes no stock. |
| | `twoThreadsReservingTheLastUnitGiveExactlyOneReservation` | Concurrency: only one thread gets the last unit. |
| `OrderEventsListenerKafkaTest` | `theSameRecordDeliveredTwiceReservesStockOnce` | The same Kafka record delivered twice reserves once. |
| `ConsumerFailureKafkaTest` | `aPoisonMessageGoesToTheDltAndDoesNotBlockTheOrdersBehindIt` | A bad message goes to the dead-letter topic and later orders still process. |
| | `aTransientFailureIsRetriedAndNothingReachesTheDlt` | A temporary failure is retried and succeeds without dead-lettering. |
| `DeadLetterReplayKafkaTest` | `afterTheFixAReplayedDeadLetterReservesTheOrderExactlyOnce` | Replaying a dead letter after the fix reserves once. |
| `StockControllerTest` | `returnsAvailableAndReserved` | The stock endpoint returns available and reserved. |
| | `unknownSkuIs404` | An unknown SKU gives 404. |

## frontend: Vitest + React Testing Library (7, `src/App.test.tsx`)
| Test | Purpose |
|---|---|
| submits the typed SKU and quantity to createOrder | The form sends the typed values to the API call. |
| lists the orders with their status | Fetched orders render with status. |
| shows a rejected createOrder in the alert paragraph | An API error is shown to the user. |
| shows the permission message on a 403 | A 403 gives the permission message. |
| changes a row when a status event arrives | A live status event updates the row. |
| refetches the list when the stream reconnects | After an SSE reconnect the list reloads (missed events). |
| keeps a pushed status when an older list arrives afterwards | A slow fetch cannot overwrite a newer pushed status. |

## frontend: Playwright (4, run in Edge locally, needs the stack running; re-run 2026-11-05 against the compose containers, twice: 4 passed each time, see the last section)
| Spec | Test | Purpose |
|---|---|---|
| `smoke.spec.ts` | home page loads and offers a login | The app loads and shows a login button. |
| `orders.spec.ts` | alice creates an order and sees it become CONFIRMED without refreshing | Full flow with real Keycloak login, Kafka and live status. |
| | an order for more than the stock becomes REJECTED | The REJECTED path through inventory-service. |
| | bob's page does not show alice's orders | Orders are isolated per user in the browser. |

## e2e: HTTP end to end (2, `OrderFlowIT`; re-run 2026-11-05 against the compose containers, twice: 2 passed each time)
| Test | Purpose |
|---|---|
| `anOrderThatFitsInStockBecomesConfirmed` | Create via the API with a Keycloak token; polls until CONFIRMED. |
| `anOrderLargerThanTheStockBecomesRejected` | Same flow ends REJECTED. |

## Prometheus rule tests (promtool, `phase2/infra/prometheus/alerts_test.yml`, 3 cases)
| Case | Purpose |
|---|---|
| `OutboxLagHigh` at 1 m and 3 m | The alert stays quiet, then is only pending, while lag has been above 60 s for less than 2 minutes. |
| `OutboxLagHigh` at 4 m | It fires once lag has been above 60 s for 2 minutes. |
| lag recovers (eval at 6 m) | The alert clears after lag drops. |
| `DeadLetterQueueGrowing` at 3 m | One dead-lettered message fires the DLT alert. |

## Checks that are scripts, not tests
`scripts/drill_check.py` (with `drill-check.sql`) runs after each failure drill and prints PASS or FAIL for: no lost orders, nothing PENDING after the drain, no unpublished outbox rows,
no order with more than one `OrderCreated` or result row, stock never negative, `available + reserved` unchanged, and reserved quantity equal to CONFIRMED quantity per SKU.

## Runs on 2026-11-05 (steps 1 to 9 of the status log in `PLAN_with_two_phases.md`)
Windows 10, Docker Desktop, the three services as containers (`docker compose -f phase2/infra/docker-compose.full.yml --profile apps up -d --wait --build`). Nothing here ran on GitHub.

| What ran | Result | Where it is recorded |
|---|---|---|
| Playwright (4 tests) with `BASE_URL=http://localhost:5173`, against the containers | 4 passed (9.6 s) | PLAN status log, step 2 |
| `mvn -f e2e/pom.xml verify` (`OrderFlowIT`, 2 tests), against the containers | 2 passed | PLAN status log, step 2 |
| The same Playwright and e2e tests from a fresh `git clone`, following only the README | 4 passed and 2 passed | PLAN status log, step 3 |
| `scripts/drill.sh` for Kafka stopped 60 s, inventory-service killed 60 s, order-service killed, at 20 orders/s for 180 s (second run of each, containers) | `drill_check.py`: ALL CHECKS PASS in all three; 0 lost, 0 duplicated reservations | `phase2/docs/resilience.md` |
| `scripts/drill_lag_alert.py`: Kafka stopped 244 s at 10 orders/s | 3,600 orders, ALL CHECKS PASS; `OutboxLagHigh` Pending at +88 s, Firing at +208 s (read from Prometheus afterwards, the script's own sampler had a bug) | `phase2/docs/resilience.md`, drill 4 |
| Index test at 3,000,000 orders (before and after `idx_orders_customer_created`) | about 60 ms to 0.2 ms; about 19,150 page reads to 53 | `phase2/docs/perf.md` |
| `scripts/perf_run.sh` with `RAMP="100 150 200 300 400 600 800 1000"` | 0 failures up to 400 orders/s; 600/s broke the p95 threshold (1.33 s); not a test with a pass or fail, a measurement | `phase2/docs/perf.md` |
| kind cluster: `kubectl apply -k phase2/infra/k8s`, `kubectl wait --for=condition=Available deployment --all` | 4 Deployments Available in about 30 s, 0 restarts after the init-container fix; `/actuator/health` UP on both services, Flyway applied in both databases | `phase2/infra/k8s/README.md` |

**Not re-run in this pass:** the 40 order-service and 11 inventory-service JUnit tests (`mvn verify`), the 7 Vitest tests and the promtool rule tests. The counts above are
still from the source and from the earlier runs. Also not run: the CI pipeline on GitHub, `terraform validate`, and any k6 script.

Drill check, added to the list of script checks: `scripts/drill_check.py` is also used by `scripts/drill_lag_alert.py`.
