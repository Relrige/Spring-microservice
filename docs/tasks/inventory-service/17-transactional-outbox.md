---
status: TODO
service: inventory-service
---
# Transactional Outbox for Stock Events

## Context
Adjust, reserve and release publish `ProductStockReplenished` / `ProductStockDepleted` with a plain `KafkaTemplate` after commit ([task 06](06-stock-events-publish-and-listen.md)). If the process crashes between the commit and the publish, or the broker is down, the stock changed but Catalog never learns, and `Product.stockStatus` stays wrong (a customer may see "in stock" for a depleted product, or the reverse). The database write and the publish cannot be made atomic together (dual-write problem).

The outbox pattern stores the event in an `outbox` table in the **same transaction** as the stock change; a relay publishes it afterwards. Delivery becomes at-least-once; Catalog's consumers are idempotent by design ([Catalog stock-event consumers](../catalog-service/18-consume-stock-events.md)).

Unlike Catalog's single `ProductCreated` event, **Inventory's events are order-sensitive**: a `Depleted` followed by `Replenished` for one product must reach Catalog in that order. The relay must preserve it.
*References:* [Inventory Spec — Emits](../../design/services-specs/inventory-service-spec.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog transactional outbox task](../catalog-service/19-transactional-outbox.md)

## Acceptance Criteria
- [ ] Flyway migration creating an `outbox` table: event id (UUID), aggregate id (`productId`), event type, topic, message key, JSON payload, `created_at`, a published marker, and a monotonically increasing sequence (identity column) used for ordering. Index for "oldest unpublished first".
- [ ] The adjust, reserve and release services append the event to the outbox **inside the same transaction** as the stock change (and the reservation rows). If anything fails, neither stock nor event is stored.
- [ ] The `AFTER_COMMIT` `KafkaTemplate` publishing from task 06 is removed from these code paths; the `StockEventPublisher` interface from task 06 now writes to the outbox.
- [ ] A relay (scheduled poller) publishes unpublished rows with `productId` as the key, waits for the broker acknowledgment, then marks the row published.
- [ ] **Per-product ordering is preserved**, including with the Kubernetes Deployment's 2 replicas: events of one `productId` are published in `sequence` order and a later event of a product is never published before an earlier unpublished one. Choose and document the mechanism (for example a single active relay guarded by a PostgreSQL advisory lock or `FOR UPDATE SKIP LOCKED` on the earliest row per product, publishing in batch order).
- [ ] A failing row (for example the broker rejects it) blocks only later events of the same product, not other products, and is retried with back-off; document the policy and what an operator sees (log/metric) when a row is stuck.
- [ ] Each event carries its `eventId` so consumers can deduplicate; the payload and topic are unchanged versus task 06.
- [ ] Published rows are removed after a configurable retention period; cleanup is tested.
- [ ] Tests (Testcontainers PostgreSQL + Kafka):
  - normal flow: a restock from 0 produces exactly one `Replenished` after commit;
  - broker down during adjust → `200` returned, row stays unpublished, event is published after the broker returns (the gap shown in task 06);
  - rollback (for example a `422` write-off, or a failing reservation) leaves no outbox row;
  - a reserve that depletes several products writes one event per product in the same transaction;
  - ordering: `Replenished`, `Depleted`, `Replenished` for one product arrive in that order even with two relay instances running and a transient publish failure on the first event.
- [ ] The relay never fails an HTTP request; it only delays delivery.

## Technical Notes / Constraints
- Compare a polling relay with change data capture (for example Debezium on the Postgres WAL); polling is chosen here for simplicity, note CDC as the alternative.
- Why simple `SKIP LOCKED` batching can reorder: relay A claims rows 1–2 and relay B claims row 3 while A is slow; the event in row 3 may overtake those in rows 1–2 if they belong to the same product. This is the key difference from Catalog's outbox.
- Ordering by a global sequence and a single publisher per batch is the simplest correct approach; per-key parallelism is an optimization that the project does not need.
- The outbox write must happen in the same method/transaction as the stock update (not in an `AFTER_COMMIT` listener).
- Expose at least a log line or metric for "age of the oldest unpublished event".
- The crossing logic (before/after quantity) from tasks 04, 13 and 14 stays unchanged; only where the event goes changes.
