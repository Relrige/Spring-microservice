---
status: TODO
service: inventory-service
---
# EP-INV-EVT-01: Consume `ProductCreated`

## Context
When Catalog creates a product it emits `ProductCreated`; Inventory reacts by creating an empty `StockItem` so workers can immediately set packaging and quantities. Delivery is at-least-once, and a `StockItem` may already exist (created on demand by adjust/packaging), so the handler must be idempotent.

Catalog may not publish yet in a given environment; tests publish the event themselves using the payload from the catalogue.
*References:* [Inventory Spec — EP-INV-EVT-01](../../design/services-specs/inventory-service-spec.md), [Event Catalogue — `ProductCreated`](../../design/services-requirements/events-catalogue.md), [Catalog `ProductCreated` producer](../catalog-service/11-product-created-publish-and-listen.md), [StockItem persistence](02-stock-item-entity-and-persistence.md)

## Acceptance Criteria
- [ ] `@KafkaListener` on the topic that Catalog owns for `ProductCreated` (name from configuration, matching the Catalog task 10 decision), in a dedicated consumer group (`inventory-service`).
- [ ] The payload is read into Inventory's **own** record (`productId`, `timestamp`, optional `eventId`); unknown properties are ignored (tolerant reader), no shared event library.
- [ ] If no `StockItem` exists for `productId` → create one with zero quantities and no packaging. If it exists → no action, acknowledge.
- [ ] Duplicate delivery and a race with on-demand creation (adjust/packaging running at the same time) never fail the consumer and never create a second row: creation is race-safe at the database level (`INSERT … ON CONFLICT DO NOTHING` or catching the duplicate-key error and treating it as "already exists").
- [ ] Creating a `StockItem` emits no event.
- [ ] Malformed or undeserializable payloads do not block the partition: after a bounded number of attempts they go to a dead-letter topic (or are skipped and logged; document the choice). Transient database failures are retried with bounded back-off.
- [ ] The consumer starts and the application stays healthy when the Catalog topic does not exist yet (missing topics are not fatal for the listener container).
- [ ] The temporary demonstration listener from [task 06](06-stock-events-publish-and-listen.md) is removed.
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
- **Topic name:** the `ProductCreated` topic is owned by Catalog. It is now decided: `catalog.product-events` (`KafkaTopics.PRODUCT_EVENTS` in catalog-service; 3 partitions, key = `productId`, `eventType` header = `ProductCreated`; the topic may later carry other product event types, so check the header and skip unknown types). Read it from a property (for example `inventory.kafka.topics.product-events`, default `catalog.product-events`) and add the variable to `.env.example`, `docker-compose.yml` and `k8s/inventory/app-config.yaml` (or leave that to [task 18](18-containerization-and-deployment.md)). Inventory must **not** declare Catalog's topic with a `NewTopic` bean.
- **Payload sent by Catalog** (no Java type headers, `spring.json.add.type.headers: false`): `ProductCreatedEvent` in catalog-service's `events` package. Read only the fields Inventory needs into its own record.
- **Replicas:** Kubernetes runs 2 replicas. The fixed group `inventory-service` makes them share the partitions so each event is handled once; do not use a per-instance or random group id.
- **Missing topic:** Spring Kafka's `missing-topics-fatal` defaults to `false`; confirm it with a test that starts the context without the topic.
- **Tests:** the Kafka test container has topic auto-creation **disabled** (`KafkaTestContainerConfig`), so tests must create the Catalog topic themselves (a test `NewTopic` bean or the admin client). Integration tests clean `stock_item_reservations` before `stock_items` (foreign key). `support/Concurrently` runs tasks at the same moment (useful for "event and on-demand adjust at once").
- **Demo listener to remove:** `events/DemoStockEventListener` (group `inventory-stock-events-demo`). No test depends on it; `StockEventsIntegrationTest` has its own collector listener.
- **Spec:** EP-INV-EVT-01 in the [Inventory spec](../../design/services-specs/inventory-service-spec.md) was updated at sprint end (payload includes `eventId`; topic from configuration; missing topic not fatal).
