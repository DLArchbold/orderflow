# ADR 0004: Blocking retry with a dead-letter topic

Status: accepted

## Context
A consumer record can fail for a reason that goes away (a database blip) or one that never does (unreadable JSON).
Without handling, a failing record is retried forever and every record behind it on the partition waits.

## Decision
Use Spring Kafka's `DefaultErrorHandler`: retry a failing record with exponential backoff (initial 1 s, x2, max 10 s,
3 attempts in total), then publish it to `<topic>.DLT` with `DeadLetterPublishingRecoverer` and move on.
Unreadable payloads (`JsonProcessingException`, `IllegalArgumentException`) and a missing `message-id` header are
not retried. The DLT counter `orderflow.dlt.messages` feeds the alert in step 21.

This is **blocking** retry: while a record is retried, its partition waits. `@RetryableTopic` retries without blocking
by moving the record through extra retry topics, at the price of more topics and more moving parts. With one
partition and short backoffs, blocking is simpler and its cost is small, so it was chosen.

## Consequences
- The DLT needs at least as many partitions as the source topic (both have 1 here).
- A person can replay dead letters after fixing the cause (`POST /admin/dlt/replay`, step 16c); the consumers are
  idempotent, so a replay cannot reserve stock twice.
