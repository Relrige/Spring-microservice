---
status: TODO
service: catalog-service
---
# Order-Tolerant Stock Events: Per-Product Version Guard

## Context
[Task 18](18-consume-stock-events.md) relies on Kafka's per-partition order: both stock events share the topic `inventory.stock-events` keyed by `productId`, so events of one product arrive in order. [Task 25](25-stock-events-retryable-topic-and-dlt.md) replaces blocking retries with **non-blocking** retries (`@RetryableTopic`). A record that fails is moved to a retry topic, and the main partition moves on. So an older `Depleted` that failed once can be applied **after** a newer `Replenished` of the same product, and `stockStatus` ends up wrong.

To make the consumer order-tolerant, Inventory now sends a per-product, strictly increasing `version` with every stock event ([Inventory task 17](../inventory-service/17-transactional-outbox.md)). Catalog remembers the last version it applied per product and ignores anything older. Whatever order the events arrive in, the newest one wins.

This is one of the two consumer-side safety mechanisms; the other one, the processed-events table, is [task 24](24-idempotent-stock-event-consumer.md). They solve different problems: this one solves **ordering**, task 24 solves **duplicates**.
*References:* [Consume stock events](18-consume-stock-events.md), [Inventory transactional outbox — version decision](../inventory-service/17-transactional-outbox.md), [Product persistence — `@DynamicUpdate` decision](03-product-entity-and-persistence.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md)

## Acceptance Criteria
- [ ] Flyway migration adds `stock_event_version BIGINT NOT NULL DEFAULT 0` to `products`. Existing and newly created products start at `0` (no stock event applied yet).
- [ ] Catalog's stock-event record gains a mandatory `version` (`long`, `> 0`). A payload without `version` is a poison message: it is not retried, and it goes to the dead-letter handling of [task 25](25-stock-events-retryable-topic-and-dlt.md).
- [ ] The status change is one conditional statement, a compare-and-set:
  ```sql
  UPDATE products SET stock_status = :status, stock_event_version = :version
  WHERE id = :productId AND stock_event_version < :version
  ```
  It touches only these two columns. No load-modify-save of the `Product` entity, so concurrent manager edits are not overwritten. This is consistent with the `@DynamicUpdate` decision of task 03.
- [ ] 0 rows updated means either an unknown product or a stale/duplicate event. Tell them apart (for example with an existence check after the failed update), and log each:
  - unknown product → WARN, acknowledge (as in task 18);
  - stale or duplicate event → DEBUG/INFO with the received and the stored version, acknowledge.

  Neither case is retried.
- [ ] Applying an event whose status equals the current one but whose version is newer still stores the new version. Otherwise a later stale event could slip through.
- [ ] Tests (Testcontainers PostgreSQL, plus Kafka where useful):
  - versions 1 (`Replenished`), 2 (`Depleted`), 3 (`Replenished`) in order → `IN_STOCK`, version 3;
  - out of order: version 2 (`Depleted`) arrives after version 3 (`Replenished`) → stays `IN_STOCK`, version 3, stale event logged;
  - the same version twice → no-op;
  - a payload without `version` → not applied, goes to dead-letter handling;
  - a concurrent manager edit of descriptive fields and a stock event on the same product → both changes survive;
  - two consumers applying versions 2 and 3 concurrently (`support/Concurrently` or equivalent) → final state is version 3.

## Technical Notes / Constraints
- **Dependency:** Inventory must emit `version` first ([Inventory task 17](../inventory-service/17-transactional-outbox.md)). Until then, tests publish events with the extended payload themselves.
- **Old events without `version`:** records published by Inventory before task 17 (still in the topic's retention) have no `version`. They go to the DLT. In local environments it is simpler to recreate the topic or the Kafka volume once both tasks are deployed. Note what was done.
- Why not compare timestamps: see the decision in [Inventory task 17](../inventory-service/17-transactional-outbox.md) (clock skew between replicas, ties, and `now()` being the transaction start time).
- A bulk `UPDATE` (`@Modifying @Query` or `JdbcTemplate`) bypasses the persistence context. That is fine in a listener that loads no `Product` entity in the same transaction. If the transaction loads one, note that it is not refreshed (`clearAutomatically`).
- The guard also makes duplicates harmless for **this** handler. The processed-events table of task 24 is still added: it is the general Idempotent Consumer pattern the course asks for, and it covers handlers whose effect is not a compare-and-set. Explain this overlap in the task notes.
- `stock_event_version` is internal. Do not expose it in product DTOs.

## Deviations from Spec (decided 2026-10-13, spec to be updated at sprint end)
- [Catalog spec](../../design/services-specs/catalog-service-spec.md) EP-CAT-EVT-01/02: the update is guarded by `version`, and stale events are acknowledged without effect. The `products` table gains `stock_event_version`.
- [Event Catalogue](../../design/services-requirements/events-catalogue.md): stock event payloads include `version` (see Inventory task 17).
