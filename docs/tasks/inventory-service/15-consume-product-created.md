---
status: IN_PROGRESS
service: inventory-service
---
# EP-INV-EVT-01: Consume `ProductCreated`

## Context
When Catalog creates a product it emits `ProductCreated`; Inventory reacts by creating an empty `StockItem` so workers can immediately set packaging and quantities. Delivery is at-least-once, and a `StockItem` may already exist (created on demand by adjust/packaging), so the handler must be idempotent.

Catalog may not publish yet in a given environment; tests publish the event themselves using the payload from the catalogue.
*References:* [Inventory Spec — EP-INV-EVT-01](../../design/services-specs/inventory-service-spec.md), [Event Catalogue — `ProductCreated`](../../design/services-requirements/events-catalogue.md), [Catalog `ProductCreated` producer](../catalog-service/11-product-created-publish-and-listen.md), [StockItem persistence](02-stock-item-entity-and-persistence.md)

## Acceptance Criteria
- [x] `@KafkaListener` on the topic that Catalog owns for `ProductCreated` (name from configuration, matching the Catalog task 10 decision), in a dedicated consumer group (`inventory-service`).
- [x] The payload is read into Inventory's **own** record (`productId`, `timestamp`, optional `eventId`); unknown properties are ignored (tolerant reader), no shared event library.
- [x] If no `StockItem` exists for `productId` → create one with zero quantities and no packaging. If it exists → no action, acknowledge.
- [x] Duplicate delivery and a race with on-demand creation (adjust/packaging running at the same time) never fail the consumer and never create a second row: creation is race-safe at the database level (`INSERT … ON CONFLICT DO NOTHING` or catching the duplicate-key error and treating it as "already exists").
- [x] Creating a `StockItem` emits no event.
- [x] Malformed or undeserializable payloads do not block the partition: after a bounded number of attempts they go to a dead-letter topic (or are skipped and logged; document the choice). Transient database failures are retried with bounded back-off.
- [x] The consumer starts and the application stays healthy when the Catalog topic does not exist yet (missing topics are not fatal for the listener container).
- [x] The temporary demonstration listener from [task 06](06-stock-events-publish-and-listen.md) is removed.
- [ ] Tests (Testcontainers Kafka + PostgreSQL): a new product creates a `StockItem`; the same event twice creates one; an event for an existing `StockItem` (with quantities) changes nothing; a poison message does not stop later messages.

## Technical Notes / Constraints
- Offsets are committed only after the database transaction succeeded; state the acknowledgment mode used and why.
- Remember: a plain `save()` on an entity with an assigned ID does a select-then-insert, which is racy. See the persistence note in [task 02](02-stock-item-entity-and-persistence.md).
- The event may arrive before or after a worker's first action on the product; both orders must end in the same state.

## Starting Point (from tasks 01–14, collected 2026-10-12)
What already exists and should be reused rather than rebuilt:
- **Race-safe creation:** `StockItemRepository.insertIfAbsent(productId)` (native `INSERT … ON CONFLICT (product_id) DO NOTHING`). It is exactly the operation this handler needs; no row lock is required because nothing else is changed. Put the handler logic in a `@Transactional` service method (for example `StockService.ensureStockItem(productId)`), not in the listener.
- **Deserialization:** values are read as `String`, and the `StringJacksonJsonMessageConverter` bean in `KafkaConfig` converts the JSON into the type of the `@KafkaListener` method parameter, ignoring unknown properties and type headers ([task 05](05-kafka-spring-setup-and-topic.md)). So a malformed payload fails in the **listener adapter** (a conversion exception), not in the Kafka deserializer: an `ErrorHandlingDeserializer` would not catch it. Spring Kafka's `DefaultErrorHandler` treats conversion exceptions as **not retryable** and hands them straight to the recoverer, which is what a poison message needs; database exceptions are retried with the configured back-off. Verify this classification with a test.
- **Consumer group:** `spring.kafka.consumer.group-id: inventory-service` and `auto-offset-reset: earliest` are already in `application.yml`.
- **Topic name:** the `ProductCreated` topic is owned by Catalog and is being decided in the Catalog PR. Read it from a property (for example `inventory.kafka.topics.product-events`) and align the value with Catalog when that PR is merged. Inventory must **not** declare Catalog's topic with a `NewTopic` bean.
- **Missing topic:** Spring Kafka's `missing-topics-fatal` defaults to `false`; confirm it with a test that starts the context without the topic.
- **Tests:** the Kafka test container has topic auto-creation **disabled** (`KafkaTestContainerConfig`), so tests must create the Catalog topic themselves (a test `NewTopic` bean or the admin client). Integration tests clean `stock_item_reservations` before `stock_items` (foreign key). `support/Concurrently` runs tasks at the same moment (useful for "event and on-demand adjust at once").
- **Demo listener to remove:** `events/DemoStockEventListener` (group `inventory-stock-events-demo`). No test depends on it; `StockEventsIntegrationTest` has its own collector listener.
- **Spec:** EP-INV-EVT-01 in the [Inventory spec](../../design/services-specs/inventory-service-spec.md) was updated at sprint end (payload includes `eventId`; topic from configuration; missing topic not fatal).

## Implementation Notes (2026-10-12)
- **Code:** `events/ProductCreatedListener` → `StockService.ensureStockItem(productId)` (`@Transactional`, reuses `insertIfAbsent`; returns whether a row was created). Own record `events/ProductCreatedEvent(eventId?, productId, timestamp?)`; `productId` is required in the compact constructor, so a payload without it fails during conversion and is a poison message.
- **Topic:** `inventory.kafka.topics.product-events` = `catalog.product-events` (matches Catalog's `KafkaTopics.PRODUCT_EVENTS`); not declared by Inventory.
- **Event-type filter (decision):** the topic is per aggregate, so it may carry other product events later. A `RecordFilterStrategy` (`KafkaConfig.productCreatedOnly`) keeps only records with header `eventType: ProductCreated` (Catalog sets it); everything else is discarded before conversion and acknowledged.
- **Errors (decision):** dead-letter topic, not skip. `DefaultErrorHandler` with 4 retries and exponential back-off (1, 2, 4, 8 s), then `DeadLetterPublishingRecoverer` to `inventory.catalog.product-events.dlt` (owned and declared by Inventory, 1 partition; the record value is republished as the same JSON text). Conversion failures are not retried.
- **Ack mode:** container default `BATCH`: a poll's offsets are committed after the listener returned for its records, i.e. after the transaction committed. A redelivery after a crash is absorbed by the idempotent insert.
- **Missing topic:** `missing-topics-fatal` stays at its default `false`; `MissingSourceTopicsStartupTest` checks that the listeners run and readiness is `UP` without the topic.
- The demo listener from task 06 was removed.
- **Open:** the Testcontainers tests (`ConsumedEventsIntegrationTest`, `MissingSourceTopicsStartupTest`) are written but were **not run yet**, because Docker was not available; the task stays `IN_PROGRESS` until they pass. Unit tests pass.
