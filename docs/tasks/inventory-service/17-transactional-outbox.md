---
status: DONE
service: inventory-service
---
# Transactional Outbox for Stock Events

## Context
Adjust, reserve and release publish `ProductStockReplenished` / `ProductStockDepleted` with a plain `KafkaTemplate` after commit ([task 06](06-stock-events-publish-and-listen.md)). If the process crashes between the commit and the publish, or the broker is down, the stock changed but Catalog never learns, and `Product.stockStatus` stays wrong (a customer may see "in stock" for a depleted product, or the reverse). The database write and the publish cannot be made atomic together (dual-write problem).

The outbox pattern stores the event in an `outbox` table in the **same transaction** as the stock change; a relay publishes it afterwards. Delivery becomes at-least-once; Catalog's consumer is idempotent by state: setting `stockStatus` twice has the same result ([Catalog stock-event consumer](../catalog-service/18-consume-stock-events.md)).

The group assignment on fault-tolerant messaging is already demonstrated on the other direction, Catalog → Inventory `ProductCreated` ([Catalog task 23](../catalog-service/23-retryable-topic-idempotent-consumer-and-report.md), [report](../../reports/messaging-fault-tolerance.md)). This task applies the same producer-side pattern to Inventory's stock events; the report lists it as a known open point.

Unlike Catalog's single `ProductCreated` event, **Inventory's events are order-sensitive**: a `Depleted` followed by `Replenished` for one product must reach Catalog in that order. The relay must preserve it.
*References:* [Inventory Spec — Emits](../../design/services-specs/inventory-service-spec.md), [Event Catalogue](../../design/services-requirements/events-catalogue.md), [Catalog transactional outbox task](../catalog-service/19-transactional-outbox.md)

## Acceptance Criteria
- [x] Flyway migration creating an `outbox` table: event id (UUID), aggregate id (`productId`), event type, topic, message key, JSON payload, `created_at`, a published marker, and a monotonically increasing sequence (identity column) used for ordering. Index for "oldest unpublished first".
- [x] The adjust, reserve and release services append the event to the outbox **inside the same transaction** as the stock change (and the reservation rows). If anything fails, neither stock nor event is stored.
- [x] **One transaction manager:** the stock change, the reservation rows and the outbox row are written through the service's single JPA `TransactionManager` on one `DataSource`. No Kafka transactions and no chained/XA transaction manager; Kafka is touched only by the relay, outside the business transaction. State this in the outbox class's Javadoc.
- [x] The `AFTER_COMMIT` `KafkaTemplate` publishing from task 06 is removed from these code paths; the `StockEventPublisher` interface from task 06 now writes to the outbox.
- [x] A relay (scheduled poller) publishes unpublished rows with `productId` as the key, waits for the broker acknowledgment, then marks the row published.
- [x] **Per-product ordering is preserved**, including with the Kubernetes Deployment's 2 replicas: events of one `productId` are published in `sequence` order and a later event of a product is never published before an earlier unpublished one. Choose and document the mechanism (for example a single active relay guarded by a PostgreSQL advisory lock or `FOR UPDATE SKIP LOCKED` on the earliest row per product, publishing in batch order).
- [x] A failing row (for example the broker rejects it) blocks only later events of the same product, not other products, and is retried with back-off; document the policy and what an operator sees (log/metric) when a row is stuck.
- [x] Each event carries its `eventId` so consumers can deduplicate; the payload and topic are unchanged versus task 06.
- [x] Published rows are removed after a configurable retention period; cleanup is tested.
- [x] Tests (Testcontainers PostgreSQL + Kafka):
  - normal flow: a restock from 0 produces exactly one `Replenished` after commit;
  - broker down during adjust → `200` returned, row stays unpublished, event is published after the broker returns (the gap shown in task 06);
  - rollback (for example a `422` write-off, or a failing reservation) leaves no outbox row;
  - a reserve that depletes several products writes one event per product in the same transaction;
  - ordering: `Replenished`, `Depleted`, `Replenished` for one product arrive in that order even with two relay instances running and a transient publish failure on the first event.
- [x] The relay never fails an HTTP request; it only delays delivery.

## Technical Notes / Constraints
- Compare a polling relay with change data capture (for example Debezium on the Postgres WAL); polling is chosen here for simplicity, note CDC as the alternative.
- Why simple `SKIP LOCKED` batching can reorder: relay A claims rows 1–2 and relay B claims row 3 while A is slow; the event in row 3 may overtake those in rows 1–2 if they belong to the same product. This is the key difference from Catalog's outbox.
- Ordering by a global sequence and a single publisher per batch is the simplest correct approach; per-key parallelism is an optimization that the project does not need.
- The outbox write must happen in the same method/transaction as the stock update (not in an `AFTER_COMMIT` listener).
- Expose at least a log line or metric for "age of the oldest unpublished event".
- The crossing logic (before/after quantity) from tasks 04, 13 and 14 stays unchanged; only where the event goes changes.

## Starting Point (from tasks 01–14, collected 2026-10-12)
- Callers already go through `StockEventPublisher.publish(StockEvent)` inside the locked transaction (adjust in `StockService`, reserve and release in `ReservationService`), so only the implementation changes. `KafkaStockEventPublisher` (after-commit send) is the class to replace.
- The outbox row must keep everything the current record has: topic `inventory.stock-events`, key `productId`, the **`eventType` header** and the JSON payload `{ eventId, productId, timestamp }`. Consumers (Catalog task 18) depend on the header.
- Reserve and release can emit several events in one transaction (one per product that crossed zero); the ordering test should include such a case.
- **Reference implementation in Catalog** ([task 19](../catalog-service/19-transactional-outbox.md), merged 2026-10-13): `catalog/outbox/` with `Outbox.append(...)` (`Propagation.MANDATORY`, so it fails without a surrounding transaction), `OutboxRelay` (claims due rows with `FOR UPDATE SKIP LOCKED`, sends, waits for the acknowledgment, back-off via `next_attempt_at`, cleanup job) and `V3__create_outbox_table.sql`. Reuse its structure, but **not** its failure policy: Catalog's relay lets newer events overtake a failing one ("one poison event never blocks the rest"), which is exactly the reordering this task must prevent per product. Inventory needs the per-product blocking described above.
- **Headers:** Catalog's relay also sends the `eventId` as a record header next to `eventType`. Do the same here for consistency (the payload keeps `eventId` too).
- **Kafka templates after the merge:** Inventory now has two `KafkaTemplate` beans (`KafkaConfig`): the `@Primary` JSON one used by `KafkaStockEventPublisher`, and a `StringSerializer` one reserved for `@RetryableTopic` forwarding. If the outbox stores the payload as JSON text, sending that `String` with the JSON template would encode it a second time (a quoted string); send it as a JSON tree (as Catalog's relay does) or define a dedicated text template for the relay. Cover it with a wire-format test.
- **Migration number:** `V3` is taken by `processed_events` (merged from main); the outbox migration is `V4`.
- **Extra motivation found during the sprint-end review:** the producer runs on Kafka client defaults (`max.block.ms` = 60 s, see the correction in [task 06](06-stock-events-publish-and-listen.md)), so with the broker down the current `send()` can block an HTTP request for up to about a minute. The outbox removes `send()` from the request path entirely.

## Implementation Notes (2026-10-14)
- **Code:** package `outbox/`: `OutboxStockEventPublisher` implements `StockEventPublisher` and appends the event (`Propagation.MANDATORY`) inside the stock transaction; `OutboxRelay` publishes; `OutboxEvent` / `OutboxRepository` / `OutboxProperties` as in Catalog. `KafkaStockEventPublisher` and its test are deleted; the callers (`StockService.adjustStock`, `ReservationService.reserve/release`) did not change.
- **Migration:** `V4__create_outbox_events_table.sql` (table `outbox_events`, the same name as Catalog's). `sequence_number BIGINT GENERATED ALWAYS AS IDENTITY` gives the publishing order (named `sequence_number` rather than `sequence` to avoid an SQL keyword). Partial indexes: unpublished by sequence, unpublished by `(aggregate_id, sequence_number)`, published by `published_at` (cleanup).
- **Ordering mechanism (chosen):** a single active relay. Each run is one transaction that first takes `pg_try_advisory_xact_lock(...)`; a replica that does not get it skips the run (no waiting). The relay sends rows in `sequence_number` order and waits for each acknowledgment (`get(publish-timeout)`) before the next one. For one product the sequence order is the commit order, because every event of a product is written while that product's stock row is locked (`SELECT … FOR UPDATE`). The lock is released on commit/rollback, and also when a pod dies (its connection closes).
- **Failure policy:** a failed row gets `attempts + 1`, `last_error` and an exponential back-off (1 s, 2 s, 4 s, … capped by `inventory.outbox.max-retry-delay` = 5 min) and is retried forever. The relay's query skips every product that has an earlier unpublished row still waiting (`NOT EXISTS` on an earlier unpublished row with `next_attempt_at > now`), so later events of that product are held back while other products keep flowing. The run stops at the first failure (during a broker outage every send would fail after a timeout); the next run, 500 ms later, already skips the waiting product. This is the deliberate difference from Catalog's relay, which lets newer events overtake a failing one.
- **What an operator sees:** an `ERROR` log per failed attempt with the event id, type, product, attempt number, how long it has been unpublished and the next retry, plus the gauge `inventory.outbox.oldest.unpublished.age` (seconds; 0 when nothing waits) via Actuator/Micrometer.
- **Wire format unchanged:** topic `inventory.stock-events`, key `productId`, headers `eventType` and (new, as in Catalog) `eventId`, payload `{ eventId, productId, timestamp }`. The stored JSON is sent as a JSON tree with the `@Primary` JSON template, so it is not double-encoded; covered by a wire-format test.
- **Producer settings:** `acks=all`, `enable.idempotence=true` stated explicitly, and `max.block.ms=5000` (was the 60 s default), so a dead broker costs the relay at most 5 s per attempt. No HTTP request calls `send()` any more.
- **Retention:** `inventory.outbox.retention` = 7 d, cleanup every hour (`deletePublishedEvents`).
- **CDC alternative:** Debezium reading the PostgreSQL WAL would remove polling and the relay lock, but needs Kafka Connect as extra infrastructure; polling is enough for this project.
- **Tests:** `outbox/OutboxIntegrationTest` (Testcontainers PostgreSQL + Kafka, relay run by hand): normal flow + wire format; broker down → `200`, row unpublished with `attempts = 1`, published after the broker returns; rejected write-off leaves no row; failing outbox write rolls back the stock change; reservation failing after the events were written rolls back events and stock; reserve depleting two products writes two rows in one transaction; `Replenished, Depleted, Replenished` keep their order with two relay instances and a transient failure on the first event; a failing product holds back only its own later events; a run skips while another replica holds the lock; `MANDATORY` outside a transaction; cleanup. `AdjustStockBrokerDownTest` now also checks the unpublished outbox row; `StockEventsIntegrationTest` passes unchanged through the scheduled relay.
- **Docs to update at sprint end:** Inventory spec *Emits → Delivery (current)* and the open-points table still describe the after-commit `KafkaTemplate`; the [fault-tolerance report](../../reports/messaging-fault-tolerance.md) lists this outbox as an open point.
