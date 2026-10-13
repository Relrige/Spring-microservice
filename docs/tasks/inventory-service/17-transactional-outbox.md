---
status: TODO
service: inventory-service
---
# Transactional Outbox for Stock Events

## Context
Adjust, reserve and release publish `ProductStockReplenished` / `ProductStockDepleted` with a plain `KafkaTemplate` after commit ([task 06](06-stock-events-publish-and-listen.md)). If the process crashes between the commit and the publish, or the broker is down, the stock changed but Catalog never learns, and `Product.stockStatus` stays wrong (a customer may see "in stock" for a depleted product, or the reverse). The database write and the publish cannot be made atomic together (dual-write problem).

The outbox pattern stores the event in an `outbox` table in the **same transaction** as the stock change; a relay publishes it afterwards. Delivery becomes at-least-once; Catalog's consumers are idempotent by design ([Catalog stock-event consumers](../catalog-service/18-consume-stock-events.md)).

Unlike Catalog's single `ProductCreated` event, **Inventory's events are order-sensitive**: a `Depleted` followed by `Replenished` for one product must reach Catalog in that order. The relay must preserve it.

This task is the **producer half** of the course's "reliable inter-service messaging" assignment (Inventory → Catalog stock events). The consumer half is in Catalog tasks [23](../catalog-service/23-stock-event-version-guard.md) (per-product version guard), [24](../catalog-service/24-idempotent-stock-event-consumer.md) (idempotent consumer with a processed-events table) and [25](../catalog-service/25-stock-events-retryable-topic-and-dlt.md) (`@RetryableTopic` + dead-letter topic), and the end-to-end demonstration is in [cross-cutting task 01](../cross-cutting/01-stock-events-reliability-experiment.md). Because Catalog will use non-blocking retries, which can reorder events of one product on the consumer side, every stock event now also carries a **per-product `version`** so that Catalog can discard stale events.
*References:* [Inventory Spec — Emits](../../design/services-specs/inventory-service-spec.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog transactional outbox task](../catalog-service/19-transactional-outbox.md)

## Acceptance Criteria
- [ ] Flyway migration creating an `outbox` table: event id (UUID), aggregate id (`productId`), event type, topic, message key, JSON payload, `created_at`, a published marker, and a monotonically increasing sequence (identity column) used for ordering. Index for "oldest unpublished first".
- [ ] The adjust, reserve and release services append the event to the outbox **inside the same transaction** as the stock change (and the reservation rows). If anything fails, neither stock nor event is stored.
- [ ] **One transaction manager:** the stock change, the reservation rows and the outbox row are written through the service's single JPA `TransactionManager` on one `DataSource`. No Kafka transactions and no chained/XA transaction manager are involved; Kafka is touched only by the relay, outside the business transaction. State this in the outbox class's Javadoc, and in the task notes explain why it removes the dual-write problem.
- [ ] **Per-product event version:** a Flyway migration adds `stock_event_version BIGINT NOT NULL DEFAULT 0` to `stock_items` (in the outbox migration or a separate one). Whenever a stock event is emitted for a product, its `stock_event_version` is incremented **in the same locked transaction**, and the event carries the new value as `version` (the first event of a product has `version = 1`). A change that does not cross zero does not touch the version. A rollback reverts the increment and the outbox row together.
- [ ] The `AFTER_COMMIT` `KafkaTemplate` publishing from task 06 is removed from these code paths; the `StockEventPublisher` interface from task 06 now writes to the outbox.
- [ ] A relay (scheduled poller) publishes unpublished rows with `productId` as the key, waits for the broker acknowledgment, then marks the row published.
- [ ] **Per-product ordering is preserved**, including with the Kubernetes Deployment's 2 replicas: events of one `productId` are published in `sequence` order and a later event of a product is never published before an earlier unpublished one. Choose and document the mechanism (for example a single active relay guarded by a PostgreSQL advisory lock or `FOR UPDATE SKIP LOCKED` on the earliest row per product, publishing in batch order).
- [ ] A failing row (for example the broker rejects it) blocks only later events of the same product, not other products, and is retried with back-off; document the policy and what an operator sees (log/metric) when a row is stuck.
- [ ] Each event carries its `eventId` so consumers can deduplicate. The payload is `{ eventId, productId, version, timestamp }`; `version` is the only change versus task 06. Topic, key and `eventType` header are unchanged.
- [ ] Published rows are removed after a configurable retention period; cleanup is tested.
- [ ] Tests (Testcontainers PostgreSQL + Kafka):
  - normal flow: a restock from 0 produces exactly one `Replenished` after commit;
  - broker down during adjust → `200` returned, row stays unpublished, event is published after the broker returns (the gap shown in task 06);
  - rollback (for example a `422` write-off, or a failing reservation) leaves no outbox row;
  - a reserve that depletes several products writes one event per product in the same transaction;
  - ordering: `Replenished`, `Depleted`, `Replenished` for one product arrive in that order even with two relay instances running and a transient publish failure on the first event;
  - version: those three events carry `version` 1, 2, 3; a change that does not cross zero leaves the version unchanged; a rolled-back change leaves it unchanged; a reserve that depletes two products increments each product's version independently;
  - concurrent stock changes of one product (`support/Concurrently`) never produce two events with the same version, and the versions of its emitted events are gap-free (1, 2, 3, …).
- [ ] The relay never fails an HTTP request; it only delays delivery.

## Technical Notes / Constraints
- Compare a polling relay with change data capture (for example Debezium on the Postgres WAL); polling is chosen here for simplicity, note CDC as the alternative.
- Why simple `SKIP LOCKED` batching can reorder: relay A claims rows 1–2 and relay B claims row 3 while A is slow; the event in row 3 may overtake those in rows 1–2 if they belong to the same product. This is the key difference from Catalog's outbox.
- Ordering by a global sequence and a single publisher per batch is the simplest correct approach; per-key parallelism is an optimization that the project does not need.
- The outbox write must happen in the same method/transaction as the stock update (not in an `AFTER_COMMIT` listener).
- Expose at least a log line or metric for "age of the oldest unpublished event".
- The crossing logic (before/after quantity) from tasks 04, 13 and 14 stays unchanged. Only where the event goes changes, plus the version increment.
- **Why a per-product version and not the `timestamp` or the outbox `sequence` (decision, 2026-10-13):**
  - A timestamp from the 2 replicas' clocks can go backwards across pods (clock skew). With ties it gives no order at all.
  - PostgreSQL `now()` is the transaction start time, so a transaction that waited on the row lock can carry an older stamp than the one that went before it.
  - The version is incremented under the `StockItem` row lock that already serializes every change of one product, so it is strictly increasing per product, whatever the clocks say.
  - The outbox `sequence` would also be correct (rows are inserted under the same lock). But it is an implementation detail of the relay: with CDC it may disappear, and Catalog would depend on it. The version is a domain fact ("the n-th stock event of this product"). The `sequence` stays internal to the relay.
  - `timestamp` stays in the payload for logs and people, not for ordering.
- With the version guard in Catalog, a reordering by the relay would no longer corrupt Catalog's state (the stale event is discarded). The relay's ordering requirement is kept anyway: it is the subject of this task and protects any future consumer without such a guard.
- Where to increment: next to the existing `StockEvents.forTransition(...)` calls, all of which already run under the row lock. A small method on `StockItemEntity` (for example `nextStockEventVersion()`) keeps the three call sites consistent.

## Starting Point (from tasks 01–14, collected 2026-10-12)
- Callers already go through `StockEventPublisher.publish(StockEvent)` inside the locked transaction (adjust in `StockService`, reserve and release in `ReservationService`), so only the implementation changes. `KafkaStockEventPublisher` (after-commit send) is the class to replace.
- The outbox row must keep everything the current record has: topic `inventory.stock-events`, key `productId`, the **`eventType` header** and the JSON payload `{ eventId, productId, timestamp }` (now extended with `version`). Consumers (Catalog task 18) depend on the header.
- Reserve and release can emit several events in one transaction (one per product that crossed zero); the ordering test should include such a case.
- **Extra motivation found during the sprint-end review:** the producer runs on Kafka client defaults (`max.block.ms` = 60 s, see the correction in [task 06](06-stock-events-publish-and-listen.md)), so with the broker down the current `send()` can block an HTTP request for up to about a minute. The outbox removes `send()` from the request path entirely.

## Deviations from Spec (decided 2026-10-13, spec to be updated at sprint end)
- [Event Catalogue](../../design/services-requirements/events-catalogue.md): `ProductStockReplenished` and `ProductStockDepleted` gain a mandatory `version` field (per-product, strictly increasing, starting at 1). The convention note "Inventory publishes after the database commit; … an event can be lost" is replaced by the outbox guarantee (at-least-once, per-product order).
- [Inventory spec](../../design/services-specs/inventory-service-spec.md): `stock_items.stock_event_version` is a new column; the "Emits" steps of adjust, reserve and release write to the outbox instead of publishing directly.
