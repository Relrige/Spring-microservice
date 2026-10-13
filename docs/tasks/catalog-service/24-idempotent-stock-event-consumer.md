---
status: TODO
service: catalog-service
---
# Idempotent Consumer for Stock Events (Processed-Events Table)

## Context
Kafka delivery is at-least-once. The same stock event can be processed twice in these cases:
- the consumer crashes after the database commit but before the offset commit;
- a partition rebalance hands an uncommitted record to the other replica;
- Inventory's outbox relay republishes a row after a crash;
- a retry-topic copy succeeds although the original's effect was already stored.

The **Idempotent Consumer** pattern records the `eventId` of every processed event in a table, in **the same transaction** as the business effect. A second delivery finds the key and does nothing.

For the stock-status update, the version guard of [task 23](23-stock-event-version-guard.md) already makes a duplicate harmless. The table is added anyway, for two reasons:
- it is the general mechanism, and it keeps working when a handler's effect is not a compare-and-set;
- it makes "this event was already processed" an explicit, queryable fact, which the reliability experiment uses as evidence ([cross-cutting task 01](../cross-cutting/01-stock-events-reliability-experiment.md)).
*References:* [Consume stock events](18-consume-stock-events.md), [Version guard](23-stock-event-version-guard.md), [Event Catalogue — `eventId` convention](../../design/services-requirements/events-catalogue.md)

## Acceptance Criteria
- [ ] Flyway migration creating `processed_events`:
  - `consumer` (text, which handler processed the event, for example `stock-events`);
  - `event_id` (UUID);
  - `processed_at` (timestamp);
  - **primary key `(consumer, event_id)`**;
  - an index on `processed_at` for cleanup.
- [ ] `eventId` is mandatory in Catalog's stock-event record. A payload without it is a poison message (dead-letter handling of [task 25](25-stock-events-retryable-topic-and-dlt.md)).
- [ ] The handler runs in **one** `@Transactional` service method, in this order:
  1. `INSERT INTO processed_events … ON CONFLICT DO NOTHING`;
  2. if 0 rows were inserted → duplicate: log at INFO (`eventId`, `productId`), skip the business logic, acknowledge;
  3. otherwise → apply the version-guarded update of task 23.

  If step 3 throws, the transaction rolls back and the key is **not** stored, so the retry processes the event again.
- [ ] Events that end without a status change are recorded as processed too: unknown product, stale version. Document this choice.
- [ ] Concurrent duplicates are safe: the second transaction blocks on the primary key until the first commits, then sees the conflict and skips. Covered by a test.
- [ ] A Micrometer counter (or at least a structured log line) for "duplicate event skipped", tagged with `consumer`. The experiment reads it.
- [ ] Old rows are deleted after a configurable retention period (default several days, longer than any realistic redelivery window: topic retention, outbox relay backlog). The cleanup is a scheduled job, and it is tested.
- [ ] Tests (Testcontainers PostgreSQL + Kafka):
  - an event processed once stores one key;
  - the same record published twice → one key, one status change, one "duplicate skipped";
  - the business update fails (for example an injected exception) → no key is stored, and the redelivery applies the event;
  - two threads processing the same `eventId` concurrently → exactly one applies it;
  - retention cleanup removes only old rows.

## Technical Notes / Constraints
- **One transaction manager:** `processed_events` lives in Catalog's own database, and the key and the effect are committed by the same JPA transaction. That is what makes "processed" and "applied" atomic. A key stored in Redis or in a separate transaction would reopen the gap between the two.
- **Insert first, not "select, then insert":** a `SELECT` check followed by an `INSERT` lets two concurrent duplicates both pass the check. The primary key is the real guard.
- **Offset commit vs. key commit:** the key is committed before the offset. A crash between the two is exactly the duplicate this table catches. Name the ack mode used (task 18).
- **Retry-topic copies** ([task 25](25-stock-events-retryable-topic-and-dlt.md)) keep the payload, so they keep the same `eventId` and the dedup works across main and retry topics.
- Keep the mechanism generic: for example a small `ProcessedEvents.markProcessed(consumer, eventId): boolean` component that other Catalog consumers can reuse later (`ProductCreated` stays Inventory's concern).
- Discuss in the notes: when does a consumer **not** need such a table? Compare with Inventory's state-based idempotency (`insertIfAbsent`, the `ACTIVE` status filter in Inventory task 16).
