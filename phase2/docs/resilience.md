# Resilience drills (PLAN step 26)

## Definitions (written before the drills, so the numbers can be checked)
- **Lost** = an order that has no row in `orders` although the client got a `201` for it, **or** an order still `PENDING` after the
  pipeline has drained (outbox lag back to 0) with nothing in the dead-letter topic. Also counted: an outbox row still unpublished after the drain,
  in either service.
- **Duplicated** = more stock reserved than the CONFIRMED orders account for (per SKU, `reserved` against the quantity of CONFIRMED order
  lines), **or** two rows for one request (an order with more than one `OrderCreated` outbox row, or an order with more than one result row in
  inventory's outbox). Stock must also never go negative, and `available + reserved` must still equal the initial 1,000,000 per SKU.
- **Not counted as duplicated: duplicate records on the Kafka topic.** The publisher is at-least-once, so a long outage produces several
  copies of one event (3 in the manual run of step 13f). The claim worth making is that those copies cause no duplicated *effect*: the
  `processed_messages` table in inventory-service absorbs them.

## How the checks run
`scripts/drill-check.sql` holds the queries, one set per database; `python scripts/drill_check.py --created <orders the client got 201 for>`
runs them against the compose Postgres and prints PASS or FAIL for each definition above. It must pass on a clean system before any drill.

## Drills
Results are recorded below as they are run. Nothing in this section is filled in until it has been run.
